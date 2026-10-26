package com.grulla.music.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.grulla.music.core.library.ImportedScore
import com.grulla.music.core.score.MusicXmlLoader

/**
 * Finds the MusicXML files in a folder chosen with the system picker (and its
 * subfolders), keeping read access to the folder so the scores open later.
 */
class FolderImport(context: Context) {
    private val app = context.applicationContext

    /** Blocking; call off the main thread. [onProgress] gets the number of scores found so far. */
    fun scan(tree: Uri, onProgress: (Int) -> Unit = {}): List<ImportedScore> {
        runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val found = ArrayList<ImportedScore>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        var folders = 0
        while (pending.isNotEmpty() && folders < MAX_FOLDERS && found.size < MAX_SCORES) {
            val parent = pending.removeFirst()
            folders++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            app.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && found.size < MAX_SCORES) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: ""
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (isScoreFile(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        read(uri, name)?.let {
                            found += it
                            onProgress(found.size)
                        }
                    }
                }
            }
        }
        return found
    }

    /** Title and composer from the file; files that are not readable MusicXML are skipped. */
    private fun read(uri: Uri, name: String): ImportedScore? = runCatching {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_FILE_BYTES) return null
        val score = MusicXmlLoader.load(bytes)
        ImportedScore(uri.toString(), score.title ?: name.substringBeforeLast('.'), score.composer)
    }.getOrNull()

    companion object {
        private const val MAX_FOLDERS = 500
        private const val MAX_SCORES = 1000
        private const val MAX_FILE_BYTES = 64 * 1024 * 1024

        fun isScoreFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".mxl") || lower.endsWith(".musicxml") || lower.endsWith(".xml")
        }
    }
}
