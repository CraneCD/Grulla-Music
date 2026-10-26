package com.grulla.music.core.midi

/**
 * Minimal in-memory Standard MIDI File model. Events carry absolute tick positions,
 * which makes cutting, shifting and merging tracks straightforward; delta times
 * only exist in the encoded form (see [MidiReader] / [MidiWriter]).
 */
data class MidiFile(
    val format: Int,
    /** Ticks per quarter note. SMPTE time division is not supported. */
    val division: Int,
    val tracks: List<MidiTrack>,
)

data class MidiTrack(val events: List<MidiEvent>)

sealed class MidiEvent {
    abstract val tick: Long
    abstract fun atTick(newTick: Long): MidiEvent

    /** Channel voice message, e.g. note on/off, program change, controller. */
    data class Channel(override val tick: Long, val status: Int, val data1: Int, val data2: Int = 0) : MidiEvent() {
        val command: Int get() = status and 0xF0
        val channel: Int get() = status and 0x0F
        val isNoteOn: Boolean get() = command == NOTE_ON && data2 > 0
        val isNoteOff: Boolean get() = command == NOTE_OFF || (command == NOTE_ON && data2 == 0)
        override fun atTick(newTick: Long) = copy(tick = newTick)
    }

    class Meta(override val tick: Long, val type: Int, val data: ByteArray) : MidiEvent() {
        override fun atTick(newTick: Long) = Meta(newTick, type, data)
        override fun equals(other: Any?) =
            other is Meta && other.tick == tick && other.type == type && other.data.contentEquals(data)
        override fun hashCode() = (tick.hashCode() * 31 + type) * 31 + data.contentHashCode()
        override fun toString() = "Meta(tick=$tick, type=0x${type.toString(16)}, data=${data.joinToString(" ") { "%02x".format(it) }})"
    }

    /** System exclusive event; [status] is 0xF0 or 0xF7. */
    class SysEx(override val tick: Long, val status: Int, val data: ByteArray) : MidiEvent() {
        override fun atTick(newTick: Long) = SysEx(newTick, status, data)
        override fun equals(other: Any?) =
            other is SysEx && other.tick == tick && other.status == status && other.data.contentEquals(data)
        override fun hashCode() = (tick.hashCode() * 31 + status) * 31 + data.contentHashCode()
    }

    companion object {
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val META_MARKER = 0x06
        const val META_END_OF_TRACK = 0x2F
        const val META_TEMPO = 0x51
        const val META_TIME_SIGNATURE = 0x58

        /** GM percussion channel (channel 10 in 1-based numbering). */
        const val PERCUSSION_CHANNEL = 9

        /** Microseconds per quarter note when a file has no tempo event (120 BPM). */
        const val DEFAULT_TEMPO_US = 500_000

        fun tempo(tick: Long, microsPerQuarter: Int): Meta {
            val us = microsPerQuarter.coerceIn(1, 0xFFFFFF)
            return Meta(tick, META_TEMPO, byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))
        }
    }
}

val MidiEvent.Meta.tempoMicros: Int?
    get() = if (type == MidiEvent.META_TEMPO && data.size == 3) {
        ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
    } else null

/** Time signature as (numerator, denominator), e.g. 6/8 -> (6, 8). */
val MidiEvent.Meta.timeSignature: Pair<Int, Int>?
    get() = if (type == MidiEvent.META_TIME_SIGNATURE && data.size >= 2) {
        (data[0].toInt() and 0xFF) to (1 shl (data[1].toInt() and 0xFF))
    } else null

fun MidiFile.allEvents(): Sequence<MidiEvent> = tracks.asSequence().flatMap { it.events.asSequence() }
