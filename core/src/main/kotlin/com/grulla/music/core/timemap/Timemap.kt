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

    private val measureById: Map<String, MeasureStart> = measures.associateBy { it.id }

    /** Time of the last event (normally the release of the final notes). */
    val durationMs: Double = this.entries.lastOrNull()?.tstamp ?: 0.0

    fun measureStartMs(measureId: String): Double? = measureById[measureId]?.timeMs

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
