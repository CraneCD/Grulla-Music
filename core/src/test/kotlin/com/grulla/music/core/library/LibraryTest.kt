package com.grulla.music.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    @Test
    fun `opening scores keeps the most recent first`() {
        val library = Library()
            .opened("content://a", "A", null, now = 1)
            .opened("content://b", "B", "Bach", now = 2)
            .opened("content://a", "A (renamed)", null, now = 3)
        assertEquals(listOf("A (renamed)", "B"), library.recent.map { it.title })
        assertEquals(2, library.entries.size)
        assertEquals("Bach", library.recent[1].composer)
    }

    @Test
    fun `reopening keeps settings and thumbnail`() {
        val id = Library.idFor("content://a")
        val library = Library()
            .opened("content://a", "A", null, now = 1)
            .withSettings(id, ScoreSettings(tempoPercent = 70, loopStartMs = 1000.0, loopEndMs = 3000.0, mutedTracks = setOf(2)))
            .withThumbnail(id, "$id.png")
            .opened("content://a", "A", null, now = 5)
        val entry = library[id]!!
        assertEquals(70, entry.settings.tempoPercent)
        assertEquals(setOf(2), entry.settings.mutedTracks)
        assertEquals("$id.png", entry.thumbnail)
        assertEquals(5, entry.lastOpened)
    }

    @Test
    fun `ids are stable and distinct`() {
        assertEquals(Library.idFor("content://x"), Library.idFor("content://x"))
        assertTrue(Library.idFor("content://x") != Library.idFor("content://y"))
        assertEquals(20, Library.idFor("content://x").length)
    }

    @Test
    fun `round trips through JSON and tolerates damage`() {
        val id = Library.idFor("asset:samples/ode.musicxml")
        val library = Library()
            .opened("asset:samples/ode.musicxml", "Ode", "Beethoven", now = 42)
            .withSettings(id, ScoreSettings(scale = 55, pageLayout = true, metronome = true, soloedTracks = setOf(1, 3), lastPositionMs = 1234.5))
        assertEquals(library, Library.decode(library.encode()))
        assertEquals(Library(), Library.decode("{not json"))
        assertEquals(Library(), Library.decode(""))
        // Unknown keys from a newer version are ignored.
        val newer = library.encode().replace("\"title\"", "\"futureField\":1,\"title\"")
        assertEquals(library, Library.decode(newer))
    }

    @Test
    fun `removing and the size cap`() {
        var library = Library()
        for (i in 0 until Library.MAX_ENTRIES + 10) library = library.opened("content://$i", "S$i", null, now = i.toLong())
        assertEquals(Library.MAX_ENTRIES, library.entries.size)
        assertNull(library[Library.idFor("content://0")], "the oldest entries are dropped")
        val newest = library.recent.first()
        assertEquals(Library.MAX_ENTRIES - 1, library.without(newest.id).entries.size)
    }
}
