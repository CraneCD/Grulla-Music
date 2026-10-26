package com.grulla.music.core.listen

import com.grulla.music.core.Fixtures
import com.grulla.music.core.midi.MidiReader
import com.grulla.music.core.timemap.Timemap
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowControllerTest {
    private val timemap = Timemap.parse(Fixtures.text("${Fixtures.CHRONO}.timemap.json"))
    private val midi = MidiReader.read(Fixtures.bytes("${Fixtures.CHRONO}.mid"))
    private val pitches: Map<String, Int> = Json.decodeFromString(Fixtures.text("${Fixtures.CHRONO}.pitches.json"))

    private fun steps(vararg pitches: Set<Int>) =
        FollowScore(pitches.mapIndexed { i, p -> FollowStep(i * 500.0, listOf("n$i"), p) })

    @Test
    fun `advances only on an expected note`() {
        val follow = FollowController(steps(setOf(60), setOf(64), setOf(67)), octaveTolerant = false)
        assertEquals(FollowResult.WRONG, follow.onNote(NoteEvent(62, 0.0)))
        assertEquals(0, follow.state.stepIndex)
        assertEquals(FollowResult.ADVANCED, follow.onNote(NoteEvent(60, 100.0)))
        assertEquals(setOf(64), follow.state.expected)
        assertEquals(FollowResult.ADVANCED, follow.onNote(NoteEvent(64, 400.0)))
        assertEquals(FollowResult.FINISHED, follow.onNote(NoteEvent(67, 800.0)))
        assertTrue(follow.state.finished)
    }

    @Test
    fun `any note of a chord counts and the rest of the chord is ignored`() {
        val follow = FollowController(steps(setOf(60, 64, 67), setOf(65)))
        assertEquals(FollowResult.ADVANCED, follow.onNote(NoteEvent(64, 0.0)))
        assertEquals(FollowResult.IGNORED, follow.onNote(NoteEvent(67, 50.0)))
        assertEquals(1, follow.state.stepIndex)
    }

    @Test
    fun `octave errors are tolerated and a missed note can be skipped`() {
        val follow = FollowController(steps(setOf(60), setOf(62), setOf(64), setOf(65)))
        assertEquals(FollowResult.ADVANCED, follow.onNote(NoteEvent(72, 0.0))) // C5 for C4
        assertEquals(FollowResult.SKIPPED_AHEAD, follow.onNote(NoteEvent(64, 500.0))) // D was not detected
        assertEquals(setOf(65), follow.state.expected)
        assertEquals(FollowResult.FINISHED, follow.onNote(NoteEvent(65, 900.0)))
        assertTrue(follow.state.finished)
    }

    @Test
    fun `start position picks the next step`() {
        val follow = FollowController(steps(setOf(60), setOf(62), setOf(64)))
        follow.startAt(600.0)
        assertEquals(2, follow.state.stepIndex)
        follow.startAt(500.0)
        assertEquals(1, follow.state.stepIndex)
    }

    @Test
    fun `tied notes are not steps and tracks select the part`() {
        val all = FollowScore.build(timemap, pitches, midi)
        val onsets = timemap.entries.count { it.on.isNotEmpty() }
        assertTrue(all.steps.size < onsets, "tie continuations are skipped")
        assertFalse(all.steps.any { it.timeMs == 3429.0 }, "the tied C♯5 at 3429 ms is not re-struck")
        assertTrue(all.steps.all { it.pitches.isNotEmpty() && it.noteIds.isNotEmpty() })

        val upper = FollowScore.build(timemap, pitches, midi, tracks = setOf(1))
        val lower = FollowScore.build(timemap, pitches, midi, tracks = setOf(2))
        assertTrue(upper.steps.isNotEmpty() && lower.steps.isNotEmpty())
        assertTrue(upper.steps.size < all.steps.size && lower.steps.size < all.steps.size)
        // Every step of either hand is a step of the whole piece.
        val allTimes = all.steps.map { it.timeMs }.toSet()
        assertTrue((upper.steps + lower.steps).all { it.timeMs in allTimes })
        // The first step of the piece: a chord in the left hand and a melody note in the right.
        assertEquals(all.steps.first().pitches, upper.steps.first().pitches + lower.steps.first().pitches)
    }
}
