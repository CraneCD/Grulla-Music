package com.grulla.music.core.midi

import java.io.ByteArrayOutputStream

/** Encodes a [MidiFile] as a Standard MIDI File (no running status). */
object MidiWriter {
    fun write(file: MidiFile): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeAscii("MThd")
        out.writeInt32(6)
        out.writeUInt16(file.format)
        out.writeUInt16(file.tracks.size)
        out.writeUInt16(file.division)
        for (track in file.tracks) {
            val body = encodeTrack(track)
            out.writeAscii("MTrk")
            out.writeInt32(body.size)
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun encodeTrack(track: MidiTrack): ByteArray {
        val out = ByteArrayOutputStream()
        var lastTick = 0L
        // A stable sort keeps the original order of simultaneous events.
        for (event in track.events.sortedBy { it.tick }) {
            out.writeVarLen(event.tick - lastTick)
            lastTick = event.tick
            when (event) {
                is MidiEvent.Channel -> {
                    out.write(event.status)
                    out.write(event.data1 and 0x7F)
                    if (event.command != 0xC0 && event.command != 0xD0) out.write(event.data2 and 0x7F)
                }
                is MidiEvent.Meta -> {
                    out.write(0xFF)
                    out.write(event.type)
                    out.writeVarLen(event.data.size.toLong())
                    out.write(event.data)
                }
                is MidiEvent.SysEx -> {
                    out.write(event.status)
                    out.writeVarLen(event.data.size.toLong())
                    out.write(event.data)
                }
            }
        }
        out.writeVarLen(0)
        out.write(byteArrayOf(0xFF.toByte(), MidiEvent.META_END_OF_TRACK.toByte(), 0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        writeUInt16(value ushr 16)
        writeUInt16(value and 0xFFFF)
    }

    private fun ByteArrayOutputStream.writeVarLen(value: Long) {
        require(value in 0..0x0FFFFFFF) { "Delta time out of range: $value" }
        var buffer = value and 0x7F
        var rest = value shr 7
        while (rest > 0) {
            buffer = (buffer shl 8) or 0x80 or (rest and 0x7F)
            rest = rest shr 7
        }
        while (true) {
            write((buffer and 0xFF).toInt())
            if (buffer and 0x80 != 0L) buffer = buffer shr 8 else break
        }
    }
}
