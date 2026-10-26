package com.grulla.music.core.timemap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry of Verovio's `renderToTimemap()` output. Times are in milliseconds at
 * the score's notated tempo (100 %); [qstamp] is in quarter notes.
 */
@Serializable
data class TimemapEntry(
    val tstamp: Double,
    val qstamp: Double = 0.0,
    val on: List<String> = emptyList(),
    val off: List<String> = emptyList(),
    val restsOn: List<String> = emptyList(),
    val restsOff: List<String> = emptyList(),
    val tempo: Double? = null,
    val measureOn: String? = null,
)

data class MeasureStart(val id: String, val timeMs: Double, val qstamp: Double)

/**
 * Parsed timemap with the lookups the player needs: measure start times (for
 * seeking) and the tempo in effect at a given time (for the count-in).
 */
class Timemap(entries: List<TimemapEntry>) {
    val entries: List<TimemapEntry> = entries.sortedBy { it.tstamp }

    val measures: List<MeasureStart> = this.entries.mapNotNull { entry ->
        entry.measureOn?.let { MeasureStart(it, entry.tstamp, entry.qstamp) }
    }

    /**
     * Verovio expands repeats, so a measure inside a repeat starts more than once.
     * Seeking by measure goes to its first pass.
     */
    private val measureById: Map<String, MeasureStart> = LinkedHashMap<String, MeasureStart>().apply {
        for (measure in measures) putIfAbsent(measure.id, measure)
    }

    /** Bar number (1-based) of each measure, in the order measures first appear; repeats keep their number. */
    private val barNumbers: Map<String, Int> = measureById.keys.withIndex().associate { (i, id) -> id to i + 1 }

    /** Number of distinct measures (bars) in the score. */
    val barCount: Int get() = barNumbers.size

    /** Bar number at [timeMs] (1-based), or 0 for an empty score. */
    fun barAt(timeMs: Double): Int = measureAt(timeMs)?.let { barNumbers[it.id] } ?: 0

    /** Time of the last event (normally the release of the final notes). */
    val durationMs: Double = this.entries.lastOrNull()?.tstamp ?: 0.0

    fun measureStartMs(measureId: String): Double? = measureById[measureId]?.timeMs

    /** End of the first pass through [measureId]: where the following measure starts. */
    fun measureEndMs(measureId: String): Double? {
        val first = measureById[measureId] ?: return null
        val index = measures.indexOf(first)
        return measures.getOrNull(index + 1)?.timeMs ?: durationMs
    }

    /**
     * The time range covering measures [firstId] to [lastId] inclusive (in either
     * order), e.g. for an A–B loop. Null if a measure is unknown.
     */
    fun rangeOfMeasures(firstId: String, lastId: String): Pair<Double, Double>? {
        val a = measureStartMs(firstId) ?: return null
        val b = measureStartMs(lastId) ?: return null
        val (from, to) = if (a <= b) firstId to lastId else lastId to firstId
        val start = measureStartMs(from)!!
        val end = measureEndMs(to)!!
        return if (end > start) start to end else null
    }

    /** The measure that contains [timeMs], or the first measure if [timeMs] is before it. */
    fun measureAt(timeMs: Double): MeasureStart? {
        if (measures.isEmpty()) return null
        val index = lastIndexAtOrBefore(measures.size, timeMs) { measures[it].timeMs }
        return measures[maxOf(index, 0)]
    }

    /** Tempo (quarter notes per minute) in effect at [timeMs]; 120 if the timemap has none. */
    fun tempoAt(timeMs: Double): Double {
        var tempo: Double? = null
        for (entry in entries) {
            if (entry.tstamp > timeMs) break
            entry.tempo?.let { tempo = it }
        }
        return tempo ?: entries.firstNotNullOfOrNull { it.tempo } ?: DEFAULT_TEMPO
    }

    companion object {
        const val DEFAULT_TEMPO = 120.0

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(timemapJson: String): Timemap =
            Timemap(json.decodeFromString<List<TimemapEntry>>(timemapJson))
    }
}

/**
 * Binary search helper: index of the last element whose key is <= [value], or -1 if
 * every key is greater. Keys must be sorted ascending.
 */
internal inline fun lastIndexAtOrBefore(size: Int, value: Double, key: (Int) -> Double): Int {
    var low = 0
    var high = size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (key(mid) <= value) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
