package com.grulla.music.core.playback

import com.grulla.music.core.midi.CountIn
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.PlaybackMidiBuilder
import com.grulla.music.core.midi.PreparedMidi
import com.grulla.music.core.timemap.Timemap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Transport { STOPPED, PREPARING, COUNTING_IN, PLAYING, PAUSED }

data class PlaybackState(
    val transport: Transport = Transport.STOPPED,
    /** Current score position in ms at the notated tempo (the timemap's unit). */
    val positionMs: Double = 0.0,
    val durationMs: Double = 0.0,
    val tempoPercent: Int = TempoRange.DEFAULT_PERCENT,
    val countInEnabled: Boolean = false,
    val hasScore: Boolean = false,
) {
    val isRunning: Boolean
        get() = transport == Transport.PREPARING || transport == Transport.COUNTING_IN || transport == Transport.PLAYING
}

/** Receives the guidance the score view should show. Called on the controller's scope. */
interface PlaybackListener {
    /** The set of sounding notes changed; [follow] asks the view to keep it on screen. */
    fun onSegmentChanged(segment: Segment?, follow: Boolean)

    /** Count-in beat [beat] of [total] started; beat 0 means the count-in is over or cancelled. */
    fun onCountInBeat(beat: Int, total: Int)

    fun onPlaybackError(message: String) {}
}

/**
 * The single playback clock. It prepares a MIDI file for the requested start
 * point, tempo and count-in (see [PlaybackMidiBuilder]), plays it with a
 * [MidiPlayer], and on every display frame converts the player position to score
 * time to drive the highlighting through [PlaybackListener].
 *
 * Not thread-safe: call it from one thread (the main thread on Android), and give
 * it a [scope] that runs there.
 */
class PlaybackController(
    private val player: MidiPlayer,
    private val scope: CoroutineScope,
    /** Suspends until the next display frame and returns its time in nanoseconds (monotonic). */
    private val awaitFrameNanos: suspend () -> Long,
    private val listener: PlaybackListener,
    /** Where MIDI files are built; CPU work that should stay off the main thread. */
    private val workContext: CoroutineContext = Dispatchers.Default,
    private val countIn: CountIn = CountIn(),
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var timemap: Timemap? = null
    private var source: MidiFile? = null
    private var tracker: HighlightTracker? = null
    private val smoother = PositionSmoother()

    /** Score position where the next play() starts when nothing is resumable. */
    private var cursorMs = 0.0

    /** The file the player has loaded, and whether it is paused at [cursorMs] ready to resume. */
    private var prepared: PreparedMidi? = null
    private var resumable = false

    /** Start point and count-in of the run being prepared or played. */
    private var runStartMs = 0.0
    private var runCountIn = false

    private var job: Job? = null
    private var countInBeat = 0

    init {
        player.onCompletion = { onPlayerCompleted() }
    }

    private val transport: Transport get() = mutableState.value.transport

    fun setScore(timemap: Timemap, midi: MidiFile) {
        stop()
        this.timemap = timemap
        source = midi
        tracker = HighlightTracker(ActiveNoteIndex(timemap))
        mutableState.update { it.copy(durationMs = timemap.durationMs, positionMs = 0.0, hasScore = true) }
    }

    fun clearScore() {
        stop()
        timemap = null
        source = null
        tracker = null
        mutableState.update { it.copy(durationMs = 0.0, positionMs = 0.0, hasScore = false) }
    }

    fun togglePlay() = if (state.value.isRunning) pause() else play()

    fun play() {
        if (source == null || state.value.isRunning) return
        if (resumable && prepared != null && !state.value.countInEnabled) {
            smoother.reset()
            player.start()
            setTransport(Transport.PLAYING)
            job = scope.launch { tickLoop(prepared!!) }
        } else {
            startRun(cursorMs, withCountIn = state.value.countInEnabled)
        }
    }

    fun pause() {
        when (transport) {
            Transport.PREPARING -> {
                job?.cancel()
                cursorMs = runStartMs
                resumable = false
            }
            Transport.COUNTING_IN, Transport.PLAYING -> {
                job?.cancel()
                player.pause()
                if (transport == Transport.COUNTING_IN) {
                    cursorMs = runStartMs
                    resumable = false
                } else {
                    cursorMs = currentScoreMs()
                    resumable = true
                }
            }
            else -> return
        }
        showCountIn(0, 0)
        setTransport(Transport.PAUSED)
        publishPosition(cursorMs, force = true)
    }

    fun stop() {
        job?.cancel()
        job = null
        if (prepared != null || transport != Transport.STOPPED) player.stop()
        prepared = null
        resumable = false
        cursorMs = 0.0
        tracker?.reset()
        showCountIn(0, 0)
        listener.onSegmentChanged(null, false)
        setTransport(Transport.STOPPED)
        publishPosition(0.0, force = true)
    }

    /** Moves to [scoreMs]. While playing, playback continues from there without a count-in. */
    fun seekTo(scoreMs: Double) {
        val map = timemap ?: return
        val target = scoreMs.coerceIn(0.0, map.durationMs)
        if (state.value.isRunning) {
            startRun(target, withCountIn = false)
            return
        }
        cursorMs = target
        resumable = false
        tracker?.reset()
        tracker?.update(target)?.let { listener.onSegmentChanged(it.segment, true) }
        publishPosition(target, force = true)
    }

    fun seekToMeasure(measureId: String): Boolean {
        val time = timemap?.measureStartMs(measureId) ?: return false
        seekTo(time)
        return true
    }

    fun setTempoPercent(percent: Int) {
        val clamped = TempoRange.clampPercent(percent)
        if (clamped == state.value.tempoPercent) return
        mutableState.update { it.copy(tempoPercent = clamped) }
        when (transport) {
            Transport.PLAYING -> startRun(currentScoreMs(), withCountIn = false)
            Transport.PREPARING, Transport.COUNTING_IN -> startRun(runStartMs, runCountIn)
            else -> resumable = false
        }
    }

    fun setCountInEnabled(enabled: Boolean) {
        mutableState.update { it.copy(countInEnabled = enabled) }
    }

    fun release() {
        job?.cancel()
        player.onCompletion = null
        player.release()
    }

    private fun startRun(scoreMs: Double, withCountIn: Boolean) {
        val midi = source ?: return
        job?.cancel()
        tracker?.reset()
        showCountIn(0, 0)
        cursorMs = scoreMs
        resumable = false
        runStartMs = scoreMs
        runCountIn = withCountIn
        setTransport(Transport.PREPARING)
        publishPosition(scoreMs, force = true)
        val factor = TempoRange.factorFor(state.value.tempoPercent)
        job = scope.launch {
            val run = try {
                val built = withContext(workContext) {
                    PlaybackMidiBuilder.build(midi, scoreMs, factor, countIn.takeIf { withCountIn })
                }
                player.load(built.bytes)
                built
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                prepared = null
                setTransport(Transport.STOPPED)
                listener.onPlaybackError("Playback failed: ${e.message ?: e::class.simpleName}")
                return@launch
            }
            prepared = run
            smoother.reset()
            // Show where the music will start, also during the count-in.
            tracker?.update(run.window.scoreStartMs)?.let { listener.onSegmentChanged(it.segment, true) }
            player.start()
            setTransport(if (run.countInBeats > 0) Transport.COUNTING_IN else Transport.PLAYING)
            tickLoop(run)
        }
    }

    private suspend fun tickLoop(run: PreparedMidi) {
        while (true) {
            onFrame(run, awaitFrameNanos())
        }
    }

    private fun onFrame(run: PreparedMidi, nowNanos: Long) {
        val playerMs = smoother.estimate(player.positionMs, nowNanos, playing = true)
        val window = run.window
        if (run.countInBeats > 0 && window.isCountingIn(playerMs)) {
            val beatMs = window.leadInMs / run.countInBeats
            val beat = (playerMs / beatMs).toInt().coerceIn(0, run.countInBeats - 1) + 1
            showCountIn(beat, run.countInBeats)
            return
        }
        showCountIn(0, run.countInBeats)
        if (transport == Transport.COUNTING_IN) setTransport(Transport.PLAYING)
        val scoreMs = window.scoreTimeAt(playerMs)
        tracker?.update(scoreMs)?.let { listener.onSegmentChanged(it.segment, true) }
        publishPosition(scoreMs.coerceAtMost(state.value.durationMs))
    }

    private fun onPlayerCompleted() {
        if (transport == Transport.PLAYING || transport == Transport.COUNTING_IN) stop()
    }

    private fun currentScoreMs(): Double {
        val run = prepared ?: return cursorMs
        val score = run.window.scoreTimeAt(player.positionMs.toDouble())
        return score.coerceIn(run.window.scoreStartMs, state.value.durationMs.coerceAtLeast(run.window.scoreStartMs))
    }

    private fun showCountIn(beat: Int, total: Int) {
        if (beat == countInBeat) return
        countInBeat = beat
        listener.onCountInBeat(beat, total)
    }

    private fun setTransport(value: Transport) {
        mutableState.update { it.copy(transport = value) }
    }

    private fun publishPosition(scoreMs: Double, force: Boolean = false) {
        val current = state.value.positionMs
        // Position is for display; ~20 updates per second is plenty.
        if (force || kotlin.math.abs(scoreMs - current) >= POSITION_STEP_MS) {
            mutableState.update { it.copy(positionMs = scoreMs) }
        }
    }

    private companion object {
        const val POSITION_STEP_MS = 50.0
    }
}
