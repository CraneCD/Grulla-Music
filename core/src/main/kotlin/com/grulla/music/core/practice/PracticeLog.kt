package com.grulla.music.core.practice

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How often the notes of one bar were played right or wrong in Follow me. */
@Serializable
data class BarStats(val hits: Int = 0, val misses: Int = 0) {
    val attempts: Int get() = hits + misses
}

/** A bar worth looping: where Follow me heard the most wrong notes. */
data class TroubleBar(val bar: Int, val misses: Int, val attempts: Int) {
    val missRate: Double get() = if (attempts == 0) 0.0 else misses.toDouble() / attempts
}

/** Practice on one score. Days are ISO dates (yyyy-MM-dd) in the device's time zone. */
@Serializable
data class ScorePractice(
    val totalMs: Long = 0,
    val days: Map<String, Long> = emptyMap(),
    val loopPasses: Int = 0,
    val bars: Map<Int, BarStats> = emptyMap(),
) {
    fun withTime(ms: Long, today: LocalDate): ScorePractice {
        if (ms <= 0) return this
        val key = today.toString()
        val oldest = today.minusDays(KEEP_DAYS).toString()
        val kept = days.filterKeys { it >= oldest }
        return copy(totalMs = totalMs + ms, days = kept + (key to (kept[key] ?: 0L) + ms))
    }

    fun withLoopPass(): ScorePractice = copy(loopPasses = loopPasses + 1)

    fun withHit(bar: Int): ScorePractice = updateBar(bar) { it.copy(hits = it.hits + 1) }

    fun withMiss(bar: Int): ScorePractice = updateBar(bar) { it.copy(misses = it.misses + 1) }

    /** Time practised in the last [count] days, today included. */
    fun recentMs(today: LocalDate, count: Int = 7): Long {
        val from = today.minusDays(count - 1L).toString()
        return days.filterKeys { it >= from }.values.sum()
    }

    /** Bars with repeated wrong notes, the worst first. */
    fun troubleBars(limit: Int = 5): List<TroubleBar> =
        bars.map { (bar, stats) -> TroubleBar(bar, stats.misses, stats.attempts) }
            .filter { it.misses >= MIN_MISSES }
            .sortedWith(compareByDescending<TroubleBar> { it.missRate }.thenByDescending { it.misses }.thenBy { it.bar })
            .take(limit)

    /** Follow me accuracy over all bars, 0..1, or null before any attempt. */
    val accuracy: Double?
        get() {
            val hits = bars.values.sumOf { it.hits }
            val attempts = bars.values.sumOf { it.attempts }
            return if (attempts == 0) null else hits.toDouble() / attempts
        }

    private fun updateBar(bar: Int, change: (BarStats) -> BarStats) =
        if (bar <= 0) this else copy(bars = bars + (bar to change(bars[bar] ?: BarStats())))

    companion object {
        const val KEEP_DAYS = 90L
        const val MIN_MISSES = 2
    }
}

/** Practice per score, keyed by library entry id. Immutable. */
@Serializable
data class PracticeLog(val scores: Map<String, ScorePractice> = emptyMap()) {
    operator fun get(id: String): ScorePractice = scores[id] ?: ScorePractice()

    fun update(id: String, change: (ScorePractice) -> ScorePractice) = PracticeLog(scores + (id to change(get(id))))

    fun without(id: String) = PracticeLog(scores - id)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun decode(text: String): PracticeLog = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(PracticeLog())
    }
}
