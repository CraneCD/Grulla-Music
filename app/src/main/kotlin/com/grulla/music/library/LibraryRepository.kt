package com.grulla.music.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.grulla.music.core.library.ImportedScore
import com.grulla.music.core.library.Library
import com.grulla.music.core.library.LibraryEntry
import com.grulla.music.core.library.ScoreSettings
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The library of recently opened scores, stored as JSON in the app's files,
 * with page-1 thumbnails next to it. Writes go through [scope] so they finish
 * even when the screen that asked for them goes away.
 */
class LibraryRepository(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "library.json")
    private val thumbnailDir = File(app.filesDir, "thumbnails")
    private val annotationDir = File(app.filesDir, "annotations")
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    private val mutableLibrary = MutableStateFlow(Library())
    val library: StateFlow<Library> = mutableLibrary.asStateFlow()

    init {
        scope.launch {
            mutableLibrary.value = withContext(Dispatchers.IO) {
                if (file.isFile) Library.decode(file.readText()) else Library()
            }
            loaded.complete(Unit)
        }
    }

    /** Records an opened score and returns its entry (with any saved settings). */
    suspend fun opened(uri: String, title: String, composer: String?): LibraryEntry =
        update { it.opened(uri, title, composer, System.currentTimeMillis()) }[Library.idFor(uri)]!!

    fun saveSettings(id: String, settings: ScoreSettings) {
        scope.launch { update { it.withSettings(id, settings) } }
    }

    fun saveThumbnail(id: String, png: ByteArray) {
        scope.launch {
            val name = "$id.png"
            withContext(Dispatchers.IO) {
                thumbnailDir.mkdirs()
                File(thumbnailDir, name).writeBytes(png)
            }
            update { it.withThumbnail(id, name) }
        }
    }

    fun thumbnailFile(entry: LibraryEntry): File? = entry.thumbnail?.let { File(thumbnailDir, it) }?.takeIf { it.isFile }

    /** The score's annotations as saved by the score view, or null. Blocking; call off the main thread. */
    fun loadAnnotations(id: String): String? = File(annotationDir, "$id.json").takeIf { it.isFile }?.readText()

    fun saveAnnotations(id: String, json: String) {
        scope.launch(Dispatchers.IO) {
            annotationDir.mkdirs()
            val partial = File(annotationDir, "$id.json.tmp")
            partial.writeText(json)
            partial.renameTo(File(annotationDir, "$id.json"))
        }
    }

    private val mutableImporting = MutableStateFlow<Int?>(null)

    /** While a folder is being imported: the number of scores found so far; otherwise null. */
    val importing: StateFlow<Int?> = mutableImporting.asStateFlow()

    /**
     * Adds every MusicXML file in the folder [tree] (and its subfolders) in the
     * background, then reports how many were found.
     */
    fun importFolder(tree: Uri, onDone: (Int) -> Unit) {
        if (mutableImporting.value != null) return
        mutableImporting.value = 0
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { FolderImport(app).scan(tree) { mutableImporting.value = it } }
                update { it.imported(found, System.currentTimeMillis()) }
                onDone(found.size)
            } finally {
                mutableImporting.value = null
            }
        }
    }

    // --- Setlists ---------------------------------------------------------------------

    fun createSetlist(name: String, firstEntry: String? = null): String {
        val id = "s" + System.currentTimeMillis().toString(36)
        scope.launch { update { lib -> lib.withSetlist(name, id).let { if (firstEntry != null) it.addToSetlist(id, firstEntry) else it } } }
        return id
    }

    fun renameSetlist(id: String, name: String) {
        scope.launch { update { it.renameSetlist(id, name) } }
    }

    fun deleteSetlist(id: String) {
        scope.launch { update { it.withoutSetlist(id) } }
    }

    fun addToSetlist(setlistId: String, entryId: String) {
        scope.launch { update { it.addToSetlist(setlistId, entryId) } }
    }

    fun removeFromSetlist(setlistId: String, entryId: String) {
        scope.launch { update { it.removeFromSetlist(setlistId, entryId) } }
    }

    fun moveInSetlist(setlistId: String, entryId: String, by: Int) {
        scope.launch { update { it.moveInSetlist(setlistId, entryId, by) } }
    }

    /** Forgets a score (the file itself is untouched) and gives back the access permission. */
    fun remove(entry: LibraryEntry) {
        scope.launch {
            update { it.without(entry.id) }
            withContext(Dispatchers.IO) {
                thumbnailFile(entry)?.delete()
                File(annotationDir, "${entry.id}.json").delete()
            }
            if (entry.uri.startsWith("content:")) {
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(entry.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    private suspend fun update(change: (Library) -> Library): Library {
        loaded.await()
        return mutex.withLock {
            val next = change(mutableLibrary.value)
            mutableLibrary.value = next
            withContext(Dispatchers.IO) {
                val partial = File(file.path + ".tmp")
                partial.writeText(next.encode())
                partial.renameTo(file)
            }
            next
        }
    }
}
