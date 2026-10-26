package com.grulla.music.score

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.grulla.music.ui.DayColors
import com.grulla.music.ui.NightColors
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

enum class LayoutMode(val js: String) { CONTINUOUS("continuous"), PAGE("page") }

/** Kotlin -> JS calls on `window.grulla` (see assets/web/score.js). */
sealed interface WebCommand {
    data class Load(val url: String, val scale: Int, val mode: LayoutMode, val transpose: String = "", val concertPitch: Boolean = false) : WebCommand
    data class SetLayout(val scale: Int, val mode: LayoutMode) : WebCommand
    data class SetOptions(val earlyTurn: Boolean) : WebCommand
    data class Highlight(val noteIds: List<String>, val follow: Boolean, val kind: Kind = Kind.PLAY) : WebCommand {
        /** PLAY: sounding now (crown red). LISTEN: what to play next in Follow me (indigo). */
        enum class Kind(val js: String) { PLAY("play"), LISTEN("listen") }
    }
    data class ShowPage(val page: Int) : WebCommand
    /** Next (+1) or previous (-1) page, spread or screenful. */
    data class Turn(val direction: Int) : WebCommand
    data class CountIn(val beat: Int, val total: Int) : WebCommand
    /** Shade measures [firstId]..[lastId]; null clears the loop shading. */
    data class SetLoop(val firstId: String?, val lastId: String?) : WebCommand
    data class Thumbnail(val width: Int) : WebCommand
    /** The saved marks of the score ({"marks": [...]}, see annotate.js). */
    data class SetAnnotations(val json: String) : WebCommand
    /** Start drawing with [tool] ("pen", "text", "breath", "erase"), or stop with null. */
    data class Annotate(val tool: String?, val text: String = "1") : WebCommand
    data object UndoAnnotation : WebCommand

    /** Guarded so that calls made before score.js has loaded are dropped instead of throwing. */
    fun toJavaScript(): String = "window.grulla && " + when (this) {
        is Load -> "grulla.load(${JSONObject.quote(url)}, ${layoutJson(scale, mode)}, " +
            "${JSONObject().put("transpose", transpose).put("concertPitch", concertPitch)})"
        is SetLayout -> "grulla.setLayout(${layoutJson(scale, mode)})"
        is SetOptions -> "grulla.setOptions(${JSONObject().put("earlyTurn", earlyTurn)})"
        is Highlight -> "grulla.highlight(${JSONArray(noteIds)}, $follow, '${kind.js}')"
        is ShowPage -> "grulla.showPage($page)"
        is Turn -> "grulla.turn($direction)"
        is CountIn -> "grulla.countIn($beat, $total)"
        is SetLoop -> "grulla.setLoop(${firstId?.let(JSONObject::quote)}, ${lastId?.let(JSONObject::quote)})"
        is Thumbnail -> "grulla.thumbnail($width)"
        is SetAnnotations -> "grulla.setAnnotations(${JSONObject.quote(json)})"
        is Annotate -> "grulla.annotate(${tool?.let(JSONObject::quote)}, ${JSONObject.quote(text)})"
        UndoAnnotation -> "grulla.undoAnnotation()"
    }

    private fun layoutJson(scale: Int, mode: LayoutMode) =
        JSONObject().put("scale", scale).put("mode", mode.js).toString()
}

/** JS -> Kotlin callbacks. Always invoked on the main thread. */
interface ScoreWebCallbacks {
    fun onWebReady(verovioVersion: String)
    /** The first page is on screen; timemap and MIDI follow with [onScoreRendered]. */
    fun onFirstPage()
    fun onScoreRendered(payloadJson: String)
    fun onMeasureTapped(measureId: String)
    fun onMeasureLongPressed(measureId: String)
    fun onZoomChanged(scale: Int)
    /** [spread]: two pages side by side, [page] being the left one. */
    fun onPageChanged(page: Int, pageCount: Int, spread: Boolean)
    fun onWebError(message: String)
    fun onThumbnail(pngDataUrl: String)
    fun onAnnotationsChanged(json: String)

    companion object {
        /** [onWebError] message when the WebView's renderer died and the view was recreated. */
        const val RENDERER_GONE = "renderer-gone"
    }
}

/**
 * Web origin under which the bundled page and the current score are served.
 * Using WebViewAssetLoader (instead of file:// URLs) keeps file access disabled
 * and gives the page a normal https origin, so fetch() works.
 */
object ScoreOrigin {
    const val DOMAIN = WebViewAssetLoader.DEFAULT_DOMAIN
    const val PAGE_URL = "https://$DOMAIN/assets/web/index.html"
    const val SCORE_PATH = "/score/"

    /** A new URL per loaded score so nothing is served from a stale cache. */
    fun scoreUrl(version: Int) = "https://$DOMAIN${SCORE_PATH}$version.musicxml"
}

/**
 * Owns the score WebView and keeps it alive while the user is elsewhere in the
 * app (the library, settings) and across rotation, so returning to a score is
 * instant instead of starting Verovio and laying out the score again.
 *
 * The WebView is created on a [MutableContextWrapper]: while it is on screen its
 * base context is the activity, otherwise the application, so no activity leaks.
 */
class ScoreWebViewHost(
    context: Context,
    private val callbacks: ScoreWebCallbacks,
    private val scoreBytes: () -> ByteArray?,
) {
    private val app = context.applicationContext
    private val contextWrapper = MutableContextWrapper(app)
    private val theme = ThemeHolder()

    /** Bumped when the render process died and a new WebView replaced the old one. */
    var generation by mutableIntStateOf(0)
        private set

    private var current: WebView? = null

    /** The WebView, attached to [activity]'s context. */
    fun obtain(activity: Context): WebView {
        contextWrapper.baseContext = activity
        val webView = current ?: createScoreWebView(contextWrapper, callbacks, scoreBytes, theme, onRendererGone = ::replace)
        current = webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    /** The screen showing the WebView went away; keep it, but let go of the activity. */
    fun detach(webView: WebView) {
        if (webView !== current) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        contextWrapper.baseContext = app
    }

    fun setNight(night: Boolean) {
        theme.night = night
        current?.let { applyTheme(it) }
    }

    fun run(command: WebCommand) {
        current?.evaluateJavascript(command.toJavaScript(), null)
    }

    fun destroy() {
        current?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        current = null
    }

    private fun replace() {
        val old = current
        current = null
        (old?.parent as? ViewGroup)?.removeView(old)
        old?.destroy()
        generation++
    }

    private fun applyTheme(webView: WebView) {
        webView.setBackgroundColor((if (theme.night) NightColors.paper else DayColors.paper).toArgb())
        webView.evaluateJavascript(theme.script(), null)
    }
}

/**
 * Shows the score page of [host]. [commands] are forwarded to JavaScript in order
 * while the view is on screen (they wait in the flow's buffer otherwise).
 */
@Composable
fun ScoreView(
    host: ScoreWebViewHost,
    commands: Flow<WebCommand>,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val generation = host.generation
    val webView = remember(generation, context) { host.obtain(context) }
    key(generation) {
        AndroidView(factory = { webView }, modifier = modifier)
    }
    LaunchedEffect(webView, night) { host.setNight(night) }
    LaunchedEffect(webView) {
        commands.collect { command -> host.run(command) }
    }
    DisposableEffect(webView) {
        onDispose { host.detach(webView) }
    }
}

/** Day or night for the page; read again whenever the page (re)loads. */
private class ThemeHolder {
    @Volatile
    var night = false

    fun script() = "window.grulla && grulla.setTheme('${if (night) "night" else "day"}')"
}

@SuppressLint("SetJavaScriptEnabled")
private fun createScoreWebView(
    context: Context,
    callbacks: ScoreWebCallbacks,
    scoreBytes: () -> ByteArray?,
    theme: ThemeHolder,
    onRendererGone: () -> Unit,
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ScoreOrigin.DOMAIN)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(ScoreOrigin.SCORE_PATH) { _ ->
            scoreBytes()?.let { WebResourceResponse("application/xml", "utf-8", ByteArrayInputStream(it)) }
        }
        .build()

    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect in debug builds
    }

    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false) // score.js handles pinch zoom by re-laying out the score
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        setBackgroundColor((if (theme.night) NightColors.paper else DayColors.paper).toArgb())
        webViewClient = ScoreWebViewClient(assetLoader, callbacks, theme, onRendererGone)
        // Only our own bundled page is ever loaded, so exposing the bridge is safe.
        addJavascriptInterface(ScoreHostBridge(callbacks), "GrullaHost")
        loadUrl(ScoreOrigin.PAGE_URL)
    }
}

// onRenderProcessGone *is* implemented below; androidx.webkit 1.17.1's lint check still reports it.
@SuppressLint("MissingOnRenderProcessGone")
private class ScoreWebViewClient(
    private val assetLoader: WebViewAssetLoader,
    private val callbacks: ScoreWebCallbacks,
    private val theme: ThemeHolder,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(theme.script(), null)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        assetLoader.shouldInterceptRequest(request.url)

    // The page never navigates; refuse anything that tries to.
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host != ScoreOrigin.DOMAIN

    // Large scores can push the renderer out of memory. Without this the whole app would crash.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.w("ScoreView", "WebView renderer gone (crashed: ${detail.didCrash()}); recreating")
        callbacks.onWebError(ScoreWebCallbacks.RENDERER_GONE)
        onRendererGone()
        return true
    }
}

/** The `GrullaHost` object seen by score.js. JavaBridge calls arrive on a background thread. */
private class ScoreHostBridge(private val callbacks: ScoreWebCallbacks) {
    private val main = Handler(Looper.getMainLooper())

    private fun onMain(block: ScoreWebCallbacks.() -> Unit) {
        main.post { callbacks.block() }
    }

    @JavascriptInterface
    fun onReady(version: String) = onMain { onWebReady(version) }

    @JavascriptInterface
    fun onFirstPage() = onMain { onFirstPage() }

    @JavascriptInterface
    fun onScoreLoaded(payloadJson: String) = onMain { onScoreRendered(payloadJson) }

    @JavascriptInterface
    fun onMeasureTapped(measureId: String) = onMain { onMeasureTapped(measureId) }

    @JavascriptInterface
    fun onMeasureLongPressed(measureId: String) = onMain { onMeasureLongPressed(measureId) }

    @JavascriptInterface
    fun onZoomChanged(scale: Int) = onMain { onZoomChanged(scale) }

    @JavascriptInterface
    fun onPageChanged(page: Int, pageCount: Int, spread: Boolean) = onMain { onPageChanged(page, pageCount, spread) }

    @JavascriptInterface
    fun onThumbnail(pngDataUrl: String) = onMain { onThumbnail(pngDataUrl) }

    @JavascriptInterface
    fun onAnnotationsChanged(json: String) = onMain { onAnnotationsChanged(json) }

    @JavascriptInterface
    fun onError(message: String) {
        Log.w("ScoreView", "score.js: $message")
        onMain { onWebError(message) }
    }
}
