package com.grulla.music.core.playback

import com.grulla.music.core.Fixtures
import com.grulla.music.core.timemap.Timemap
import com.grulla.music.core.timemap.TimemapEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveNoteIndexTest {
    // Bass note "b" held for two beats under a melody "m1", "m2".
    private val timemap = Timemap(
        listOf(
            TimemapEntry(tstamp = 100.0, on = listOf("b", "m1"), measureOn = "bar1"),
            TimemapEntry(tstamp = 600.0, off = listOf("m1"), on = listOf("m2")),
            TimemapEntry(tstamp = 1100.0, off = listOf("b", "m2")),
            TimemapEntry(tstamp = 1100.0, on = listOf("c"), measureOn = "bar2"),
            TimemapEntry(tstamp = 1600.0, off = listOf("c")),
        ),
    )
    private val index = ActiveNoteIndex(timemap)

    @Test
    fun `nothing sounds before the first note`() {
        assertNull(index.segmentAt(0.0))
        assertEquals(emptyList(), index.activeAt(99.9))
    }

    @Test
    fun `finds the notes sounding at a time`() {
        assertEquals(listOf("b", "m1"), index.activeAt(100.0))
        assertEquals(listOf("b", "m1"), index.activeAt(599.0))
        assertEquals(listOf("b", "m2"), index.activeAt(600.0))
        assertEquals(listOf("c"), index.activeAt(1100.0))
        assertEquals(emptyList(), index.activeAt(5000.0))
    }

    @Test
    fun `merges entries that share a timestamp and tracks the measure`() {
        assertEquals(listOf(100.0, 600.0, 1100.0, 1600.0), index.segments.map { it.startMs })
        assertEquals(listOf(0, 1, 2, 3), index.segments.map { it.index })
        assertEquals("bar1", index.segmentAt(700.0)?.measureId)
        assertEquals("bar2", index.segmentAt(1200.0)?.measureId)
    }

    @Test
    fun `tracker reports only changes`() {
        val tracker = HighlightTracker(index)
        assertEquals(HighlightTracker.Change(null), tracker.update(0.0))
        assertNull(tracker.update(50.0))
        assertEquals(listOf("b", "m1"), tracker.update(100.0)?.segment?.noteIds)
        assertNull(tracker.update(300.0))
        assertNull(tracker.update(599.0))
        assertEquals(listOf("b", "m2"), tracker.update(650.0)?.segment?.noteIds)
        // Jumping backwards (a seek) is a change too.
        assertEquals(listOf("b", "m1"), tracker.update(120.0)?.segment?.noteIds)
        tracker.reset()
        assertNotNull(tracker.update(120.0))
    }

    @Test
    fun `every note in the fixture is active at its onset`() {
        val fixture = Timemap.parse(Fixtures.text("${Fixtures.CHRONO}.timemap.json"))
        val fixtureIndex = ActiveNoteIndex(fixture)
        for (entry in fixture.entries) {
            val active = fixtureIndex.activeAt(entry.tstamp)
            assertTrue(active.containsAll(entry.on), "at ${entry.tstamp}: $active vs ${entry.on}")
            assertTrue(active.none { it in entry.off && it !in entry.on })
        }
        assertEquals(emptyList(), fixtureIndex.activeAt(fixture.durationMs))
    }
}
