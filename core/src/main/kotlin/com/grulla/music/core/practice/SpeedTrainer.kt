package com.grulla.music.core.practice

import com.grulla.music.core.playback.TempoRange
import kotlinx.serialization.Serializable

/** Speed trainer settings: start slow and go up by [stepPercent] every [repeats] passes of the loop. */
@Serializable
data class SpeedTrainerSettings(
    val startPercent: Int = 70,
    val targetPercent: Int = 100,
    val stepPercent: Int = 5,
    val repeats: Int = 2,
) {
    /** The same settings with every value in its allowed range and start below target. */
    fun normalized(): SpeedTrainerSettings {
        val start = TempoRange.clampPercent(startPercent)
        val target = TempoRange.clampPercent(targetPercent).coerceAtLeast(start)
        return SpeedTrainerSettings(start, target, stepPercent.coerceIn(1, 25), repeats.coerceIn(1, 20))
    }
}

/**
 * Counts passes through the loop and raises the tempo step by step until the
 * target. Each pass is one full repeat of the A–B loop.
 */
class SpeedTrainer(settings: SpeedTrainerSettings) {
    val settings = settings.normalized()

    var tempoPercent: Int = this.settings.startPercent
        private set

    /** Passes completed at the current tempo. */
    var passes: Int = 0
        private set

    val reachedTarget: Boolean get() = tempoPercent >= settings.targetPercent

    /** Records a completed pass; returns the new tempo when it goes up. */
    fun onPass(): Int? {
        if (reachedTarget) return null
        passes++
        if (passes < settings.repeats) return null
        passes = 0
        tempoPercent = minOf(settings.targetPercent, tempoPercent + settings.stepPercent)
        return tempoPercent
    }
}
