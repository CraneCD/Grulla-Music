package com.grulla.music.core.listen

import com.grulla.music.core.Fixtures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chords played by FluidSynth (FluidR3 Mono piano), 22.05 kHz mono, one per 1.5 s:
 * C–E–G, C–E (G missing), F3–A3–C4, G3–B3–D4–F4, C–E–A, C3–G3–E4, C–E–G.
 */
class ChordDetectorTest {
    private val rate: Int
    private val samples: FloatArray

    init {
        val bytes = ByteBuffer.wrap(Fixtures.bytes("piano-chords-22050.wav")).order(ByteOrder.LITTLE_ENDIAN)
        rate = bytes.getInt(24)
        val dataSize = bytes.getInt(40)
        samples = FloatArray(dataSize / 2) { bytes.getShort(44 + 2 * it) / 32768f }
    }

    /** 4096 samples at 22.05 kHz: the same resolution as 8192 at 44.1 kHz. */
    private fun detector() = ChordDetector(rate, fftSize = 4096)

    private fun heard(atSeconds: Double, vararg pitches: Int): Map<Int, Boolean> {
        val end = (atSeconds * rate).toInt()
        return detector().presence(samples, end - 4096, pitches.toSet())
    }

    @Test
    fun `complete chords are heard`() {
        assertTrue(heard(0.4, 60, 64, 67).values.all { it }, "C E G: ${heard(0.4, 60, 64, 67)}")
        assertTrue(heard(3.4, 53, 57, 60).values.all { it }, "F A C: ${heard(3.4, 53, 57, 60)}")
        assertTrue(heard(4.9, 55, 59, 62, 65).values.all { it }, "G B D F: ${heard(4.9, 55, 59, 62, 65)}")
        assertTrue(heard(6.4, 60, 64, 69).values.all { it }, "C E A: ${heard(6.4, 60, 64, 69)}")
    }

    @Test
    fun `a missing note is noticed`() {
        assertEquals(false, heard(1.9, 60, 64, 67)[67], "G with only C E sounding: ${heard(1.9, 60, 64, 67)}")
        assertEquals(false, heard(0.4, 60, 64, 69)[69], "A with C E G sounding: ${heard(0.4, 60, 64, 69)}")
        assertEquals(false, heard(6.4, 60, 64, 67)[67], "G with C E A sounding: ${heard(6.4, 60, 64, 67)}")
        assertEquals(false, heard(4.9, 55, 59, 62, 64)[64], "E with G B D F sounding: ${heard(4.9, 55, 59, 62, 64)}")
    }

    @Test
    fun `streaming reports each complete strike of the expected chord once`() {
        val random = Random(3)
        val noisy = FloatArray(samples.size) { samples[it] + (random.nextFloat() - 0.5f) * 0.01f }
        for ((name, audio) in listOf("clean" to samples, "noisy" to noisy)) {
            // C–E–G at 0 s and 9 s; C3–G3–E4 at 7.5 s is the same chord in other octaves.
            assertEquals(listOf(0, 5, 6), stream(audio, setOf(60, 64, 67)).map { (it / 1500).toInt() }, name)
        }
        assertEquals(listOf(4), stream(samples, setOf(60, 64, 69)).map { (it / 1500).toInt() }, "C E A")
    }

    /** Times (ms) of the chord events for [expected], hop by hop as on the device. */
    private fun stream(samples: FloatArray, expected: Set<Int>): List<Double> {
        val detector = detector().apply { expect(expected) }
        // Half a second of silence first, as when the microphone starts before the player.
        val lead = rate / 2
        val audio = FloatArray(lead) + samples
        val events = ArrayList<Double>()
        var end = 4096
        while (end <= audio.size) {
            detector.process(audio, end, timeMs = (end - lead) * 1000.0 / rate)?.let { events += it.timeMs }
            end += 256
        }
        return events
    }

    @Test
    fun `pure tones with harmonics`() {
        val rate = 44_100
        fun tone(vararg pitches: Int) = FloatArray(8192) { i ->
            pitches.sumOf { p ->
                val f = 440.0 * 2.0.pow((p - 69) / 12.0)
                (1..4).sumOf { h -> sin(2 * PI * f * h * i / rate) * 0.1 / h }
            }.toFloat()
        }
        val detector = ChordDetector(rate)
        assertTrue(detector.presence(tone(57, 60, 64), 0, setOf(57, 60, 64)).values.all { it })
        assertFalse(detector.presence(tone(57, 60), 0, setOf(57, 60, 64)).getValue(64))
    }
}
