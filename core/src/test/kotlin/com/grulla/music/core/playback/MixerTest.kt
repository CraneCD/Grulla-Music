package com.grulla.music.core.playback

import com.grulla.music.core.Fixtures
import com.grulla.music.core.midi.MidiEvent
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.MidiReader
import com.grulla.music.core.midi.MidiTrack
import com.grulla.music.core.score.PartListParser
import com.grulla.music.core.score.ScorePart
import kotlin.test.Test
import kotlin.test.assertEquals

class MixerTest {
    @Test
    fun `parts map to Verovio tracks in staff order`() {
        val parts = PartListParser.parse(Fixtures.text("three-parts.musicxml"))
        val midi = MidiReader.read(Fixtures.bytes("three-parts.mid"))
        val channels = Mixer.channels(parts, midi)
        assertEquals(
            listOf(
                MixerChannel(1, "V", "Voice", partName = "Voice", staff = 1, staves = 1),
                MixerChannel(2, "P", "Piano – upper staff", partName = "Piano", staff = 1, staves = 2),
                MixerChannel(3, "P", "Piano – lower staff", partName = "Piano", staff = 2, staves = 2),
                MixerChannel(4, "B", "Bass", partName = "Bass", staff = 1, staves = 1),
            ),
            channels.map { it.copy(program = null) },
        )
    }

    @Test
    fun `the piano fixture has one channel per hand`() {
        val mxl = com.grulla.music.core.score.MusicXmlLoader.load(Fixtures.bytes("${Fixtures.CHRONO}.mxl"))
        val parts = PartListParser.parse(mxl.musicXml)
        val channels = Mixer.channels(parts, MidiReader.read(Fixtures.bytes("${Fixtures.CHRONO}.mid")))
        assertEquals(listOf(1, 2), channels.map { it.trackIndex })
    }

    @Test
    fun `falls back to track names when the staff count does not match`() {
        fun track(name: String) = MidiTrack(
            listOf(
                MidiEvent.Meta(0, 0x03, name.toByteArray()),
                MidiEvent.Channel(0, 0x90, 60, 80),
            ),
        )
        val midi = MidiFile(1, 480, listOf(MidiTrack(emptyList()), track("Flute"), track("Oboe")))
        val channels = Mixer.channels(listOf(ScorePart("P1", "Winds", 1)), midi)
        assertEquals(listOf("Flute", "Oboe"), channels.map { it.label })
        assertEquals(listOf(1, 2), channels.map { it.trackIndex })
    }

    @Test
    fun `mute and solo decide which tracks are silenced`() {
        val channels = (1..4).map { MixerChannel(it, null, "T$it") }
        assertEquals(emptySet(), MixState().silencedTracks(channels))
        assertEquals(setOf(2), MixState().toggleMute(2).silencedTracks(channels))
        assertEquals(setOf(1, 2, 4), MixState().toggleSolo(3).silencedTracks(channels))
        // Solo plus mute on the soloed track: silence everything.
        assertEquals(setOf(1, 2, 3, 4), MixState().toggleSolo(3).toggleMute(3).silencedTracks(channels))
        // Toggling twice restores.
        assertEquals(emptySet(), MixState().toggleSolo(3).toggleSolo(3).silencedTracks(channels))
    }
}
