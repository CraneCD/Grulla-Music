package com.grulla.music.core.midi

import com.grulla.music.core.playback.PlaybackWindow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Count-in settings: [bars] bars of clicks in the time signature at the start point. */
data class CountIn(
    val bars: Int = 1,
    val accentNote: Int = HI_WOOD_BLOCK,
    val beatNote: Int = LOW_WOOD_BLOCK,
    val accentVelocity: Int = 110,
    val beatVelocity: Int = 85,
) {
    init {
        require(bars >= 1) { "A count-in needs at least one bar" }
    }

    companion object {
        const val HI_WOOD_BLOCK = 76
        const val LOW_WOOD_BLOCK = 77
    }
}

/** A MIDI file ready for the player plus the mapping from player time back to score time. */
class PreparedMidi(
    val bytes: ByteArray,
    val window: PlaybackWindow,
    /** Number of count-in clicks before the music starts (0 without count-in). */
    val countInBeats: Int,
)

/**
 * Builds the MIDI that is actually played, from the score's MIDI as rendered by
 * Verovio:
 *  - starts at [startMs] (score time), keeping program/controller/tempo state;
 *  - scales every tempo by `tempoFactor` (1.0 = notated tempo);
 *  - optionally prepends a count-in on the GM percussion channel.
 *
 * The result always plays from position 0, so a single player clock maps linearly
 * to score time through [PreparedMidi.window].
 */
object PlaybackMidiBuilder {
    fun build(source: MidiFile, startMs: Double, tempoFactor: Double, countIn: CountIn?): PreparedMidi {
        require(tempoFactor > 0.0) { "tempoFactor must be positive" }
        val sourceTempo = TempoMap.of(source)
        val startTick = sourceTempo.msToTick(startMs.coerceAtLeast(0.0)).roundToLong().coerceAtLeast(0L)
        val exactStartMs = sourceTempo.tickToMs(startTick)

        val (beatsPerBar, beatUnit) = timeSignatureAt(source, startTick)
        val ticksPerBeat = (source.division * 4 / beatUnit).coerceAtLeast(1)
        val countInBeats = countIn?.let { it.bars * beatsPerBar } ?: 0
        val leadTicks = countInBeats.toLong() * ticksPerBeat

        val tracks = source.tracks.mapIndexed { index, track ->
            val shifted = shiftTrack(track, startTick, leadTicks, tempoFactor)
            if (index == 0) {
                // The tempo in effect at the start point governs the count-in and the first notes.
                val tempo = MidiEvent.tempo(0, scaleTempo(sourceTempo.microsAt(startTick), tempoFactor))
                MidiTrack(listOf(tempo) + shifted)
            } else {
                MidiTrack(shifted)
            }
        }.toMutableList()

        if (countIn != null) {
            tracks += countInTrack(countIn, countInBeats, beatsPerBar, ticksPerBeat)
        }

        val output = MidiFile(format = if (tracks.size > 1) 1 else source.format, division = source.division, tracks = tracks)
        val leadInMs = TempoMap.of(output).tickToMs(leadTicks)
        return PreparedMidi(
            bytes = MidiWriter.write(output),
            window = PlaybackWindow(scoreStartMs = exactStartMs, tempoFactor = tempoFactor, leadInMs = leadInMs),
            countInBeats = countInBeats,
        )
    }

    private fun shiftTrack(track: MidiTrack, startTick: Long, leadTicks: Long, tempoFactor: Double): List<MidiEvent> {
        val result = ArrayList<MidiEvent>(track.events.size)
        for (event in track.events) {
            val scaled = if (event is MidiEvent.Meta && event.tempoMicros != null) {
                MidiEvent.tempo(event.tick, scaleTempo(event.tempoMicros!!, tempoFactor))
            } else {
                event
            }
            if (event.tick >= startTick) {
                result += scaled.atTick(event.tick - startTick + leadTicks)
            } else if (!isNote(event) && !isTempo(event)) {
                // State before the start point (programs, controllers, time signature...) is replayed
                // at tick 0. Earlier tempo changes are replaced by the single tempo event that build()
                // puts at tick 0.
                result += event.atTick(0)
            }
        }
        return result
    }

    private fun isNote(event: MidiEvent) = event is MidiEvent.Channel && (event.isNoteOn || event.isNoteOff)

    private fun isTempo(event: MidiEvent) = event is MidiEvent.Meta && event.tempoMicros != null

    private fun countInTrack(countIn: CountIn, beats: Int, beatsPerBar: Int, ticksPerBeat: Int): MidiTrack {
        val events = ArrayList<MidiEvent>(beats * 2)
        val ch = MidiEvent.PERCUSSION_CHANNEL
        val clickLength = (ticksPerBeat / 2).coerceAtLeast(1)
        for (beat in 0 until beats) {
            val tick = beat.toLong() * ticksPerBeat
            val accent = beat % beatsPerBar == 0
            val note = if (accent) countIn.accentNote else countIn.beatNote
            val velocity = if (accent) countIn.accentVelocity else countIn.beatVelocity
            events += MidiEvent.Channel(tick, MidiEvent.NOTE_ON or ch, note, velocity)
            events += MidiEvent.Channel(tick + clickLength, MidiEvent.NOTE_OFF or ch, note, 0)
        }
        return MidiTrack(events)
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
