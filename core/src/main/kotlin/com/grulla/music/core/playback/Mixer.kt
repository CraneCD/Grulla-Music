package com.grulla.music.core.playback

import com.grulla.music.core.midi.MidiEvent
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.MidiTrack
import com.grulla.music.core.score.ScorePart

/**
 * One mixer strip: a MIDI track of the score, usually one staff of one part
 * (Verovio writes a track per staff, in score order, after a conductor track).
 */
data class MixerChannel(
    val trackIndex: Int,
    val partId: String?,
    val label: String,
    /** The score's General MIDI program for this track, if it sets one. */
    val program: Int? = null,
    /** Plays on the GM percussion channel (no instrument choice). */
    val percussion: Boolean = false,
)

/** Mute / solo, volume and instrument choices, keyed by MIDI track index. */
data class MixState(
    val muted: Set<Int> = emptySet(),
    val soloed: Set<Int> = emptySet(),
    /** Percent of the written dynamics; missing means 100. */
    val volumes: Map<Int, Int> = emptyMap(),
    /** Chosen General MIDI program; missing means the score's own. */
    val programs: Map<Int, Int> = emptyMap(),
) {
    fun volume(track: Int): Int = volumes[track] ?: 100

    fun withVolume(track: Int, percent: Int): MixState {
        val clamped = percent.coerceIn(0, MAX_VOLUME)
        return copy(volumes = if (clamped == 100) volumes - track else volumes + (track to clamped))
    }

    /** Sets [program] for [tracks] (all staves of one part share an instrument); null restores the score's. */
    fun withProgram(tracks: Collection<Int>, program: Int?): MixState =
        copy(programs = if (program == null) programs - tracks.toSet() else programs + tracks.associateWith { program })

    /** Tracks that must not sound: the muted ones, or everything outside the solo set. */
    fun silencedTracks(channels: List<MixerChannel>): Set<Int> {
        val all = channels.map { it.trackIndex }.toSet()
        val audible = if (soloed.isNotEmpty()) soloed intersect all else all
        return (all - audible) + (muted intersect all)
    }

    fun toggleMute(track: Int) = copy(muted = muted.toggle(track))

    fun toggleSolo(track: Int) = copy(soloed = soloed.toggle(track))

    private fun Set<Int>.toggle(value: Int) = if (value in this) this - value else this + value

    companion object {
        const val MAX_VOLUME = 150
    }
}

object Mixer {
    /**
     * Maps MusicXML parts to the MIDI tracks Verovio wrote for them. Parts take
     * `staves` consecutive tracks after the conductor track; if the counts do not
     * add up, each track becomes a channel labelled with its track name.
     */
    fun channels(parts: List<ScorePart>, midi: MidiFile): List<MixerChannel> {
        val musicTracks = midi.tracks.indices.filter { midi.tracks[it].hasNotes() }
        if (musicTracks.isEmpty()) return emptyList()
        val first = musicTracks.first()
        val expected = parts.sumOf { it.staves }
        if (parts.isNotEmpty() && expected == midi.tracks.size - first) {
            var track = first
            return parts.flatMap { part ->
                (1..part.staves).map { staff ->
                    val index = track++
                    MixerChannel(index, part.id, label(part, staff), midi.tracks[index].program(), midi.tracks[index].isPercussion())
                }
            }.filter { it.trackIndex in musicTracks }
        }
        return musicTracks.map { index ->
            val t = midi.tracks[index]
            MixerChannel(index, null, t.name() ?: "Track $index", t.program(), t.isPercussion())
        }
    }

    private fun label(part: ScorePart, staff: Int): String = when {
        part.staves == 1 -> part.name
        part.staves == 2 -> "${part.name} – ${if (staff == 1) "upper" else "lower"} staff"
        else -> "${part.name} – staff $staff"
    }

    private fun MidiTrack.program(): Int? = events.asSequence()
        .filterIsInstance<MidiEvent.Channel>()
        .firstOrNull { it.command == MidiEvent.PROGRAM_CHANGE }
        ?.data1

    private fun MidiTrack.isPercussion() = events.any { it is MidiEvent.Channel && it.isNoteOn && it.channel == MidiEvent.PERCUSSION_CHANNEL }

    private fun MidiTrack.hasNotes() = events.any { it is MidiEvent.Channel && it.isNoteOn }

    private fun MidiTrack.name(): String? = events.asSequence()
        .filterIsInstance<MidiEvent.Meta>()
        .firstOrNull { it.type == 0x03 }
        ?.let { String(it.data, Charsets.ISO_8859_1).trim() }
        ?.takeIf { it.isNotEmpty() }
}
