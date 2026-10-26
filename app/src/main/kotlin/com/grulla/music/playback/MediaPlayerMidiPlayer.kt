package com.grulla.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.grulla.music.core.playback.MidiPlayer
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MidiPlayer] backed by Android's built-in MIDI synthesizer (Sonivox EAS, reached
 * through MediaPlayer). The sound is basic and the position is only updated once
 * per audio buffer (smoothed by the controller), so this is a stop-gap until the
 * FluidSynth backend.
 *
 * Must be used from the main thread, so that MediaPlayer callbacks arrive there too.
 */
class MediaPlayerMidiPlayer(context: Context) : MidiPlayer {
    private val directory = File(context.cacheDir, "playback")
    private var player: MediaPlayer? = null
    private var fileCounter = 0

    override var onCompletion: (() -> Unit)? = null

    override suspend fun load(midi: ByteArray) {
        releasePlayer()
        val file = withContext(Dispatchers.IO) {
            directory.mkdirs()
            directory.listFiles()?.forEach { it.delete() }
            File(directory, "score-${fileCounter++}.mid").apply { writeBytes(midi) }
        }
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            withContext(Dispatchers.IO) {
                FileInputStream(file).use { mediaPlayer.setDataSource(it.fd) }
                mediaPlayer.prepare()
            }
        } catch (e: Throwable) {
            mediaPlayer.release()
            if (e is CancellationException) throw e
            throw IllegalStateException("Android could not play the generated MIDI file", e)
        }
        mediaPlayer.setOnCompletionListener { onCompletion?.invoke() }
        player = mediaPlayer
    }

    override fun start() {
        player?.start()
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    override fun stop() {
        releasePlayer()
    }

    override val positionMs: Long
        get() = player?.currentPosition?.toLong() ?: 0L

    override val isPlaying: Boolean
        get() = player?.isPlaying == true

    override fun release() {
        releasePlayer()
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun releasePlayer() {
        player?.let {
            it.setOnCompletionListener(null)
            it.release()
        }
        player = null
    }
}
