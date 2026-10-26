package com.grulla.music.core.listen

import com.grulla.music.core.midi.MidiEvent
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.TempoMap
import com.grulla.music.core.timemap.Timemap
import kotlin.math.abs

/** One place in the score where the player has to strike something. */
data class FollowStep(
    val timeMs: Double,
    /** Notes to highlight (those of the followed parts that start here). */
    val noteIds: List<String>,
    /** MIDI pitches the player may strike to move on. */
    val pitches: Set<Int>,
)

/** The steps of a score for "follow me", restricted to the parts the player plays. */
class FollowScore(val steps: List<FollowStep>) {
    /** Index of the first step at or after [timeMs]. */
    fun stepAt(timeMs: Double): Int = steps.indexOfFirst { it.timeMs >= timeMs - 1.0 }.let { if (it < 0) steps.size else it }

    companion object {
        /**
         * Builds the steps from Verovio's timemap and note pitches, using the MIDI to
         * know which notes are really struck (a note tied from the previous one is an
         * onset in the timemap but not in the MIDI) and which track each belongs to.
         *
         * @param tracks MIDI tracks the player plays (null = all).
         */
        fun build(timemap: Timemap, pitches: Map<String, Int>, midi: MidiFile, tracks: Set<Int>? = null): FollowScore {
            val tempo = TempoMap.of(midi)
            // (time, pitch) of every note-on of the followed tracks, excluding percussion.
            val struck = midi.tracks.withIndex()
                .filter { (index, _) -> tracks == null || index in tracks }
                .flatMap { (_, track) -> track.events }
                .filterIsInstance<MidiEvent.Channel>()
                .filter { it.isNoteOn && it.channel != MidiEvent.PERCUSSION_CHANNEL }
                .map { tempo.tickToMs(it.tick) to it.data1 }
                .groupBy({ it.second }, { it.first })
            fun isStruck(pitch: Int, timeMs: Double) = struck[pitch]?.any { abs(it - timeMs) <= TOLERANCE_MS } == true

            val steps = timemap.entries.mapNotNull { entry ->
                val notes = entry.on.mapNotNull { id -> pitches[id]?.let { id to it } }.filter { (_, p) -> isStruck(p, entry.tstamp) }
                if (notes.isEmpty()) null else FollowStep(entry.tstamp, notes.map { it.first }, notes.map { it.second }.toSet())
            }
            return FollowScore(steps)
        }

        private const val TOLERANCE_MS = 2.0
    }
}

/** What happened when a note was heard. */
enum class FollowResult { ADVANCED, SKIPPED_AHEAD, WRONG, IGNORED, FINISHED }

data class FollowState(
    /** Index of the step to play next; == steps.size when finished. */
    val stepIndex: Int,
    val total: Int,
    val expected: Set<Int>,
    val lastHeard: Int? = null,
    val lastResult: FollowResult? = null,
) {
    val finished: Boolean get() = stepIndex >= total
}

/**
 * "Follow me": the score only moves on when the player strikes an expected note.
 * Any note of a chord counts, unless [chordsNeedAllNotes]: then a chord moves on
 * only with a [ChordEvent] (every note heard, see [ChordDetector]). Striking the
 * note after the expected one is taken as a skipped note, so a missed detection
 * does not leave the player stuck.
 */
class FollowController(
    private val score: FollowScore,
    /** Accept the right pitch class in any octave (pitch detectors make octave errors). */
    private val octaveTolerant: Boolean = true,
    /** After a step, other notes heard this soon are taken as the rest of the chord. */
    private val chordWindowMs: Double = 150.0,
    private val chordsNeedAllNotes: Boolean = false,
) {
    private var index = 0
    private var lastAdvanceAt = Double.NEGATIVE_INFINITY
    private var lastHeard: Int? = null
    private var lastResult: FollowResult? = null

    val steps: List<FollowStep> get() = score.steps

    val state: FollowState
        get() = FollowState(index, score.steps.size, score.steps.getOrNull(index)?.pitches.orEmpty(), lastHeard, lastResult)

    val currentStep: FollowStep? get() = score.steps.getOrNull(index)

    /** The chord to check every note of, when the current step is one and chords need all notes. */
    val expectedChord: Set<Int>?
        get() = currentStep?.pitches?.takeIf { chordsNeedAllNotes && it.size >= 2 }

    fun startAt(timeMs: Double) {
        index = score.stepAt(timeMs)
        lastAdvanceAt = Double.NEGATIVE_INFINITY
        lastHeard = null
        lastResult = null
    }

    /** Moves past the current step without hearing it (e.g. a note the microphone cannot pick up). */
    fun skip(): FollowResult {
        if (index >= score.steps.size) return FollowResult.FINISHED
        index++
        lastHeard = null
        lastResult = if (index >= score.steps.size) FollowResult.FINISHED else FollowResult.SKIPPED_AHEAD
        return lastResult!!
    }

    fun onNote(event: NoteEvent): FollowResult {
        lastHeard = event.midi
        val result = when {
            index >= score.steps.size -> FollowResult.FINISHED
            matches(score.steps[index], event.midi) && expectedChord != null -> FollowResult.IGNORED
            matches(score.steps[index], event.midi) -> {
                index++
                lastAdvanceAt = event.timeMs
                if (index >= score.steps.size) FollowResult.FINISHED else FollowResult.ADVANCED
            }
            event.timeMs - lastAdvanceAt < chordWindowMs -> FollowResult.IGNORED
            index + 1 < score.steps.size && matches(score.steps[index + 1], event.midi) -> {
                index += 2
                lastAdvanceAt = event.timeMs
                if (index >= score.steps.size) FollowResult.FINISHED else FollowResult.SKIPPED_AHEAD
            }
            else -> FollowResult.WRONG
        }
        lastResult = result
        return result
    }

    /** Every note of the current chord was heard. */
    fun onChord(event: ChordEvent): FollowResult {
        val chord = expectedChord ?: return FollowResult.IGNORED
        if (event.pitches != chord) return FollowResult.IGNORED
        index++
        lastAdvanceAt = event.timeMs
        lastHeard = null
        lastResult = if (index >= score.steps.size) FollowResult.FINISHED else FollowResult.ADVANCED
        return lastResult!!
    }

    private fun matches(step: FollowStep, midi: Int): Boolean =
        midi in step.pitches || (octaveTolerant && step.pitches.any { Math.floorMod(it - midi, 12) == 0 })
}

/** Note name with octave, e.g. 61 -> "C♯4". */
fun noteName(midi: Int): String {
    val names = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    return names[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)
}
