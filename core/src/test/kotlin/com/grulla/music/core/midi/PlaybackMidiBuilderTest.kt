package com.grulla.music.core.midi

import com.grulla.music.core.Fixtures
import com.grulla.music.core.timemap.Timemap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackMidiBuilderTest {
    private val source = MidiReader.read(Fixtures.bytes("${Fixtures.CHRONO}.mid"))
    private val timemap = Timemap.parse(Fixtures.text("${Fixtures.CHRONO}.timemap.json"))

    /** Note-on times in ms (sorted) for the notes of the score (not the count-in). */
    private fun noteOnTimes(file: MidiFile, includePercussion: Boolean = false): List<Double> {
        val map = TempoMap.of(file)
        return file.allEvents()
            .filterIsInstance<MidiEvent.Channel>()
            .filter { it.isNoteOn && (includePercussion || it.channel != MidiEvent.PERCUSSION_CHANNEL) }
            .map { map.tickToMs(it.tick) }
            .sorted()
            .toList()
    }

    private fun lastEventMs(file: MidiFile): Double {
        val map = TempoMap.of(file)
        return map.tickToMs(file.allEvents().maxOf { it.tick })
    }

    @Test
    fun `full tempo from the start reproduces the source timing`() {
        val prepared = PlaybackMidiBuilder.build(source, startMs = 0.0, tempoFactor = 1.0, countIn = null)
        val output = MidiReader.read(prepared.bytes)
        assertEquals(noteOnTimes(source), noteOnTimes(output))
        assertEquals(0, prepared.countInBeats)
        assertEquals(0.0, prepared.window.leadInMs)
    }

    @Test
    fun `tempo factor scales every duration`() {
        val half = MidiReader.read(PlaybackMidiBuilder.build(source, 0.0, 0.5, null).bytes)
        val fast = MidiReader.read(PlaybackMidiBuilder.build(source, 0.0, 1.5, null).bytes)
        val original = lastEventMs(source)
        assertEquals(original * 2.0, lastEventMs(half), original * 0.001)
        assertEquals(original / 1.5, lastEventMs(fast), original * 0.001)
    }

    @Test
    fun `starting mid-score drops earlier notes and keeps instrument state`() {
        val measure = timemap.measures[8]
        val prepared = PlaybackMidiBuilder.build(source, measure.timeMs, 1.0, null)
        val output = MidiReader.read(prepared.bytes)
        val onsets = noteOnTimes(output)
        assertEquals(0.0, onsets.first(), 1.0)
        val expected = noteOnTimes(source).filter { it >= measure.timeMs - 1.0 }
        assertEquals(expected.size, onsets.size)
        // Program changes from the start of the source are still there, at tick 0.
        val programs = { file: MidiFile ->
            file.allEvents().filterIsInstance<MidiEvent.Channel>().filter { it.command == 0xC0 }.map { it.channel to it.data1 }.toList()
        }
        assertEquals(programs(source), programs(output))
        assertTrue(output.allEvents().filterIsInstance<MidiEvent.Channel>().filter { it.command == 0xC0 }.all { it.tick == 0L })
    }

    @Test
    fun `count-in adds one bar of clicks at the scaled start tempo`() {
        val prepared = PlaybackMidiBuilder.build(source, 0.0, 0.5, CountIn())
        val output = MidiReader.read(prepared.bytes)
        assertEquals(4, prepared.countInBeats)
        val clicks = output.allEvents().filterIsInstance<MidiEvent.Channel>()
            .filter { it.isNoteOn && it.channel == MidiEvent.PERCUSSION_CHANNEL }.toList()
        assertEquals(listOf(76, 77, 77, 77), clicks.map { it.data1 })
        // 87.5 BPM at 50 % = 43.75 BPM: one beat lasts 60000 / 43.75 ms.
        val beatMs = 60_000.0 / (87.5 * 0.5)
        assertEquals(4 * beatMs, prepared.window.leadInMs, 1.0)
        assertEquals(4 * beatMs, noteOnTimes(output).first(), 1.0)
        assertTrue(prepared.window.isCountingIn(prepared.window.leadInMs - 1))
    }

    @Test
    fun `window maps every played note back to its timemap onset`() {
        for (factor in listOf(0.5, 0.8, 1.0, 1.25, 1.5)) {
            val start = timemap.measures[12].timeMs
            val prepared = PlaybackMidiBuilder.build(source, start, factor, CountIn(bars = 2))
            val output = MidiReader.read(prepared.bytes)
            val onsets = timemap.entries.filter { it.on.isNotEmpty() }.map { it.tstamp }
            val played = noteOnTimes(output).map { prepared.window.scoreTimeAt(it) }
            assertEquals(noteOnTimes(source).count { it >= start - 1.0 }, played.size, "factor $factor")
            for (time in played) {
                val nearest = onsets.minBy { abs(it - time) }
                assertTrue(abs(nearest - time) <= 1.5, "factor $factor: note at score time $time, nearest onset $nearest")
            }
        }
    }

    @Test
    fun `count-in follows the time signature and defaults to 4-4`() {
        val threeFour = MidiFile(
            format = 0,
            division = 96,
            tracks = listOf(
                MidiTrack(
                    listOf(
                        MidiEvent.Meta(0, MidiEvent.META_TIME_SIGNATURE, byteArrayOf(3, 2, 24, 8)),
                        MidiEvent.Channel(0, 0x90, 60, 90),
                        MidiEvent.Channel(96, 0x80, 60, 0),
                    ),
                ),
            ),
        )
        val prepared = PlaybackMidiBuilder.build(threeFour, 0.0, 1.0, CountIn())
        assertEquals(3, prepared.countInBeats)
        assertEquals(1500.0, prepared.window.leadInMs, 1e-6) // 3 beats at the default 120 BPM.
        assertEquals(1, MidiReader.read(prepared.bytes).format)

        val noSignature = threeFour.copy(tracks = listOf(MidiTrack(threeFour.tracks[0].events.drop(1))))
        assertEquals(4 to 4, PlaybackMidiBuilder.timeSignatureAt(noSignature, 0))
    }
}
