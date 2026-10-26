package com.grulla.music.score

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    data class Load(val url: String, val scale: Int, val mode: LayoutMode) : WebCommand
    data class SetLayout(val scale: Int, val mode: LayoutMode) : WebCommand
    data class Highlight(val noteIds: List<String>, val follow: Boolean, val kind: Kind = Kind.PLAY) : WebCommand {
        /** PLAY: sounding now (crown red). LISTEN: what to play next in Follow me (indigo). */
        enum class Kind(val js: String) { PLAY("play"), LISTEN("listen") }
    }
    data class ShowPage(val page: Int) : WebCommand
    data class CountIn(val beat: Int, val total: Int) : WebCommand
    /** Shade measures [firstId]..[lastId]; null clears the loop shading. */
    data class SetLoop(val firstId: String?, val lastId: String?) : WebCommand
    data class Thumbnail(val width: Int) : WebCommand

    /** Guarded so that calls made before score.js has loaded are dropped instead of throwing. */
    fun toJavaScript(): String = "window.grulla && " + when (this) {
        is Load -> "grulla.load(${JSONObject.quote(url)}, ${layoutJson(scale, mode)})"
        is SetLayout -> "grulla.setLayout(${layoutJson(scale, mode)})"
        is Highlight -> "grulla.highlight(${JSONArray(noteIds)}, $follow, '${kind.js}')"
        is ShowPage -> "grulla.showPage($page)"
        is CountIn -> "grulla.countIn($beat, $total)"
        is SetLoop -> "grulla.setLoop(${firstId?.let(JSONObject::quote)}, ${lastId?.let(JSONObject::quote)})"
        is Thumbnail -> "grulla.thumbnail($width)"
    }

    private fun layoutJson(scale: Int, mode: LayoutMode) =
        JSONObject().put("scale", scale).put("mode", mode.js).toString()
}

/** JS -> Kotlin callbacks. Always invoked on the main thread. */
interface ScoreWebCallbacks {
    fun onWebReady(verovioVersion: String)
    fun onScoreRendered(payloadJson: String)
    fun onMeasureTapped(measureId: String)
    fun onMeasureLongPressed(measureId: String)
    fun onZoomChanged(scale: Int)
    fun onPageChanged(page: Int, pageCount: Int)
    fun onWebError(message: String)
    fun onThumbnail(pngDataUrl: String)
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
 * Hosts the score page. [commands] are forwarded to JavaScript in order;
 * [scoreBytes] supplies the MusicXML document requested by `grulla.load()`.
 */
@Composable
fun ScoreView(
    commands: Flow<WebCommand>,
    callbacks: ScoreWebCallbacks,
    scoreBytes: () -> ByteArray?,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val theme = remember { ThemeHolder() }
    theme.night = night
    // Bumped when the WebView's render process dies; a fresh WebView then reloads the score.
    var generation by remember { mutableIntStateOf(0) }
    val webView = remember(generation) {
        createScoreWebView(context, callbacks, scoreBytes, theme, onRendererGone = { generation++ })
    }
    key(generation) {
        AndroidView(factory = { webView }, modifier = modifier)
    }
    LaunchedEffect(webView, night) {
        webView.setBackgroundColor((if (night) NightColors.paper else DayColors.paper).toArgb())
        webView.evaluateJavascript(theme.script(), null)
    }
    LaunchedEffect(webView) {
        commands.collect { command -> webView.evaluateJavascript(command.toJavaScript(), null) }
    }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
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
        callbacks.onWebError("The score view stopped unexpectedly and was reloaded.")
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
    fun onScoreLoaded(payloadJson: String) = onMain { onScoreRendered(payloadJson) }

    @JavascriptInterface
    fun onMeasureTapped(measureId: String) = onMain { onMeasureTapped(measureId) }

    @JavascriptInterface
    fun onMeasureLongPressed(measureId: String) = onMain { onMeasureLongPressed(measureId) }

    @JavascriptInterface
    fun onZoomChanged(scale: Int) = onMain { onZoomChanged(scale) }

    @JavascriptInterface
    fun onPageChanged(page: Int, pageCount: Int) = onMain { onPageChanged(page, pageCount) }

    @JavascriptInterface
    fun onThumbnail(pngDataUrl: String) = onMain { onThumbnail(pngDataUrl) }

    @JavascriptInterface
    fun onError(message: String) {
        Log.w("ScoreView", "score.js: $message")
        onMain { onWebError(message) }
    }
}
