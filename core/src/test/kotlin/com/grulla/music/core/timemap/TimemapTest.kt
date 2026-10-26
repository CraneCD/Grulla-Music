package com.grulla.music.core.timemap

import com.grulla.music.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimemapTest {
    private val sample = """
        [
          {"tstamp": 500, "qstamp": 1, "off": ["n1"], "on": ["n2"], "futureKey": true},
          {"tstamp": 0, "qstamp": 0, "on": ["n1", "n3"], "measureOn": "m1", "tempo": 120},
          {"tstamp": 1000, "qstamp": 2, "off": ["n2", "n3"], "on": ["n4"], "measureOn": "m2"},
          {"tstamp": 2000, "qstamp": 3, "off": ["n4"], "tempo": 60, "restsOn": ["r1"]},
          {"tstamp": 3000, "qstamp": 4, "restsOff": ["r1"]}
        ]
    """.trimIndent()

    @Test
    fun `parses entries, sorts them and ignores unknown keys`() {
        val timemap = Timemap.parse(sample)
        assertEquals(listOf(0.0, 500.0, 1000.0, 2000.0, 3000.0), timemap.entries.map { it.tstamp })
        assertEquals(listOf("n1", "n3"), timemap.entries.first().on)
        assertEquals(listOf("r1"), timemap.entries[3].restsOn)
        assertEquals(3000.0, timemap.durationMs)
    }

    @Test
    fun `finds measure start times`() {
        val timemap = Timemap.parse(sample)
        assertEquals(listOf("m1", "m2"), timemap.measures.map { it.id })
        assertEquals(1000.0, timemap.measureStartMs("m2"))
        assertNull(timemap.measureStartMs("nope"))
        assertEquals("m1", timemap.measureAt(-10.0)?.id)
        assertEquals("m1", timemap.measureAt(999.0)?.id)
        assertEquals("m2", timemap.measureAt(1000.0)?.id)
        assertEquals("m2", timemap.measureAt(99_999.0)?.id)
    }

    @Test
    fun `reports the tempo in effect`() {
        val timemap = Timemap.parse(sample)
        assertEquals(120.0, timemap.tempoAt(0.0))
        assertEquals(120.0, timemap.tempoAt(1999.0))
        assertEquals(60.0, timemap.tempoAt(2500.0))
        assertEquals(Timemap.DEFAULT_TEMPO, Timemap.parse("[]").tempoAt(0.0))
    }

    @Test
    fun `parses the Verovio fixture`() {
        val timemap = Timemap.parse(Fixtures.text("${Fixtures.CHRONO}.timemap.json"))
        assertEquals(549, timemap.entries.size)
        assertEquals(53, timemap.measures.size)
        assertEquals(0.0, timemap.measures.first().timeMs)
        assertTrue(timemap.measures.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
        assertEquals(87.5, timemap.tempoAt(0.0), 0.01)
        // Every note that starts also ends.
        val on = timemap.entries.flatMap { it.on }.toSet()
        val off = timemap.entries.flatMap { it.off }.toSet()
        assertEquals(on, off)
    }
}
