package com.grulla.music.core.playback

import com.grulla.music.core.timemap.Timemap
import com.grulla.music.core.timemap.lastIndexAtOrBefore

/**
 * A span of score time during which the set of sounding notes does not change.
 * [noteIds] keeps onset order, so the first id is a good anchor for the cursor.
 */
data class Segment(
    val index: Int,
    val startMs: Double,
    val noteIds: List<String>,
    val measureId: String?,
)

/**
 * Precomputes, from a [Timemap], which notes are sounding at any time, so the
 * per-frame lookup is a binary search instead of a replay of the note on/off list.
 */
class ActiveNoteIndex(timemap: Timemap) {
    val segments: List<Segment>

    init {
        val sounding = LinkedHashSet<String>()
        var measure: String? = null
        val result = ArrayList<Segment>(timemap.entries.size)
        for (entry in timemap.entries) {
            sounding.removeAll(entry.off.toSet())
            sounding.addAll(entry.on)
            entry.measureOn?.let { measure = it }
            val segment = Segment(result.size, entry.tstamp, sounding.toList(), measure)
            // Several entries can share a timestamp; keep only the final state for it.
            if (result.isNotEmpty() && result.last().startMs == entry.tstamp) {
                result[result.lastIndex] = segment.copy(index = result.lastIndex)
            } else {
                result += segment
            }
        }
        segments = result
    }

    /** Segment in effect at [scoreMs], or null before the first event. */
    fun segmentAt(scoreMs: Double): Segment? {
        val index = lastIndexAtOrBefore(segments.size, scoreMs) { segments[it].startMs }
        return if (index >= 0) segments[index] else null
    }

    fun activeAt(scoreMs: Double): List<String> = segmentAt(scoreMs)?.noteIds.orEmpty()
}

/**
 * Remembers the last segment that was shown so the UI is only told about changes,
 * not about every frame.
 */
class HighlightTracker(private val index: ActiveNoteIndex) {
    private var current: Segment? = null
    private var hasValue = false

    /** Returns the new segment when it differs from the last one reported, otherwise null. */
    fun update(scoreMs: Double): Change? {
        val segment = index.segmentAt(scoreMs)
        if (hasValue && segment?.index == current?.index) return null
        current = segment
        hasValue = true
        return Change(segment)
    }

    fun reset() {
        current = null
        hasValue = false
    }

    data class Change(val segment: Segment?)
}
