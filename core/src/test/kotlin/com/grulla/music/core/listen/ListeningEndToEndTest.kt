package com.grulla.music.core.listen

import com.grulla.music.core.Fixtures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Listens to a real piano rendering: the first four bars of "Ode to Joy" played
 * by FluidSynth with the app's default SoundFont (FluidR3 Mono), 22.05 kHz mono,
 * with its attacks, release tails and reverb. Five notes are repeated, which
 * tests re-attack detection.
 */
class ListeningEndToEndTest {
    private val melody = listOf(64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 64, 62, 62)

    private fun readWav(name: String): Pair<Int, FloatArray> {
        val bytes = ByteBuffer.wrap(Fixtures.bytes(name)).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bytes.getInt(24)
        val dataSize = bytes.getInt(40)
        val samples = FloatArray(dataSize / 2) { bytes.getShort(44 + 2 * it) / 32768f }
        return rate to samples
    }

    private fun listen(rate: Int, samples: FloatArray): List<NoteEvent> {
        val detector = YinPitchDetector(rate)
        val notes = NoteEventDetector()
        val hop = 256
        val events = ArrayList<NoteEvent>()
        var start = 0
        while (start + detector.requiredSamples <= samples.size) {
            val estimate = detector.detect(samples, start)
            notes.process(estimate, timeMs = start * 1000.0 / rate)?.let { events += it }
            start += hop
        }
        return events
    }

    @Test
    fun `hears every note of the melody in order`() {
        val (rate, samples) = readWav("piano-melody-22050.wav")
        val heard = listen(rate, samples)
        assertEquals(melody, heard.map { it.midi })
    }

    @Test
    fun `follows the melody to the end, also with background noise`() {
        val (rate, clean) = readWav("piano-melody-22050.wav")
        val random = Random(7)
        val noisy = FloatArray(clean.size) { clean[it] + (random.nextFloat() - 0.5f) * 0.01f }
        for (samples in listOf(clean, noisy)) {
            val score = FollowScore(melody.mapIndexed { i, p -> FollowStep(i * 600.0, listOf("n$i"), setOf(p)) })
            val follow = FollowController(score, octaveTolerant = false)
            val results = listen(rate, samples).map { follow.onNote(it) }
            assertTrue(follow.state.finished, "stopped at step ${follow.state.stepIndex}: $results")
            assertTrue(results.count { it == FollowResult.WRONG } <= 1, "too many wrong notes: $results")
        }
    }
}
