package com.grulla.music.core.listen

import kotlin.math.abs
import kotlin.math.roundToInt

/** A note the player has just started. */
data class NoteEvent(val midi: Int, val timeMs: Double)

/**
 * Turns a stream of per-frame [PitchEstimate]s into note onsets: a note counts
 * once its pitch has been stable for a few frames, and the same pitch counts again
 * only after a silence or a clear re-attack.
 *
 * Re-attacks are found from the level alone (a rise over the quietest point since
 * the last note), because the pitch is often unreadable during the attack itself;
 * the next stable pitch within [attackWindowMs] then counts as the new note.
 */
class NoteEventDetector(
    private val stableFrames: Int = 3,
    private val minConfidence: Double = 0.75,
    private val minRms: Double = 0.006,
    /** A level rise by this factor over the quietest point since the last note is a new attack. */
    private val reattackRatio: Double = 1.4,
    /** No re-attack is detected this soon after a note (the attack itself is still rising). */
    private val reattackHoldOffMs: Double = 120.0,
    /** A detected attack must be followed by a stable pitch within this time. */
    private val attackWindowMs: Double = 250.0,
    /** Maximum deviation from the nearest semitone in cents (pianos are often stretch-tuned). */
    private val maxCents: Double = 45.0,
) {
    private var candidate: Int? = null
    private var candidateFrames = 0
    private var lastEmitted: Int? = null
    private var lastEmittedAt = Double.NEGATIVE_INFINITY
    private var silentSinceEmit = true
    private var quietestSinceEmit = Double.MAX_VALUE
    private var attackAt = Double.NEGATIVE_INFINITY

    fun reset() {
        candidate = null
        candidateFrames = 0
        lastEmitted = null
        lastEmittedAt = Double.NEGATIVE_INFINITY
        silentSinceEmit = true
        quietestSinceEmit = Double.MAX_VALUE
        attackAt = Double.NEGATIVE_INFINITY
    }

    fun process(estimate: PitchEstimate, timeMs: Double): NoteEvent? {
        // Level-based attack detection, whether or not the frame has a readable pitch.
        if (timeMs - lastEmittedAt >= reattackHoldOffMs && estimate.rms >= minRms &&
            estimate.rms > quietestSinceEmit * reattackRatio
        ) {
            attackAt = timeMs
            quietestSinceEmit = estimate.rms // One attack per rise.
        }
        quietestSinceEmit = minOf(quietestSinceEmit, estimate.rms)

        val midi = estimate.midi
        val note = if (midi != null && estimate.confidence >= minConfidence && estimate.rms >= minRms &&
            abs(midi - midi.roundToInt()) * 100 <= maxCents
        ) {
            midi.roundToInt()
        } else {
            null
        }
        if (note == null) {
            candidate = null
            candidateFrames = 0
            if (estimate.rms < minRms) silentSinceEmit = true
            return null
        }

        if (note == candidate) candidateFrames++ else {
            candidate = note
            candidateFrames = 1
        }
        val reattack = note == lastEmitted && timeMs - attackAt <= attackWindowMs && attackAt > lastEmittedAt

        val stable = candidateFrames >= stableFrames
        val isNew = note != lastEmitted || silentSinceEmit
        if (stable && (isNew || reattack)) {
            lastEmitted = note
            lastEmittedAt = timeMs
            silentSinceEmit = false
            quietestSinceEmit = estimate.rms
            return NoteEvent(note, timeMs)
        }
        return null
    }
}
