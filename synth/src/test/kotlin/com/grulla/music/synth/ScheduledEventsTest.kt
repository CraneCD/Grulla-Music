package com.grulla.music.synth

import com.grulla.music.core.midi.MidiEvent
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.MidiTrack
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ScheduledEventsTest {
    @Test
    fun `channel messages are timed with the tempo map and packed`() {
        // 480 PPQ, 120 BPM then 60 BPM after one quarter.
        val file = MidiFile(
            format = 1,
            division = 480,
            tracks = listOf(
                MidiTrack(listOf(MidiEvent.tempo(0, 500_000), MidiEvent.tempo(480, 1_000_000))),
                MidiTrack(
                    listOf(
                        MidiEvent.Channel(0, 0xC0, 5),
                        MidiEvent.Channel(0, 0x90, 60, 100),
                        MidiEvent.Channel(480, 0x80, 60, 0),
                        MidiEvent.Channel(960, 0xE1, 0x00, 0x40),
                    ),
                ),
            ),
        )
        val scheduled = ScheduledEvents.from(file)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 500.0, 1500.0), scheduled.timesMs)
        assertEquals(0xC0 or (5 shl 8), scheduled.packed[0])
        assertEquals(0x90 or (60 shl 8) or (100 shl 16), scheduled.packed[1])
        assertEquals(0xE1 or (0x40 shl 16), scheduled.packed[3])
        assertEquals(1500.0, scheduled.endMs)
    }

    @Test
    fun `a release at the same time as a repeated note goes first`() {
        val file = MidiFile(
            format = 1,
            division = 480,
            tracks = listOf(
                MidiTrack(listOf(MidiEvent.Channel(480, 0x90, 60, 90))), // next note of staff 1
                MidiTrack(listOf(MidiEvent.Channel(0, 0x90, 60, 90), MidiEvent.Channel(480, 0x80, 60, 0))),
            ),
        )
        val scheduled = ScheduledEvents.from(file)
        assertEquals(listOf(0x90, 0x80, 0x90), scheduled.packed.map { it and 0xF0 })
    }
}
