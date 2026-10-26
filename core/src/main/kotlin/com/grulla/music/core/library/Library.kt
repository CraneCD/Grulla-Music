package com.grulla.music.core.library

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What is remembered per score. Null zoom/layout means "use the app default". */
@Serializable
data class ScoreSettings(
    val scale: Int? = null,
    val pageLayout: Boolean? = null,
    val tempoPercent: Int = 100,
    val countIn: Boolean = false,
    val metronome: Boolean = false,
    val follow: Boolean = true,
    /** A–B loop in score time (ms at the notated tempo). */
    val loopStartMs: Double? = null,
    val loopEndMs: Double? = null,
    val loopEnabled: Boolean = false,
    val mutedTracks: Set<Int> = emptySet(),
    val soloedTracks: Set<Int> = emptySet(),
    val lastPositionMs: Double = 0.0,
)

@Serializable
data class LibraryEntry(
    val id: String,
    /** content:// URI of the file, or asset:<path> for bundled samples. */
    val uri: String,
    val title: String,
    val composer: String? = null,
    /** Epoch milliseconds. */
    val lastOpened: Long,
    /** File name of the page-1 thumbnail in the app's thumbnail folder. */
    val thumbnail: String? = null,
    val settings: ScoreSettings = ScoreSettings(),
)

/** The user's scores: recently opened files with their per-score settings. Immutable. */
@Serializable
data class Library(val entries: List<LibraryEntry> = emptyList()) {
    /** Most recently opened first. */
    val recent: List<LibraryEntry> get() = entries.sortedByDescending { it.lastOpened }

    operator fun get(id: String): LibraryEntry? = entries.firstOrNull { it.id == id }

    /** Records that [uri] was opened now; keeps the settings and thumbnail of a known score. */
    fun opened(uri: String, title: String, composer: String?, now: Long): Library {
        val id = idFor(uri)
        val existing = get(id)
        val entry = existing?.copy(title = title, composer = composer, lastOpened = now)
            ?: LibraryEntry(id = id, uri = uri, title = title, composer = composer, lastOpened = now)
        val others = entries.filter { it.id != id }.sortedByDescending { it.lastOpened }.take(MAX_ENTRIES - 1)
        return Library(listOf(entry) + others)
    }

    fun withSettings(id: String, settings: ScoreSettings): Library = update(id) { it.copy(settings = settings) }

    fun withThumbnail(id: String, thumbnail: String?): Library = update(id) { it.copy(thumbnail = thumbnail) }

    fun without(id: String): Library = Library(entries.filter { it.id != id })

    private fun update(id: String, change: (LibraryEntry) -> LibraryEntry) =
        Library(entries.map { if (it.id == id) change(it) else it })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_ENTRIES = 200

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            coerceInputValues = true
        }

        /** Stable id for a file: the same URI always maps to the same entry. */
        fun idFor(uri: String): String =
            MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

        /** Reads a stored library; a damaged file yields an empty library rather than a crash. */
        fun decode(text: String): Library = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(Library())
    }
}
