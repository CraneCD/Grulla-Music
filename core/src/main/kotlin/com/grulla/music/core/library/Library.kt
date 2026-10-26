package com.grulla.music.core.library

import com.grulla.music.core.practice.SpeedTrainerSettings
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
    val trainer: SpeedTrainerSettings = SpeedTrainerSettings(),
    val trainerOn: Boolean = false,
    /** MIDI tracks of the part(s) the user plays, for Follow me / Wait for me (empty: not chosen yet). */
    val myTracks: Set<Int> = emptySet(),
    /** Semitones up (positive) or down; applied to notation and sound. */
    val transpose: Int = 0,
    /** Show transposing instruments at sounding pitch. */
    val concertPitch: Boolean = false,
    /** Percent per MIDI track (100 = as written), and chosen General MIDI programs. */
    val trackVolumes: Map<Int, Int> = emptyMap(),
    val trackPrograms: Map<Int, Int> = emptyMap(),
)

@Serializable
data class LibraryEntry(
    val id: String,
    /** content:// URI of the file, or asset:<path> for bundled samples. */
    val uri: String,
    val title: String,
    val composer: String? = null,
    /** Epoch milliseconds; 0 for a score imported but not opened yet. */
    val lastOpened: Long,
    /** When the score joined the library (epoch milliseconds). */
    val addedAt: Long = lastOpened,
    /** File name of the page-1 thumbnail in the app's thumbnail folder. */
    val thumbnail: String? = null,
    val settings: ScoreSettings = ScoreSettings(),
)

/** A named, ordered list of scores, e.g. for a concert or a lesson. */
@Serializable
data class Setlist(val id: String, val name: String, val entryIds: List<String> = emptyList())

enum class LibrarySort { RECENT, TITLE, COMPOSER, ADDED }

/** The user's scores with their per-score settings, and setlists. Immutable. */
@Serializable
data class Library(val entries: List<LibraryEntry> = emptyList(), val setlists: List<Setlist> = emptyList()) {
    /** Most recently opened first (never opened ones last). */
    val recent: List<LibraryEntry> get() = sorted(LibrarySort.RECENT)

    /** The score to continue: the one opened last. */
    val lastOpened: LibraryEntry? get() = entries.filter { it.lastOpened > 0 }.maxByOrNull { it.lastOpened }

    fun sorted(sort: LibrarySort, from: List<LibraryEntry> = entries): List<LibraryEntry> = when (sort) {
        LibrarySort.RECENT -> from.sortedWith(compareByDescending<LibraryEntry> { it.lastOpened }.thenByDescending { it.addedAt })
        LibrarySort.TITLE -> from.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        LibrarySort.COMPOSER -> from.sortedWith(
            compareBy<LibraryEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.composer ?: "\uFFFF" }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        LibrarySort.ADDED -> from.sortedByDescending { it.addedAt }
    }

    /** Entries whose title or composer contain every word of [query] (case and accents ignored). */
    fun search(query: String, from: List<LibraryEntry> = entries): List<LibraryEntry> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return from
        return from.filter { entry ->
            val text = normalize(entry.title + " " + (entry.composer ?: ""))
            words.all { it in text }
        }
    }

    /** The scores of a setlist, in its order. */
    fun entriesOf(setlist: Setlist): List<LibraryEntry> = setlist.entryIds.mapNotNull { get(it) }

    fun setlist(id: String): Setlist? = setlists.firstOrNull { it.id == id }

    fun withSetlist(name: String, id: String): Library = copy(setlists = setlists + Setlist(id, name.trim()))

    fun renameSetlist(id: String, name: String): Library = updateSetlist(id) { it.copy(name = name.trim()) }

    fun withoutSetlist(id: String): Library = copy(setlists = setlists.filter { it.id != id })

    fun addToSetlist(setlistId: String, entryId: String): Library =
        updateSetlist(setlistId) { if (entryId in it.entryIds) it else it.copy(entryIds = it.entryIds + entryId) }

    fun removeFromSetlist(setlistId: String, entryId: String): Library =
        updateSetlist(setlistId) { it.copy(entryIds = it.entryIds - entryId) }

    /** Moves an entry one place up (-1) or down (+1) in a setlist. */
    fun moveInSetlist(setlistId: String, entryId: String, by: Int): Library = updateSetlist(setlistId) { list ->
        val from = list.entryIds.indexOf(entryId)
        val to = (from + by).coerceIn(0, list.entryIds.size - 1)
        if (from < 0 || from == to) list else list.copy(entryIds = list.entryIds.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun updateSetlist(id: String, change: (Setlist) -> Setlist) =
        copy(setlists = setlists.map { if (it.id == id) change(it) else it })

    /** Adds scores found in a folder; known ones are left as they are. */
    fun imported(found: List<ImportedScore>, now: Long): Library {
        val known = entries.map { it.id }.toSet()
        val added = found.distinctBy { idFor(it.uri) }.filter { idFor(it.uri) !in known }.map {
            LibraryEntry(id = idFor(it.uri), uri = it.uri, title = it.title, composer = it.composer, lastOpened = 0, addedAt = now)
        }
        return copy(entries = (entries + added).take(MAX_ENTRIES))
    }

    operator fun get(id: String): LibraryEntry? = entries.firstOrNull { it.id == id }

    /** Records that [uri] was opened now; keeps the settings and thumbnail of a known score. */
    fun opened(uri: String, title: String, composer: String?, now: Long): Library {
        val id = idFor(uri)
        val existing = get(id)
        val entry = existing?.copy(title = title, composer = composer, lastOpened = now)
            ?: LibraryEntry(id = id, uri = uri, title = title, composer = composer, lastOpened = now, addedAt = now)
        val others = entries.filter { it.id != id }.sortedByDescending { it.lastOpened }.take(MAX_ENTRIES - 1)
        return copy(entries = listOf(entry) + others)
    }

    fun withSettings(id: String, settings: ScoreSettings): Library = update(id) { it.copy(settings = settings) }

    fun withThumbnail(id: String, thumbnail: String?): Library = update(id) { it.copy(thumbnail = thumbnail) }

    fun without(id: String): Library = Library(
        entries.filter { it.id != id },
        setlists.map { it.copy(entryIds = it.entryIds - id) },
    )

    private fun update(id: String, change: (LibraryEntry) -> LibraryEntry) =
        copy(entries = entries.map { if (it.id == id) change(it) else it })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_ENTRIES = 2000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            coerceInputValues = true
        }

        /** Stable id for a file: the same URI always maps to the same entry. */
        fun idFor(uri: String): String =
            MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

        private fun normalize(text: String): String =
            java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        /** Reads a stored library; a damaged file yields an empty library rather than a crash. */
        fun decode(text: String): Library = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(Library())
    }
}

/** A score found when importing a folder. */
data class ImportedScore(val uri: String, val title: String, val composer: String?)
