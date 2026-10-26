package com.grulla.music.score

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.grulla.music.core.score.LoadedScore
import com.grulla.music.core.score.MusicXmlLoader
import com.grulla.music.core.score.ScoreLoadException
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads score files from the Storage Access Framework and from bundled assets. */
class ScoreFiles(private val context: Context) {
    /** MIME types offered in the system file picker. */
    val pickerMimeTypes = arrayOf(
        "application/vnd.recordare.musicxml+xml",
        "application/vnd.recordare.musicxml",
        "application/xml",
        "text/xml",
        "application/zip",
        // Many providers do not know .mxl / .musicxml and report them as generic binary files.
        "application/octet-stream",
    )

    /** Opens a library URI: content://..., or asset:<path> for a bundled sample. Blocking. */
    fun open(uri: String): Pair<LoadedScore, String> =
        if (uri.startsWith(ASSET_SCHEME)) openAsset(uri.removePrefix(ASSET_SCHEME)) else open(uri.toUri())

    /** Blocking; call off the main thread. Returns the score and a fallback title (the file name). */
    fun open(uri: Uri): Pair<LoadedScore, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readCapped() }
            ?: throw ScoreLoadException("The file could not be opened.")
        return MusicXmlLoader.load(bytes) to (displayName(uri) ?: "Score")
    }

    /** Blocking; call off the main thread. */
    fun openAsset(path: String): Pair<LoadedScore, String> {
        val bytes = context.assets.open(path).use { it.readCapped() }
        return MusicXmlLoader.load(bytes) to path.substringAfterLast('/')
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringBeforeLast('.')

    private fun InputStream.readCapped(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) throw ScoreLoadException("The file is too large to open.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val ASSET_SCHEME = "asset:"
        const val SAMPLE_URI = "${ASSET_SCHEME}samples/ode-to-joy.musicxml"
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
