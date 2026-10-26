package com.grulla.music.core.midi

import com.grulla.music.core.playback.PlaybackWindow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Count-in settings: [bars] bars of clicks in the time signature at the start point. */
data class CountIn(
    val bars: Int = 1,
    val accentNote: Int = Clicks.HI_WOOD_BLOCK,
    val beatNote: Int = Clicks.LOW_WOOD_BLOCK,
    val accentVelocity: Int = 110,
    val beatVelocity: Int = 85,
) {
    init {
        require(bars >= 1) { "A count-in needs at least one bar" }
    }
}

/** Metronome clicks on every beat of the score, accenting the first beat of each bar. */
data class Metronome(
    val accentNote: Int = Clicks.HI_WOOD_BLOCK,
    val beatNote: Int = Clicks.LOW_WOOD_BLOCK,
    val accentVelocity: Int = 90,
    val beatVelocity: Int = 60,
)

object Clicks {
    const val HI_WOOD_BLOCK = 76
    const val LOW_WOOD_BLOCK = 77
}

/** What to play and how. Times are score times in ms at the notated tempo. */
data class PlaybackOptions(
    val startMs: Double = 0.0,
    /** Stop at this score time (end of a loop or range); null plays to the end. */
    val endMs: Double? = null,
    /** 1.0 = notated tempo. */
    val tempoFactor: Double = 1.0,
    val countIn: CountIn? = null,
    val metronome: Metronome? = null,
    /** Bar starts in quarter notes (Verovio timemap `qstamp`); used to place metronome downbeats. */
    val measureStartsQuarters: List<Double> = emptyList(),
    /** MIDI tracks whose notes are left out (mute / solo). */
    val silencedTracks: Set<Int> = emptySet(),
    /** Volume per MIDI track in percent of the written dynamics (100 = as written). */
    val trackVolumes: Map<Int, Int> = emptyMap(),
    /** General MIDI program (0-127) per MIDI track, replacing the score's instrument. */
    val trackPrograms: Map<Int, Int> = emptyMap(),
) {
    init {
        require(tempoFactor > 0.0) { "tempoFactor must be positive" }
    }
}

/** A MIDI file ready for the player plus the mapping from player time back to score time. */
class PreparedMidi(
    val bytes: ByteArray,
    val window: PlaybackWindow,
    /** Number of count-in clicks before the music starts (0 without count-in). */
    val countInBeats: Int,
    /** Player time (ms) where the requested range ends: the end of the loop or of the file. */
    val endPlayerMs: Double,
)

/**
 * Builds the MIDI that is actually played, from the score's MIDI as rendered by
 * Verovio:
 *  - plays the range [PlaybackOptions.startMs, PlaybackOptions.endMs), keeping
 *    program/controller/tempo state at the start point;
 *  - scales every tempo by the tempo factor;
 *  - drops the notes of silenced tracks;
 *  - adds a metronome track and/or prepends a count-in on the GM percussion channel.
 *
 * The result always plays from position 0, so a single player clock maps linearly
 * to score time through [PreparedMidi.window].
 */
object PlaybackMidiBuilder {
    fun build(source: MidiFile, startMs: Double, tempoFactor: Double, countIn: CountIn?): PreparedMidi =
        build(source, PlaybackOptions(startMs = startMs, tempoFactor = tempoFactor, countIn = countIn))

    fun build(source: MidiFile, options: PlaybackOptions): PreparedMidi {
        val factor = options.tempoFactor
        val sourceTempo = TempoMap.of(source)
        val lastSourceTick = source.allEvents().maxOfOrNull { it.tick } ?: 0L
        val startTick = sourceTempo.msToTick(options.startMs.coerceAtLeast(0.0)).roundToLong().coerceIn(0L, lastSourceTick)
        val endTick = options.endMs
            ?.let { sourceTempo.msToTick(it).roundToLong() }
            ?.takeIf { it > startTick && it < lastSourceTick }
        val exactStartMs = sourceTempo.tickToMs(startTick)

        val grid = BeatGrid.at(source, startTick)
        val countInBeats = options.countIn?.let { it.bars * grid.beatsPerBar } ?: 0
        val leadTicks = countInBeats.toLong() * grid.beatTicks

        val sourceTracks = source.tracks.toMutableList()
        if (options.metronome != null) {
            sourceTracks += metronomeTrack(source, options.metronome, options.measureStartsQuarters, endTick ?: lastSourceTick)
        }
        val tracks = sourceTracks.mapIndexed { index, track ->
            val silenced = index in options.silencedTracks || options.trackVolumes[index] == 0
            val shifted = shiftTrack(track, startTick, endTick, leadTicks, factor, silenced)
                .let { events -> options.trackVolumes[index]?.let { scaleVelocities(events, it) } ?: events }
                .let { events -> options.trackPrograms[index]?.let { replaceProgram(events, it) } ?: events }
            if (index == 0) {
                // The tempo in effect at the start point governs the count-in and the first notes.
                val tempo = MidiEvent.tempo(0, scaleTempo(sourceTempo.microsAt(startTick), factor))
                MidiTrack(listOf(tempo) + shifted)
            } else {
                MidiTrack(shifted)
            }
        }.toMutableList()

        if (options.countIn != null) {
            tracks += countInTrack(options.countIn, countInBeats, grid)
        }
        if (options.silencedTracks.isNotEmpty()) {
            // Keep the full length even when every note is silenced (scrolling without sound).
            val end = (endTick ?: lastSourceTick) - startTick + leadTicks
            tracks[0] = MidiTrack(tracks[0].events + MidiEvent.Meta(end, MidiEvent.META_MARKER, "end".toByteArray(Charsets.US_ASCII)))
        }

        val output = MidiFile(format = if (tracks.size > 1) 1 else source.format, division = source.division, tracks = tracks)
        val outputTempo = TempoMap.of(output)
        val window = PlaybackWindow(scoreStartMs = exactStartMs, tempoFactor = factor, leadInMs = outputTempo.tickToMs(leadTicks))
        val endPlayerMs = if (endTick != null) {
            window.playerTimeFor(sourceTempo.tickToMs(endTick))
        } else {
            outputTempo.tickToMs(output.allEvents().maxOfOrNull { it.tick } ?: 0L)
        }
        return PreparedMidi(MidiWriter.write(output), window, countInBeats, endPlayerMs)
    }

    private fun shiftTrack(
        track: MidiTrack,
        startTick: Long,
        endTick: Long?,
        leadTicks: Long,
        tempoFactor: Double,
        silenced: Boolean,
    ): List<MidiEvent> {
        val result = ArrayList<MidiEvent>(track.events.size)
        for (event in track.events) {
            if (silenced && isNote(event)) continue
            val scaled = if (event is MidiEvent.Meta && event.tempoMicros != null) {
                MidiEvent.tempo(event.tick, scaleTempo(event.tempoMicros!!, tempoFactor))
            } else {
                event
            }
            when {
                endTick != null && event.tick >= endTick -> {
                    // Past the end of the range: only release notes that are still sounding.
                    if (event is MidiEvent.Channel && event.isNoteOff) result += event.atTick(endTick - startTick + leadTicks)
                }
                event.tick >= startTick -> result += scaled.atTick(event.tick - startTick + leadTicks)
                // State before the start point (programs, controllers, time signature...) is replayed
                // at tick 0. Earlier tempo changes are replaced by the single tempo event that build()
                // puts at tick 0.
                !isNote(event) && !isTempo(event) -> result += event.atTick(0)
            }
        }
        return result
    }

    /** Scales note-on velocities by [percent] (a note-on keeps at least velocity 1, else it would be a note-off). */
    private fun scaleVelocities(events: List<MidiEvent>, percent: Int): List<MidiEvent> {
        if (percent == 100) return events
        return events.map { event ->
            if (event is MidiEvent.Channel && event.isNoteOn) {
                MidiEvent.Channel(event.tick, event.status, event.data1, (event.data2 * percent / 100.0).roundToInt().coerceIn(1, 127))
            } else {
                event
            }
        }
    }

    /** Drops the track's program changes and sets [program] at the start on every (melodic) channel it uses. */
    private fun replaceProgram(events: List<MidiEvent>, program: Int): List<MidiEvent> {
        val channels = events.filterIsInstance<MidiEvent.Channel>()
            .map { it.channel }
            .filter { it != MidiEvent.PERCUSSION_CHANNEL }
            .toSortedSet()
        val changes = channels.map { MidiEvent.Channel(0, MidiEvent.PROGRAM_CHANGE or it, program.coerceIn(0, 127), 0) }
        return changes + events.filterNot { it is MidiEvent.Channel && it.command == MidiEvent.PROGRAM_CHANGE && it.channel in channels }
    }

    private fun isNote(event: MidiEvent) = event is MidiEvent.Channel && (event.isNoteOn || event.isNoteOff)

    private fun isTempo(event: MidiEvent) = event is MidiEvent.Meta && event.tempoMicros != null

    private fun countInTrack(countIn: CountIn, beats: Int, grid: BeatGrid): MidiTrack {
        val events = ArrayList<MidiEvent>(beats * 2)
        for (beat in 0 until beats) {
            val accent = beat % grid.beatsPerBar == 0
            events.addClick(
                tick = beat.toLong() * grid.beatTicks,
                length = grid.beatTicks / 2,
                note = if (accent) countIn.accentNote else countIn.beatNote,
                velocity = if (accent) countIn.accentVelocity else countIn.beatVelocity,
            )
        }
        return MidiTrack(events)
    }

    /**
     * One click per beat of every bar. Bars start at [measureStartsQuarters] (so
     * pickups, irregular bars and expanded repeats are followed); without them,
     * bars are derived from the time signatures.
     */
    internal fun metronomeTrack(source: MidiFile, metronome: Metronome, measureStartsQuarters: List<Double>, lastTick: Long): MidiTrack {
        val barStarts = measureStartsQuarters
            .map { (it * source.division).roundToLong() }
            .filter { it in 0 until lastTick }
            .distinct()
            .sorted()
            .ifEmpty { barStartsFromTimeSignatures(source, lastTick) }
        val events = ArrayList<MidiEvent>()
        for ((i, barStart) in barStarts.withIndex()) {
            val grid = BeatGrid.at(source, barStart)
            val fullBar = grid.beatTicks * grid.beatsPerBar
            val barEnd = minOf(barStarts.getOrNull(i + 1) ?: (barStart + fullBar), lastTick)
            val length = barEnd - barStart
            // A short first bar is a pickup: its beats are the last beats of a full bar.
            val offset = if (i == 0 && length < fullBar) fullBar - length else 0L
            var beat = ((offset + grid.beatTicks - 1) / grid.beatTicks).toInt()
            while (true) {
                val tick = barStart + beat * grid.beatTicks - offset
                if (tick >= barEnd) break
                val accent = beat == 0
                events.addClick(
                    tick = tick,
                    length = grid.beatTicks / 2,
                    note = if (accent) metronome.accentNote else metronome.beatNote,
                    velocity = if (accent) metronome.accentVelocity else metronome.beatVelocity,
                )
                beat++
            }
        }
        return MidiTrack(events.sortedBy { it.tick })
    }

    private fun barStartsFromTimeSignatures(source: MidiFile, lastTick: Long): List<Long> {
        val starts = ArrayList<Long>()
        var tick = 0L
        while (tick < lastTick) {
            starts += tick
            val grid = BeatGrid.at(source, tick)
            tick += (grid.beatTicks * grid.beatsPerBar).coerceAtLeast(1)
        }
        return starts
    }

    private fun MutableList<MidiEvent>.addClick(tick: Long, length: Long, note: Int, velocity: Int) {
        val ch = MidiEvent.PERCUSSION_CHANNEL
        add(MidiEvent.Channel(tick, MidiEvent.NOTE_ON or ch, note, velocity))
        add(MidiEvent.Channel(tick + length.coerceAtLeast(1), MidiEvent.NOTE_OFF or ch, note, 0))
    }

    /** Time signature in effect at [tick]; 4/4 when the file has none. */
    fun timeSignatureAt(file: MidiFile, tick: Long): Pair<Int, Int> =
        file.allEvents()
            .filterIsInstance<MidiEvent.Meta>()
            .filter { it.tick <= tick && it.timeSignature != null }
            .maxByOrNull { it.tick }
            ?.timeSignature
            ?.takeIf { (beats, unit) -> beats > 0 && unit > 0 }
            ?: (4 to 4)

    private fun scaleTempo(microsPerQuarter: Int, factor: Double): Int =
        (microsPerQuarter / factor).roundToInt().coerceIn(1, 0xFFFFFF)
}

/**
 * How a bar is counted: compound meters (6/8, 9/8, 12/8...) are counted in dotted
 * beats, everything else in the denominator's note value.
 */
internal data class BeatGrid(val beatsPerBar: Int, val beatTicks: Long) {
    companion object {
        fun at(file: MidiFile, tick: Long): BeatGrid {
            val (numerator, denominator) = PlaybackMidiBuilder.timeSignatureAt(file, tick)
            val unitTicks = (file.division * 4L / denominator).coerceAtLeast(1)
            val compound = denominator >= 8 && numerator >= 6 && numerator % 3 == 0
            return if (compound) BeatGrid(numerator / 3, unitTicks * 3) else BeatGrid(numerator, unitTicks)
        }
    }
}
