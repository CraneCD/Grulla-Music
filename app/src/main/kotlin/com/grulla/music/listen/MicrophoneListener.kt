package com.grulla.music.listen

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.grulla.music.core.listen.NoteEvent
import com.grulla.music.core.listen.NoteEventDetector
import com.grulla.music.core.listen.PitchEstimate
import com.grulla.music.core.listen.YinPitchDetector

/**
 * Captures the microphone on a background thread and turns it into note events
 * (YIN pitch detection every ~12 ms). Callbacks arrive on the main thread.
 */
class MicrophoneListener(
    private val onNote: (NoteEvent) -> Unit,
    /** Latest analysis, about ten times per second, for a live "hearing" display. */
    private val onLevel: (PitchEstimate) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Returns false if the microphone cannot be opened. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(context: Context): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) return false
        val record = AudioRecord(
            audioSource(context),
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuffer, HOP * 4 * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread({ analyse(record) }, "grulla-listen").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    /** The least processed source available: no automatic gain or noise suppression to fight the detector. */
    private fun audioSource(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun analyse(record: AudioRecord) {
        val detector = YinPitchDetector(SAMPLE_RATE)
        val notes = NoteEventDetector()
        val window = FloatArray(detector.requiredSamples)
        val chunk = FloatArray(HOP)
        var filled = 0
        var samples = 0L
        var lastLevelMs = Double.NEGATIVE_INFINITY
        try {
            record.startRecording()
            while (running) {
                val read = record.read(chunk, 0, HOP, AudioRecord.READ_BLOCKING)
                if (read < 0) break
                if (read == 0) continue
                // Slide the analysis window and append the new samples.
                System.arraycopy(window, read, window, 0, window.size - read)
                System.arraycopy(chunk, 0, window, window.size - read, read)
                filled = minOf(window.size, filled + read)
                samples += read
                if (filled < window.size) continue
                val estimate = detector.detect(window)
                val timeMs = samples * 1000.0 / SAMPLE_RATE
                notes.process(estimate, timeMs)?.let { event -> main.post { if (running) onNote(event) } }
                if (timeMs - lastLevelMs >= LEVEL_INTERVAL_MS) {
                    lastLevelMs = timeMs
                    main.post { if (running) onLevel(estimate) }
                }
            }
        } catch (_: IllegalStateException) {
            // The microphone was taken by another app or the recording could not start.
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val HOP = 512
        const val LEVEL_INTERVAL_MS = 100.0
    }
}
