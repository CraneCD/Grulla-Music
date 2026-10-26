package com.grulla.music.core.midi

import com.grulla.music.core.Fixtures
import com.grulla.music.core.timemap.Timemap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MidiCodecTest {
    private val fixture = MidiReader.read(Fixtures.bytes("${Fixtures.CHRONO}.mid"))

    @Test
    fun `reads the Verovio fixture`() {
        assertEquals(1, fixture.format)
        assertEquals(120, fixture.division)
        assertEquals(3, fixture.tracks.size)
        assertTrue(fixture.allEvents().filterIsInstance<MidiEvent.Channel>().any { it.isNoteOn })
        val timeSignatures = fixture.allEvents().filterIsInstance<MidiEvent.Meta>().mapNotNull { it.timeSignature }.toSet()
        assertEquals(setOf(4 to 4), timeSignatures)
    }

    @Test
    fun `write then read gives the same events`() {
        val again = MidiReader.read(MidiWriter.write(fixture))
        assertEquals(fixture.format, again.format)
        assertEquals(fixture.division, again.division)
        assertEquals(fixture.tracks, again.tracks)
    }

    @Test
    fun `reads running status and encodes long delta times`() {
        // Format 0, 96 PPQ; note on, then a note off using running status (velocity 0) after 200000 ticks.
        val track = byteArrayOf(
            0x00, 0x90.toByte(), 60, 100,
            0x8C.toByte(), 0x9A.toByte(), 0x40, 60, 0, // delta 200000 = 0x0C 0x1A 0x40 in 7-bit groups
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val bytes = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0, 96) +
            "MTrk".toByteArray() + byteArrayOf(0, 0, 0, track.size.toByte()) + track
        val file = MidiReader.read(bytes)
        val events = file.tracks.single().events.filterIsInstance<MidiEvent.Channel>()
        assertEquals(listOf(0L, 200_000L), events.map { it.tick })
        assertTrue(events[0].isNoteOn)
        assertTrue(events[1].isNoteOff)
        assertEquals(file.tracks, MidiReader.read(MidiWriter.write(file)).tracks)
    }

    @Test
    fun `variable length values round trip at the boundaries`() {
        for (tick in listOf(0L, 127L, 128L, 16_383L, 16_384L, 2_097_151L, 0x0FFFFFFFL)) {
            val file = MidiFile(0, 480, listOf(MidiTrack(listOf(MidiEvent.Channel(tick, 0x90, 60, 64)))))
            assertEquals(tick, MidiReader.read(MidiWriter.write(file)).tracks[0].events[0].tick)
        }
    }

    @Test
    fun `rejects invalid files`() {
        assertFailsWith<MidiFormatException> { MidiReader.read("RIFF0000".toByteArray()) }
        assertFailsWith<MidiFormatException> { MidiReader.read("MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 1, 0, 2, 0, 96)) }
    }

    @Test
    fun `tempo map converts ticks and milliseconds across tempo changes`() {
        // 480 PPQ: 120 BPM for one quarter, then 60 BPM.
        val map = TempoMap(480, listOf(0L to 500_000, 480L to 1_000_000))
        assertEquals(0.0, map.tickToMs(0))
        assertEquals(500.0, map.tickToMs(480))
        assertEquals(1500.0, map.tickToMs(960))
        assertEquals(960.0, map.msToTick(1500.0), 1e-9)
        assertEquals(240.0, map.msToTick(250.0), 1e-9)
        assertEquals(1_000_000, map.microsAt(10_000))
        // No tempo events: 120 BPM.
        assertEquals(500.0, TempoMap(480, emptyList()).tickToMs(480))
    }

    @Test
    fun `MIDI note-on times agree with Verovio timemap onsets`() {
        // The reverse does not hold: a note tied from the previous one is an onset in the
        // timemap (so it gets highlighted) but is not re-struck in the MIDI.
        val timemap = Timemap.parse(Fixtures.text("${Fixtures.CHRONO}.timemap.json"))
        val tempoMap = TempoMap.of(fixture)
        val timemapOnsets = timemap.entries.filter { it.on.isNotEmpty() }.map { it.tstamp }
        val midiOnsets = fixture.allEvents()
            .filterIsInstance<MidiEvent.Channel>()
            .filter { it.isNoteOn }
            .map { tempoMap.tickToMs(it.tick) }
            .toList()
        assertTrue(midiOnsets.isNotEmpty())
        for (onset in midiOnsets) {
            val nearest = timemapOnsets.minBy { abs(it - onset) }
            assertTrue(abs(nearest - onset) <= 1.0, "MIDI note-on at $onset ms has no timemap onset (nearest $nearest)")
        }
    }

    @Test
    fun `tempo meta helpers encode and decode`() {
        val event = MidiEvent.tempo(0, 685_716)
        assertEquals(685_716, event.tempoMicros)
        assertContentEquals(byteArrayOf(0x0A, 0x76, 0x94.toByte()), event.data)
        assertEquals(0xFFFFFF, MidiEvent.tempo(0, Int.MAX_VALUE).tempoMicros)
    }
}
