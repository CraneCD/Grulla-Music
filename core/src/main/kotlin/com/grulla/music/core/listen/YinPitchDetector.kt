package com.grulla.music.core.listen

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** One analysis frame: the detected fundamental (if any), how sure, and how loud. */
data class PitchEstimate(
    /** Fundamental frequency in Hz, or null when the frame is silent or not periodic. */
    val frequencyHz: Double?,
    /** 0..1; 1 - the normalized YIN difference at the chosen period. */
    val confidence: Double,
    /** Root mean square level of the frame (full scale = 1.0). */
    val rms: Double,
) {
    /** MIDI note number as a real number (69.0 = A4 = 440 Hz). */
    val midi: Double? get() = frequencyHz?.let { 69.0 + 12.0 * ln(it / 440.0) / ln(2.0) }
}

/**
 * Monophonic pitch detection with the YIN algorithm (de Cheveigné & Kawahara,
 * 2002): difference function, cumulative mean normalization, absolute threshold
 * and parabolic interpolation. Written from the paper for this project.
 *
 * Each call to [detect] analyses [requiredSamples] samples. Not thread-safe
 * (reuses its buffers); use one instance per audio thread.
 */
class YinPitchDetector(
    val sampleRate: Int,
    /** Integration window in samples (~23 ms at 44.1 kHz). */
    val windowSize: Int = 1024,
    minFrequencyHz: Double = 60.0,
    maxFrequencyHz: Double = 1700.0,
    private val threshold: Double = 0.15,
    /** Frames quieter than this are reported as silent without analysis. */
    private val silenceRms: Double = 0.003,
) {
    private val maxLag = ceil(sampleRate / minFrequencyHz).toInt()
    private val minLag = floor(sampleRate / maxFrequencyHz).toInt().coerceAtLeast(2)
    private val difference = DoubleArray(maxLag + 2)

    /** Samples needed per analysis: the window plus the longest period searched. */
    val requiredSamples: Int = windowSize + maxLag + 1

    fun detect(samples: FloatArray, offset: Int = 0): PitchEstimate {
        require(samples.size - offset >= requiredSamples) { "Need $requiredSamples samples" }
        var energy = 0.0
        for (i in offset until offset + windowSize) energy += samples[i] * samples[i]
        val rms = sqrt(energy / windowSize)
        if (rms < silenceRms) return PitchEstimate(null, 0.0, rms)

        // Step 2: difference function d(tau).
        for (tau in 1..maxLag) {
            var sum = 0.0
            val shifted = offset + tau
            for (j in 0 until windowSize) {
                val delta = samples[offset + j] - samples[shifted + j]
                sum += delta * delta
            }
            difference[tau] = sum
        }
        // Step 3: cumulative mean normalized difference d'(tau).
        difference[0] = 1.0
        var runningSum = 0.0
        for (tau in 1..maxLag) {
            runningSum += difference[tau]
            difference[tau] = if (runningSum == 0.0) 1.0 else difference[tau] * tau / runningSum
        }
        // Step 4: first dip below the threshold, followed to its local minimum.
        var tau = minLag
        var found = -1
        while (tau <= maxLag) {
            if (difference[tau] < threshold) {
                while (tau + 1 <= maxLag && difference[tau + 1] < difference[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) {
            // No clear period: report the best candidate's confidence, but no pitch.
            var best = minLag
            for (t in minLag..maxLag) if (difference[t] < difference[best]) best = t
            return PitchEstimate(null, (1.0 - difference[best]).coerceIn(0.0, 1.0), rms)
        }
        // Step 5: parabolic interpolation around the minimum.
        val period = if (found in (minLag + 1) until maxLag) {
            val a = difference[found - 1]
            val b = difference[found]
            val c = difference[found + 1]
            val denominator = a - 2 * b + c
            if (denominator != 0.0) found + 0.5 * (a - c) / denominator else found.toDouble()
        } else {
            found.toDouble()
        }
        return PitchEstimate(sampleRate / period, (1.0 - difference[found]).coerceIn(0.0, 1.0), rms)
    }
}
