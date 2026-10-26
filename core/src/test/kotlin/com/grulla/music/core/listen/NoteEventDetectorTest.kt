package com.grulla.music.core.listen

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteEventDetectorTest {
    private fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12)

    private fun frame(midi: Int?, rms: Double = 0.1, confidence: Double = 0.95, cents: Double = 0.0) =
        PitchEstimate(midi?.let { hz(it + cents / 100) }, confidence, rms)

    private fun NoteEventDetector.feed(frames: List<PitchEstimate>, startMs: Double = 0.0, stepMs: Double = 12.0): List<NoteEvent> =
        frames.mapIndexedNotNull { i, f -> process(f, startMs + i * stepMs) }

    @Test
    fun `a note counts once it is stable`() {
        val detector = NoteEventDetector(stableFrames = 3)
        val events = detector.feed(List(10) { frame(60) })
        assertEquals(listOf(NoteEvent(60, 24.0)), events)
    }

    @Test
    fun `a different note is a new event, the same held note is not`() {
        val detector = NoteEventDetector(stableFrames = 2)
        val events = detector.feed(List(5) { frame(60) } + List(5) { frame(62) } + List(5) { frame(62) })
        assertEquals(listOf(60, 62), events.map { it.midi })
    }

    @Test
    fun `the same note after a silence counts again`() {
        val detector = NoteEventDetector(stableFrames = 2)
        val events = detector.feed(List(4) { frame(64) } + List(3) { frame(null, rms = 0.0005) } + List(4) { frame(64) })
        assertEquals(listOf(64, 64), events.map { it.midi })
    }

    @Test
    fun `a re-attack of a decaying note counts again after the hold-off`() {
        val detector = NoteEventDetector(stableFrames = 2, reattackRatio = 1.8, reattackHoldOffMs = 100.0)
        // Strike at 0.2, decay to 0.04 over ~200 ms, strike again at 0.2.
        val decaying = listOf(0.2, 0.18, 0.15, 0.12, 0.1, 0.08, 0.07, 0.06, 0.05, 0.045, 0.04, 0.04, 0.04, 0.04, 0.04, 0.04, 0.04)
        val restrike = listOf(0.2, 0.2, 0.19, 0.18)
        val events = detector.feed((decaying + restrike).map { frame(67, rms = it) })
        assertEquals(2, events.size)
        assertEquals(67, events[1].midi)
        // Rising level right after the first strike is not a second note.
        val early = NoteEventDetector(stableFrames = 2, reattackHoldOffMs = 100.0)
        assertEquals(1, early.feed(listOf(0.02, 0.05, 0.1, 0.2, 0.2).map { frame(67, rms = it) }).size)
    }

    @Test
    fun `unsure, quiet or out-of-tune frames are not notes`() {
        val detector = NoteEventDetector(stableFrames = 2)
        assertEquals(emptyList(), detector.feed(List(5) { frame(60, confidence = 0.5) }))
        assertEquals(emptyList(), detector.feed(List(5) { frame(60, rms = 0.001) }))
        assertEquals(emptyList(), detector.feed(List(5) { frame(60, cents = 48.0) }))
        assertNull(detector.process(frame(null), 0.0))
    }
}
