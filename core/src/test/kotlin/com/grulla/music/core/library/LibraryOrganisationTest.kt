package com.grulla.music.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryOrganisationTest {
    private val library = Library()
        .opened("content://a/ode.mxl", "Ode to Joy", "Ludwig van Beethoven", now = 3000)
        .opened("content://a/gnossienne.mxl", "Gnossienne No. 1", "Érik Satie", now = 2000)
        .opened("content://a/air.musicxml", "Air", null, now = 1000)

    @Test
    fun `search ignores case and accents and needs every word`() {
        assertEquals(listOf("Gnossienne No. 1"), library.search("erik GNOSS").map { it.title })
        assertEquals(listOf("Ode to Joy"), library.search(" beethoven  ode ").map { it.title })
        assertEquals(3, library.search("").size)
        assertEquals(0, library.search("bach").size)
    }

    @Test
    fun `sorting by title, composer and recency`() {
        assertEquals(listOf("Air", "Gnossienne No. 1", "Ode to Joy"), library.sorted(LibrarySort.TITLE).map { it.title })
        assertEquals(listOf("Ode to Joy", "Gnossienne No. 1", "Air"), library.sorted(LibrarySort.COMPOSER).map { it.title })
        assertEquals(listOf("Ode to Joy", "Gnossienne No. 1", "Air"), library.recent.map { it.title })
    }

    @Test
    fun `imported scores join without replacing known ones and are not "continue" candidates`() {
        val imported = library.imported(
            listOf(
                ImportedScore("content://a/ode.mxl", "Other title", null),
                ImportedScore("content://tree/b.mxl", "Bourrée", "J. S. Bach"),
                ImportedScore("content://tree/b.mxl", "Bourrée", "J. S. Bach"),
            ),
            now = 5000,
        )
        assertEquals(4, imported.entries.size)
        assertEquals("Ode to Joy", imported[Library.idFor("content://a/ode.mxl")]!!.title)
        assertEquals("Ode to Joy", imported.lastOpened!!.title)
        assertEquals("Bourrée", imported.sorted(LibrarySort.ADDED).first().title)
        assertNull(Library().imported(listOf(ImportedScore("content://x", "X", null)), now = 1).lastOpened)
    }

    @Test
    fun `setlists keep their order and lose removed scores`() {
        val ode = Library.idFor("content://a/ode.mxl")
        val air = Library.idFor("content://a/air.musicxml")
        val gno = Library.idFor("content://a/gnossienne.mxl")
        var lib = library.withSetlist("Recital ", "s1")
            .addToSetlist("s1", air).addToSetlist("s1", ode).addToSetlist("s1", gno).addToSetlist("s1", air)
        val recital = lib.setlist("s1")!!
        assertEquals("Recital", recital.name)
        assertEquals(listOf("Air", "Ode to Joy", "Gnossienne No. 1"), lib.entriesOf(recital).map { it.title })
        lib = lib.moveInSetlist("s1", gno, -1).moveInSetlist("s1", air, -1)
        assertEquals(listOf(air, gno, ode), lib.setlist("s1")!!.entryIds)
        lib = lib.without(gno).removeFromSetlist("s1", air)
        assertEquals(listOf(ode), lib.setlist("s1")!!.entryIds)
        assertEquals(lib, Library.decode(lib.encode()))
        assertEquals(emptyList(), lib.withoutSetlist("s1").setlists)
    }
}
