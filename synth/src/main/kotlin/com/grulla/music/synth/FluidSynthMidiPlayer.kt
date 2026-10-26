package com.grulla.music.synth

import android.os.Handler
import android.os.Looper
import com.grulla.music.core.midi.MidiEvent
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.MidiReader
import com.grulla.music.core.midi.TempoMap
import com.grulla.music.core.midi.allEvents
import com.grulla.music.core.playback.MidiPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MidiPlayer] rendering through FluidSynth into an AAudio stream. Events are
 * scheduled to the exact sample, loops are gapless, and the position is that of
 * the audio actually being heard (from AAudio's presentation timestamps).
 *
 * Use from the main thread. Create with [create].
 */
class FluidSynthMidiPlayer private constructor(private var handle: Long) : MidiPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    override var onCompletion: (() -> Unit)? = null

    override val supportsLooping: Boolean get() = true

    val sampleRate: Int get() = if (handle != 0L) NativeSynth.nativeSampleRate(handle) else 0

    /** The native side reports completion through a flag; check it a few times per second while playing. */
    private val completionPoll = object : Runnable {
        override fun run() {
            if (handle == 0L || !polling) return
            if (NativeSynth.nativeConsumeCompleted(handle)) {
                polling = false
                onCompletion?.invoke()
                return
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Blocking (decodes the SoundFont); call off the main thread. */
    fun loadSoundFont(path: String): Boolean = handle != 0L && NativeSynth.nativeLoadSoundFont(handle, path)

    override suspend fun load(midi: ByteArray) {
        val events = withContext(Dispatchers.Default) { ScheduledEvents.from(MidiReader.read(midi)) }
        stopPolling()
        if (handle != 0L) NativeSynth.nativeSetSequence(handle, events.timesMs, events.packed, events.endMs)
    }

    override fun start() {
        if (handle == 0L) return
        NativeSynth.nativePlay(handle)
        polling = true
        main.removeCallbacks(completionPoll)
        main.postDelayed(completionPoll, POLL_MS)
    }

    override fun pause() {
        stopPolling()
        if (handle != 0L) NativeSynth.nativePause(handle)
    }

    override fun stop() {
        stopPolling()
        if (handle != 0L) NativeSynth.nativeStop(handle)
    }

    override fun setLoop(startMs: Double, endMs: Double) {
        if (handle != 0L) NativeSynth.nativeSetLoop(handle, startMs, endMs)
    }

    override val positionMs: Long
        get() = if (handle != 0L) NativeSynth.nativePositionMs(handle).toLong() else 0L

    override val isPlaying: Boolean
        get() = handle != 0L && NativeSynth.nativeIsPlaying(handle)

    override fun release() {
        stopPolling()
        if (handle != 0L) {
            NativeSynth.nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun stopPolling() {
        polling = false
        main.removeCallbacks(completionPoll)
    }

    companion object {
        private const val POLL_MS = 50L

        /** Opens the audio output and creates the synthesizer (no SoundFont yet). */
        fun create(): Result<FluidSynthMidiPlayer> {
            val error = arrayOfNulls<String>(1)
            val handle = try {
                NativeSynth.nativeCreate(error)
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError, or ExceptionInInitializerError from NativeSynth's loadLibrary.
                return Result.failure(IllegalStateException("The FluidSynth library could not be loaded", e))
            }
            if (handle == 0L) return Result.failure(IllegalStateException(error[0] ?: "FluidSynth could not start"))
            return Result.success(FluidSynthMidiPlayer(handle))
        }
    }
}

/** The channel messages of a MIDI file as (time, packed message) pairs in play order. */
internal class ScheduledEvents(val timesMs: DoubleArray, val packed: IntArray, val endMs: Double) {
    companion object {
        fun from(file: MidiFile): ScheduledEvents {
            val tempo = TempoMap.of(file)
            val events = file.tracks.flatMapIndexed { trackIndex, track ->
                track.events.filterIsInstance<MidiEvent.Channel>().map { trackIndex to it }
            }
                // Within the same tick, releases go first so a repeated note is not cut by its predecessor's note-off.
                .sortedWith(compareBy({ it.second.tick }, { if (it.second.isNoteOff) 0 else 1 }))
            val times = DoubleArray(events.size)
            val packed = IntArray(events.size)
            events.forEachIndexed { i, (_, e) ->
                times[i] = tempo.tickToMs(e.tick)
                packed[i] = (e.status and 0xFF) or ((e.data1 and 0x7F) shl 8) or ((e.data2 and 0x7F) shl 16)
            }
            val lastTick = file.allEvents().maxOfOrNull { it.tick } ?: 0L
            return ScheduledEvents(times, packed, tempo.tickToMs(lastTick))
        }
    }
}
