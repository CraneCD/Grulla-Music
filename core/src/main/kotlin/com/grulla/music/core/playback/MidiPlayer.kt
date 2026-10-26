package com.grulla.music.core.playback

/**
 * Plays a complete Standard MIDI File and reports its position. This is the seam
 * for swapping Android's MediaPlayer for FluidSynth (or anything else) later.
 *
 * All methods except [load] are called on the main thread and must not block.
 */
interface MidiPlayer {
    /** Replaces whatever was loaded with [midi]; the position is reset to 0 and the player is paused. */
    suspend fun load(midi: ByteArray)

    fun start()

    fun pause()

    /** Stops playback and unloads the current file. */
    fun stop()

    /** Current position in milliseconds since the start of the loaded file. */
    val positionMs: Long

    val isPlaying: Boolean

    /** Called (on the main thread) when the loaded file has played to its end. */
    var onCompletion: (() -> Unit)?

    fun release()
}
