package com.grulla.music.core.playback

/**
 * Maps the player's clock (milliseconds since the prepared MIDI started) to score
 * time (milliseconds at 100 % tempo, the unit of Verovio's timemap) and back.
 *
 * A prepared MIDI file starts with [leadInMs] of count-in, then plays the score from
 * [scoreStartMs] at [tempoFactor] times the notated tempo, so the relation is linear.
 */
data class PlaybackWindow(
    val scoreStartMs: Double,
    val tempoFactor: Double,
    val leadInMs: Double = 0.0,
) {
    init {
        require(tempoFactor > 0.0) { "tempoFactor must be positive" }
    }

    /** Score time at player position [playerMs]; less than [scoreStartMs] during the count-in. */
    fun scoreTimeAt(playerMs: Double): Double = scoreStartMs + (playerMs - leadInMs) * tempoFactor

    fun playerTimeFor(scoreMs: Double): Double = leadInMs + (scoreMs - scoreStartMs) / tempoFactor

    fun isCountingIn(playerMs: Double): Boolean = playerMs < leadInMs
}

/** Tempo slider limits: 50 %–150 % of the notated tempo. */
object TempoRange {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 150
    const val DEFAULT_PERCENT = 100

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun factorFor(percent: Int): Double = clampPercent(percent) / 100.0
}
