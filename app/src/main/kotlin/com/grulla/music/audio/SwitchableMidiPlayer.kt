package com.grulla.music.audio

import com.grulla.music.core.playback.MidiPlayer
import kotlinx.coroutines.CompletableDeferred

/**
 * The [MidiPlayer] the playback controller talks to; forwards to the engine in
 * use (FluidSynth or Android's MediaPlayer) and can switch between them.
 *
 * Loading waits until the audio engine has finished starting up, so pressing
 * play right after launch uses the chosen engine rather than the fallback.
 */
class SwitchableMidiPlayer(initial: MidiPlayer) : MidiPlayer {
    private var current: MidiPlayer = initial
    private val ready = CompletableDeferred<Unit>()

    override var onCompletion: (() -> Unit)? = null

    init {
        attach(initial)
    }

    /** Marks start-up as finished; until then load() waits. */
    fun markReady() {
        ready.complete(Unit)
    }

    /**
     * Switches engines. Whatever was playing stops, and the controller is told
     * through [onCompletion] so its state stays consistent.
     */
    fun use(player: MidiPlayer) {
        if (player === current) return
        val wasPlaying = current.isPlaying
        current.stop()
        current.onCompletion = null
        current = player
        attach(player)
        if (wasPlaying) onCompletion?.invoke()
    }

    private fun attach(player: MidiPlayer) {
        player.onCompletion = { onCompletion?.invoke() }
    }

    override suspend fun load(midi: ByteArray) {
        ready.await()
        current.load(midi)
    }

    override fun start() = current.start()

    override fun pause() = current.pause()

    override fun stop() = current.stop()

    override val positionMs: Long get() = current.positionMs

    override val isPlaying: Boolean get() = current.isPlaying

    override val supportsLooping: Boolean get() = current.supportsLooping

    override fun setLoop(startMs: Double, endMs: Double) = current.setLoop(startMs, endMs)

    /** The engines are shared by the whole app (see AudioEngine); releasing a controller only stops them. */
    override fun release() = current.stop()
}
