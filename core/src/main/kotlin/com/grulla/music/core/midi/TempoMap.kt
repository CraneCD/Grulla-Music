package com.grulla.music.core.midi

/**
 * Converts between ticks and milliseconds using every tempo event of a file
 * (tempo events may live in any track of a format 1 file).
 */
class TempoMap(division: Int, tempoEvents: List<Pair<Long, Int>>) {
    private val ticksPerQuarter = division.toDouble()

    /** Tempo segments sorted by tick; the first always starts at tick 0. */
    private val segmentTicks: LongArray
    private val segmentMicros: IntArray
    private val segmentStartMs: DoubleArray

    init {
        val sorted = tempoEvents.sortedBy { it.first }
        val ticks = ArrayList<Long>()
        val micros = ArrayList<Int>()
        ticks += 0L
        micros += MidiEvent.DEFAULT_TEMPO_US
        for ((tick, us) in sorted) {
            if (tick == ticks.last()) {
                micros[micros.lastIndex] = us // Later events at the same tick win.
            } else {
                ticks += tick
                micros += us
            }
        }
        segmentTicks = ticks.toLongArray()
        segmentMicros = micros.toIntArray()
        segmentStartMs = DoubleArray(ticks.size)
        for (i in 1 until ticks.size) {
            segmentStartMs[i] = segmentStartMs[i - 1] + ticksToMs(ticks[i] - ticks[i - 1], micros[i - 1])
        }
    }

    fun tickToMs(tick: Long): Double {
        val i = segmentIndexForTick(tick)
        return segmentStartMs[i] + ticksToMs(tick - segmentTicks[i], segmentMicros[i])
    }

    fun msToTick(ms: Double): Double {
        var i = segmentStartMs.size - 1
        while (i > 0 && segmentStartMs[i] > ms) i--
        return segmentTicks[i] + (ms - segmentStartMs[i]) * 1000.0 * ticksPerQuarter / segmentMicros[i]
    }

    /** Microseconds per quarter note in effect at [tick]. */
    fun microsAt(tick: Long): Int = segmentMicros[segmentIndexForTick(tick)]

    private fun segmentIndexForTick(tick: Long): Int {
        var i = segmentTicks.size - 1
        while (i > 0 && segmentTicks[i] > tick) i--
        return i
    }

    private fun ticksToMs(ticks: Long, microsPerQuarter: Int): Double =
        ticks * microsPerQuarter / ticksPerQuarter / 1000.0

    companion object {
        fun of(file: MidiFile): TempoMap = TempoMap(
            file.division,
            file.allEvents()
                .filterIsInstance<MidiEvent.Meta>()
                .mapNotNull { event -> event.tempoMicros?.let { event.tick to it } }
                .toList(),
        )
    }
}
