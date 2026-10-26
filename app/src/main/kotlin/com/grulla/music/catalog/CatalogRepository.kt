package com.grulla.music.catalog

import android.content.Context
import androidx.core.content.edit
import com.grulla.music.core.catalog.Catalog
import com.grulla.music.core.catalog.CatalogScore
import com.grulla.music.core.score.MusicXmlLoader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The catalogue as the "Find scores" screen sees it. */
sealed interface CatalogState {
    data object Loading : CatalogState

    data class Ready(val catalog: Catalog) : CatalogState

    /** No catalogue yet and it could not be downloaded (no connection, server error). */
    data object Offline : CatalogState
}

/** A score being downloaded, or one that failed. Downloaded scores are in [CatalogRepository.isDownloaded]. */
enum class DownloadState { DOWNLOADING, FAILED }

/**
 * The online score catalogue: the index (catalog.json.gz) and the scores themselves,
 * served as static files from the `catalog` branch of the app's repository (see
 * tools/catalog). The index is cached and refreshed at most once a day; downloaded
 * scores are kept in the app's storage and open like any other score
 * ([DOWNLOAD_SCHEME] URIs).
 */
class CatalogRepository(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "catalog")
    private val cache = File(dir, "catalog.json.gz")
    private val prefs = app.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    private val mutableState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()

    private val mutableDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = mutableDownloads.asStateFlow()

    private var loading = false

    /** Shows the cached catalogue at once, and refreshes it when it is old (or [force]). */
    fun load(force: Boolean = false) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                if (state.value !is CatalogState.Ready) {
                    withContext(Dispatchers.IO) { readCache() }?.let { mutableState.value = CatalogState.Ready(it) }
                }
                val stale = System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) > REFRESH_MS
                if (force || stale || state.value !is CatalogState.Ready) {
                    val fresh = withContext(Dispatchers.IO) { runCatching { fetchIndex() }.getOrNull() }
                    when {
                        fresh != null -> mutableState.value = CatalogState.Ready(fresh)
                        state.value !is CatalogState.Ready -> mutableState.value = CatalogState.Offline
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    fun isDownloaded(id: String): Boolean = fileFor(id).isFile

    /** Downloads [score] (unless it is already here) and reports its library URI, or null on failure. */
    fun download(score: CatalogScore, onDone: (String?) -> Unit) {
        if (isDownloaded(score.id)) {
            onDone(uriFor(score.id))
            return
        }
        if (downloads.value[score.id] == DownloadState.DOWNLOADING) return
        mutableDownloads.update { it + (score.id to DownloadState.DOWNLOADING) }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = get(BASE_URL + score.file)
                    MusicXmlLoader.load(bytes) // Only keep files that open.
                    val target = fileFor(score.id)
                    target.parentFile?.mkdirs()
                    val partial = File(target.path + ".part")
                    partial.writeBytes(bytes)
                    partial.renameTo(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
            mutableDownloads.update { if (ok) it - score.id else it + (score.id to DownloadState.FAILED) }
            onDone(if (ok) uriFor(score.id) else null)
        }
    }

    private fun readCache(): Catalog? =
        if (cache.isFile) runCatching { decode(cache.readBytes()) }.getOrNull() else null

    /** Downloads the index if it changed (ETag); returns the new catalogue, or the cached one if unchanged. */
    private fun fetchIndex(): Catalog {
        val connection = open(BASE_URL + INDEX)
        prefs.getString(KEY_ETAG, null)?.takeIf { cache.isFile }?.let { connection.setRequestProperty("If-None-Match", it) }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit { putLong(KEY_CHECKED, System.currentTimeMillis()) }
                return readCache() ?: throw IOException("cache unreadable")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readBytes() }
            val catalog = decode(bytes)
            dir.mkdirs()
            val partial = File(cache.path + ".part")
            partial.writeBytes(bytes)
            partial.renameTo(cache)
            prefs.edit {
                putString(KEY_ETAG, connection.getHeaderField("ETag"))
                putLong(KEY_CHECKED, System.currentTimeMillis())
            }
            return catalog
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): ByteArray {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "GrullaMusic")
    }

    private fun decode(gzipped: ByteArray): Catalog =
        Catalog.decode(GZIPInputStream(gzipped.inputStream()).bufferedReader().use { it.readText() })

    private fun fileFor(id: String) = downloadedFile(app, id)

    companion object {
        /** The `catalog` branch of the app's repository (see tools/catalog/README.md). */
        const val BASE_URL = "https://raw.githubusercontent.com/CraneCD/Grulla-Music/catalog/"
        private const val INDEX = "catalog.json.gz"
        private const val KEY_ETAG = "etag"
        private const val KEY_CHECKED = "checked"
        private const val REFRESH_MS = 24L * 3600 * 1000

        /** Library URIs of downloaded scores: download:<catalogue id>. */
        const val DOWNLOAD_SCHEME = "download:"

        fun uriFor(id: String) = DOWNLOAD_SCHEME + id

        /** Where a downloaded score is stored. Ids are checked so a URI cannot point outside the folder. */
        fun downloadedFile(context: Context, id: String): File {
            require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Bad catalogue id" }
            return File(File(context.applicationContext.filesDir, "downloads"), "$id.mxl")
        }
    }
}
