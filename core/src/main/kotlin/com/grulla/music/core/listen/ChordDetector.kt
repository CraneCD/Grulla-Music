package com.grulla.music.core.listen

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How sure the chord check must be that every note sounds. */
enum class ChordSensitivity(
    /** A note's fundamental or octave must reach this share of the loudest peak in the spectrum. */
    val relative: Double,
    /** ...and this many times the spectrum's noise floor. */
    val overFloor: Double,
) {
    RELAXED(0.08, 4.0),
    NORMAL(0.15, 6.0),
    STRICT(0.25, 9.0),
}

/** All notes of the expected chord were heard, struck together. */
data class ChordEvent(val pitches: Set<Int>, val timeMs: Double)

/**
 * Checks that every note of an *expected* chord sounds. Polyphonic transcription
 * in general is hard, but verifying a known chord is not: for each expected note
 * the spectrum must show a peak at its fundamental or its octave, compared with
 * the loudest peak. Partials that coincide with a partial of another chord note (C4's
 * third harmonic is G5, G4's second) are left out, so a note is not counted as
 * heard just because a lower note's overtones cover it. A note whose partials are
 * all shared (an octave doubling) cannot be told apart and is accepted.
 *
 * [process] reports a chord once when it is newly complete: right after the
 * strike that completes it, or after a fresh attack (a level rise).
 *
 * Not thread-safe; use one instance on the audio thread.
 */
class ChordDetector(
    val sampleRate: Int,
    /** FFT size; 8192 at 44.1 kHz resolves semitones down to about C3 (with the harmonics, lower). */
    val fftSize: Int = 8192,
    var sensitivity: ChordSensitivity = ChordSensitivity.NORMAL,
    private val minRms: Double = 0.006,
    private val reattackRatio: Double = 1.4,
) {
    init {
        require(fftSize >= 256 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val magnitudes = DoubleArray(fftSize / 2)

    private var expected: Set<Int> = emptySet()
    private var wasComplete = false
    private var quietest = Double.MAX_VALUE
    private var pendingAttack = false
    private var attackAt = Double.NEGATIVE_INFINITY

    /** How long after an attack the chord is checked: when most of the FFT window follows it. */
    private val settleMs = fftSize * 1000.0 / sampleRate * 0.6

    /** Spectrum magnitude of a partial at amplitude 0.001 (-60 dBFS): quieter is not a note. */
    private val absoluteFloor = 0.001 * fftSize / 4

    /** Starts checking for [pitches] (MIDI numbers); a chord already sounding does not count until struck again. */
    fun expect(pitches: Set<Int>) {
        if (pitches == expected) return
        expected = pitches
        wasComplete = true
        pendingAttack = false
        quietest = Double.MAX_VALUE
        attackAt = Double.NEGATIVE_INFINITY
    }

    /**
     * Analyses the newest [fftSize] samples of [samples] (ending at [end]) and
     * returns an event when the expected chord has just become complete.
     */
    fun process(samples: FloatArray, end: Int = samples.size, timeMs: Double): ChordEvent? {
        if (expected.size < 2) return null
        val start = end - fftSize
        require(start >= 0) { "Need $fftSize samples" }
        var sum = 0.0
        for (i in end - HOP_FOR_LEVEL until end) sum += samples[i] * samples[i].toDouble()
        val rms = sqrt(sum / HOP_FOR_LEVEL)
        if (rms >= minRms && rms > quietest * reattackRatio) {
            pendingAttack = true
            attackAt = timeMs
            quietest = rms
        }
        quietest = minOf(quietest, rms)
        if (rms < minRms) {
            wasComplete = false
            return null
        }
        // Wait until the analysis window is mostly past the attack: the strike itself is broadband noise.
        if (timeMs - attackAt < settleMs) return null
        val complete = presence(samples, start).values.all { it }
        val event = complete && (!wasComplete || pendingAttack)
        pendingAttack = false
        wasComplete = complete
        return if (event) ChordEvent(expected, timeMs) else null
    }

    /** For each expected note, whether it can be heard in the [fftSize] samples starting at [start]. */
    fun presence(samples: FloatArray, start: Int, pitches: Set<Int> = expected): Map<Int, Boolean> {
        spectrum(samples, start)
        val floor = maxOf(noiseFloor(), absoluteFloor / sensitivity.overFloor)
        val loudest = loudestPeak()
        return pitches.associateWith { pitch ->
            val s = salience(pitch, pitches - pitch)
            s == null || (s >= loudest * sensitivity.relative && s >= floor * sensitivity.overFloor)
        }
    }

    /**
     * How strongly [pitch] sounds: its strongest unshared partial among the
     * fundamental and the octave (a piano's low notes have a weak fundamental).
     * When both are shared with [others], the higher partials decide; null when
     * every partial is shared (an octave doubling cannot be told apart).
     */
    private fun salience(pitch: Int, others: Set<Int>): Double? {
        val f0 = frequencyOf(pitch)
        fun unshared(h: Int) = others.none { other ->
            val g = frequencyOf(other)
            (1..MAX_SHARED_HARMONIC).any { k -> centsBetween(f0 * h, g * k) < SHARED_CENTS }
        }
        val anchors = (1..2).filter(::unshared)
        val partials = anchors.ifEmpty { (3..MAX_PARTIAL).filter { f0 * it < sampleRate * 0.45 && unshared(it) } }
        if (partials.isEmpty()) return null
        return partials.maxOf { peak(f0 * it) }
    }

    /** The strongest spectral peak between 60 Hz and 5 kHz: the reference for "loud enough". */
    private fun loudestPeak(): Double {
        val binHz = sampleRate.toDouble() / fftSize
        val from = (60 / binHz).toInt().coerceAtLeast(1)
        val to = (5000 / binHz).toInt().coerceAtMost(magnitudes.size - 1)
        var best = 0.0
        for (bin in from..to) best = maxOf(best, magnitudes[bin])
        return best
    }

    /**
     * Largest spectral peak (local maximum) within ±[PEAK_CENTS] of [frequency]. Only
     * peaks count: at low pitches the window reaches into the main lobe of a
     * neighbouring semitone, whose slope must not pass for a note.
     */
    private fun peak(frequency: Double): Double {
        val ratio = 2.0.pow(PEAK_CENTS / 1200.0)
        val binHz = sampleRate.toDouble() / fftSize
        val low = (frequency / ratio / binHz).toInt().coerceIn(1, magnitudes.size - 2)
        val high = (frequency * ratio / binHz + 1).toInt().coerceIn(low, magnitudes.size - 2)
        var best = 0.0
        for (bin in low..high) {
            val m = magnitudes[bin]
            if (m >= magnitudes[bin - 1] && m >= magnitudes[bin + 1]) best = maxOf(best, m)
        }
        return best
    }

    /** Median magnitude between 60 Hz and 5 kHz. */
    private fun noiseFloor(): Double {
        val binHz = sampleRate.toDouble() / fftSize
        val from = (60 / binHz).toInt().coerceAtLeast(1)
        val to = (5000 / binHz).toInt().coerceAtMost(magnitudes.size - 1)
        val band = magnitudes.copyOfRange(from, to + 1)
        band.sort()
        return band[band.size / 2]
    }

    private fun spectrum(samples: FloatArray, start: Int) {
        for (i in 0 until fftSize) {
            re[i] = samples[start + i] * window[i]
            im[i] = 0.0
        }
        Fft.transform(re, im)
        for (i in magnitudes.indices) magnitudes[i] = hypot(re[i], im[i])
    }

    private companion object {
        const val MAX_PARTIAL = 5
        const val MAX_SHARED_HARMONIC = 8
        const val SHARED_CENTS = 35.0
        const val PEAK_CENTS = 40.0
        const val HOP_FOR_LEVEL = 512

        fun frequencyOf(pitch: Int) = 440.0 * 2.0.pow((pitch - 69) / 12.0)

        fun centsBetween(a: Double, b: Double) = kotlin.math.abs(1200.0 * kotlin.math.ln(a / b) / kotlin.math.ln(2.0))
    }
}

/** In-place iterative radix-2 FFT. */
internal object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val a = i + k
                    val b = a + length / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val next = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = next
                }
                i += length
            }
            length = length shl 1
        }
    }
}
