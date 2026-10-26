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
)

/** Mute / solo state, keyed by MIDI track index. */
data class MixState(
    val muted: Set<Int> = emptySet(),
    val soloed: Set<Int> = emptySet(),
) {
    /** Tracks that must not sound: the muted ones, or everything outside the solo set. */
    fun silencedTracks(channels: List<MixerChannel>): Set<Int> {
        val all = channels.map { it.trackIndex }.toSet()
        val audible = if (soloed.isNotEmpty()) soloed intersect all else all
        return (all - audible) + (muted intersect all)
    }

    fun toggleMute(track: Int) = copy(muted = muted.toggle(track))

    fun toggleSolo(track: Int) = copy(soloed = soloed.toggle(track))

    private fun Set<Int>.toggle(value: Int) = if (value in this) this - value else this + value
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
                    MixerChannel(track++, part.id, label(part, staff))
                }
            }.filter { it.trackIndex in musicTracks }
        }
        return musicTracks.map { index ->
            MixerChannel(index, null, midi.tracks[index].name() ?: "Track $index")
        }
    }

    private fun label(part: ScorePart, staff: Int): String = when {
        part.staves == 1 -> part.name
        part.staves == 2 -> "${part.name} – ${if (staff == 1) "upper" else "lower"} staff"
        else -> "${part.name} – staff $staff"
    }

    private fun MidiTrack.hasNotes() = events.any { it is MidiEvent.Channel && it.isNoteOn }

    private fun MidiTrack.name(): String? = events.asSequence()
        .filterIsInstance<MidiEvent.Meta>()
        .firstOrNull { it.type == 0x03 }
        ?.let { String(it.data, Charsets.ISO_8859_1).trim() }
        ?.takeIf { it.isNotEmpty() }
}
