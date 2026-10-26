package com.grulla.music.core.listen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YinPitchDetectorTest {
    private val rate = 44_100
    private val detector = YinPitchDetector(rate)

    private fun midiOf(hz: Double) = 69 + 12 * ln(hz / 440) / ln(2.0)

    /** A tone with the given harmonic amplitudes (1st = fundamental), like a plucked or bowed string. */
    private fun tone(hz: Double, harmonics: List<Double> = listOf(1.0), amplitude: Double = 0.3, decay: Double = 0.0): FloatArray =
        FloatArray(detector.requiredSamples) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            harmonics.forEachIndexed { h, a -> v += a * sin(2 * PI * hz * (h + 1) * t) }
            (amplitude * v / harmonics.sum() * exp(-decay * t)).toFloat()
        }

    @Test
    fun `finds the pitch of a sine`() {
        val estimate = detector.detect(tone(440.0))
        assertEquals(440.0, estimate.frequencyHz!!, 1.0)
        assertEquals(69.0, estimate.midi!!, 0.05)
        assertTrue(estimate.confidence > 0.9)
    }

    @Test
    fun `finds the fundamental of rich tones across the range`() {
        val piano = listOf(1.0, 0.6, 0.4, 0.25, 0.15, 0.1)
        for (hz in listOf(82.41, 110.0, 196.0, 261.63, 329.63, 523.25, 880.0, 1318.5)) {
            val estimate = detector.detect(tone(hz, piano, decay = 3.0))
            assertNotNull(estimate.frequencyHz, "no pitch at $hz Hz")
            assertTrue(abs(estimate.midi!! - midiOf(hz)) < 0.2, "$hz Hz detected as ${estimate.frequencyHz}")
        }
    }

    @Test
    fun `a strong second harmonic does not cause an octave error`() {
        val estimate = detector.detect(tone(196.0, listOf(1.0, 2.0, 0.5)))
        assertEquals(midiOf(196.0), estimate.midi!!, 0.2)
    }

    @Test
    fun `silence and noise have no pitch`() {
        val silence = detector.detect(FloatArray(detector.requiredSamples))
        assertNull(silence.frequencyHz)
        assertEquals(0.0, silence.rms)
        val random = Random(42)
        val noise = detector.detect(FloatArray(detector.requiredSamples) { (random.nextFloat() - 0.5f) * 0.5f })
        assertTrue(noise.frequencyHz == null || noise.confidence < 0.75, "noise: $noise")
    }

    @Test
    fun `note names`() {
        assertEquals("C4", noteName(60))
        assertEquals("A4", noteName(69))
        assertEquals("C♯5", noteName(73))
        assertEquals("B♭2", noteName(46))
    }
}
