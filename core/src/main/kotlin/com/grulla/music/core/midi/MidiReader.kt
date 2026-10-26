package com.grulla.music.core.midi

class MidiFormatException(message: String) : Exception(message)

/** Decodes a Standard MIDI File (format 0 or 1, PPQ time division). */
object MidiReader {
    fun read(bytes: ByteArray): MidiFile {
        val input = Cursor(bytes)
        if (input.readAscii(4) != "MThd") throw MidiFormatException("Missing MThd header")
        val headerLength = input.readInt32()
        if (headerLength < 6) throw MidiFormatException("MThd header too short")
        val format = input.readUInt16()
        val trackCount = input.readUInt16()
        val division = input.readUInt16()
        if (division and 0x8000 != 0) throw MidiFormatException("SMPTE time division is not supported")
        if (division == 0) throw MidiFormatException("Invalid time division")
        input.skip(headerLength - 6)

        val tracks = ArrayList<MidiTrack>(trackCount)
        while (tracks.size < trackCount && input.remaining >= 8) {
            val chunkType = input.readAscii(4)
            val length = input.readInt32()
            if (length < 0 || length > input.remaining) throw MidiFormatException("Chunk $chunkType overruns the file")
            if (chunkType != "MTrk") {
                input.skip(length) // Unknown chunks must be ignored.
                continue
            }
            tracks += readTrack(Cursor(input.take(length)))
        }
        if (tracks.size != trackCount) throw MidiFormatException("Expected $trackCount tracks, found ${tracks.size}")
        return MidiFile(format, division, tracks)
    }

    private fun readTrack(input: Cursor): MidiTrack {
        val events = ArrayList<MidiEvent>()
        var tick = 0L
        var runningStatus = 0
        while (input.remaining > 0) {
            tick += input.readVarLen()
            var status = input.peekUInt8()
            if (status < 0x80) {
                if (runningStatus == 0) throw MidiFormatException("Data byte without status at tick $tick")
                status = runningStatus
            } else {
                input.readUInt8()
            }
            when {
                status == 0xFF -> {
                    val type = input.readUInt8()
                    val data = input.take(input.readVarLen().toInt())
                    if (type == MidiEvent.META_END_OF_TRACK) break
                    events += MidiEvent.Meta(tick, type, data)
                }
                status == 0xF0 || status == 0xF7 -> {
                    events += MidiEvent.SysEx(tick, status, input.take(input.readVarLen().toInt()))
                    runningStatus = 0
                }
                status in 0x80..0xEF -> {
                    runningStatus = status
                    val data1 = input.readUInt8()
                    val command = status and 0xF0
                    val data2 = if (command == 0xC0 || command == 0xD0) 0 else input.readUInt8()
                    events += MidiEvent.Channel(tick, status, data1, data2)
                }
                else -> throw MidiFormatException("Unsupported status byte 0x${status.toString(16)} at tick $tick")
            }
        }
        return MidiTrack(events)
    }

    private class Cursor(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size - position

        private fun require(n: Int) {
            if (n < 0 || remaining < n) throw MidiFormatException("Unexpected end of data")
        }

        fun peekUInt8(): Int {
            require(1)
            return bytes[position].toInt() and 0xFF
        }

        fun readUInt8(): Int = peekUInt8().also { position++ }

        fun readUInt16(): Int = (readUInt8() shl 8) or readUInt8()

        fun readInt32(): Int = (readUInt16() shl 16) or readUInt16()

        fun readAscii(n: Int): String = String(take(n), Charsets.US_ASCII)

        fun take(n: Int): ByteArray {
            require(n)
            return bytes.copyOfRange(position, position + n).also { position += n }
        }

        fun skip(n: Int) {
            require(n)
            position += n
        }

        fun readVarLen(): Long {
            var value = 0L
            repeat(4) {
                val b = readUInt8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return value
            }
            throw MidiFormatException("Variable-length quantity longer than 4 bytes")
        }
    }
}
