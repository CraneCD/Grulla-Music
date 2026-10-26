package com.grulla.music.core.playback

/**
 * Players report their position in coarse steps (MediaPlayer updates it once per
 * audio buffer). For a smooth cursor this extrapolates between reports using the
 * frame clock, never runs more than [maxExtrapolationMs] ahead of the last report,
 * and never moves backwards by less than [seekThresholdMs] (a larger jump back is
 * treated as a real seek).
 */
class PositionSmoother(
    private val maxExtrapolationMs: Double = 120.0,
    private val seekThresholdMs: Double = 250.0,
) {
    private var lastReportedMs = Long.MIN_VALUE
    private var reportedAtNanos = 0L
    private var lastOutput = Double.NaN

    fun reset() {
        lastReportedMs = Long.MIN_VALUE
        lastOutput = Double.NaN
    }

    /**
     * @param reportedMs position as reported by the player
     * @param nowNanos monotonic time of the current frame
     * @param playing whether the player is advancing (no extrapolation when paused)
     */
    fun estimate(reportedMs: Long, nowNanos: Long, playing: Boolean = true): Double {
        if (reportedMs != lastReportedMs) {
            lastReportedMs = reportedMs
            reportedAtNanos = nowNanos
        }
        val elapsed = if (playing) ((nowNanos - reportedAtNanos) / 1_000_000.0).coerceIn(0.0, maxExtrapolationMs) else 0.0
        val estimate = reportedMs + elapsed
        val previous = lastOutput
        val output = when {
            previous.isNaN() -> estimate
            estimate < previous && previous - estimate < seekThresholdMs -> previous
            else -> estimate
        }
        lastOutput = output
        return output
    }
}
