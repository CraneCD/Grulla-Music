package com.grulla.music.core.playback

import com.grulla.music.core.midi.CountIn
import com.grulla.music.core.midi.Metronome
import com.grulla.music.core.midi.MidiFile
import com.grulla.music.core.midi.PlaybackMidiBuilder
import com.grulla.music.core.midi.PlaybackOptions
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

/** WAITING: paused by [PlaybackController.holdAt] until the player catches up ("Wait for me"). */
enum class Transport { STOPPED, PREPARING, COUNTING_IN, PLAYING, WAITING, PAUSED }

/** A range of score time (ms at the notated tempo), end exclusive. */
data class TimeRange(val startMs: Double, val endMs: Double) {
    init {
        require(endMs > startMs) { "Empty range" }
    }

    operator fun contains(timeMs: Double) = timeMs >= startMs && timeMs < endMs
}

data class PlaybackState(
    val transport: Transport = Transport.STOPPED,
    /** Current score position in ms at the notated tempo (the timemap's unit). */
    val positionMs: Double = 0.0,
    val durationMs: Double = 0.0,
    val tempoPercent: Int = TempoRange.DEFAULT_PERCENT,
    val countInEnabled: Boolean = false,
    val metronomeEnabled: Boolean = false,
    /** The A–B range, if one is set. */
    val loop: TimeRange? = null,
    val loopEnabled: Boolean = false,
    val hasScore: Boolean = false,
) {
    val isRunning: Boolean
        get() = transport == Transport.PREPARING || transport == Transport.COUNTING_IN || transport == Transport.PLAYING ||
            transport == Transport.WAITING

    val activeLoop: TimeRange? get() = loop?.takeIf { loopEnabled }
}

/** Receives the guidance the score view should show. Called on the controller's scope. */
interface PlaybackListener {
    /** The set of sounding notes changed; [follow] asks the view to keep it on screen. */
    fun onSegmentChanged(segment: Segment?, follow: Boolean)

    /** Count-in beat [beat] of [total] started; beat 0 means the count-in is over or cancelled. */
    fun onCountInBeat(beat: Int, total: Int)

    /** Preparing or starting playback failed; [message] is the technical reason. */
    fun onPlaybackError(message: String) {}

    /** Playback went round the A–B loop once more. */
    fun onLoopPass() {}
}

/**
 * The single playback clock. It prepares a MIDI file for the requested start
 * point, tempo, loop, mix, metronome and count-in (see [PlaybackMidiBuilder]),
 * plays it with a [MidiPlayer], and on every display frame converts the player
 * position to score time to drive the highlighting through [PlaybackListener].
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
    private val metronome: Metronome = Metronome(),
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var timemap: Timemap? = null
    private var source: MidiFile? = null
    private var tracker: HighlightTracker? = null
    private var measureStartsQuarters: List<Double> = emptyList()
    private var silencedTracks: Set<Int> = emptySet()
    private var trackVolumes: Map<Int, Int> = emptyMap()
    private var trackPrograms: Map<Int, Int> = emptyMap()
    private val smoother = PositionSmoother()

    /** Score position where the next play() starts when nothing is resumable. */
    private var cursorMs = 0.0

    /** The file the player has loaded, and whether it is paused at [cursorMs] ready to resume. */
    private var prepared: PreparedMidi? = null
    private var resumable = false

    /** Start point and count-in of the run being prepared or played. */
    private var runStartMs = 0.0
    private var runCountIn = false

    /** Whether the current run loops by restarting (players without seamless looping). */
    private var restartAtEnd = false

    private var job: Job? = null
    private var countInBeat = 0

    /** Score time where playback waits (see [holdAt]); null plays on. */
    private var holdMs: Double? = null

    /** Last published score time of the running pass, to notice the loop jumping back. */
    private var lastFrameScoreMs = Double.NaN

    init {
        player.onCompletion = { onPlayerCompleted() }
    }

    private val transport: Transport get() = mutableState.value.transport

    fun setScore(timemap: Timemap, midi: MidiFile) {
        stop()
        this.timemap = timemap
        source = midi
        tracker = HighlightTracker(ActiveNoteIndex(timemap))
        measureStartsQuarters = timemap.measures.map { it.qstamp }
        silencedTracks = emptySet()
        trackVolumes = emptyMap()
        trackPrograms = emptyMap()
        mutableState.update {
            it.copy(durationMs = timemap.durationMs, positionMs = 0.0, hasScore = true, loop = null, loopEnabled = false)
        }
    }

    fun clearScore() {
        stop()
        timemap = null
        source = null
        tracker = null
        mutableState.update { it.copy(durationMs = 0.0, positionMs = 0.0, hasScore = false, loop = null, loopEnabled = false) }
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
            Transport.WAITING -> Unit // The player is already paused at cursorMs, ready to resume.
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
        cursorMs = state.value.activeLoop?.startMs ?: 0.0
        tracker?.reset()
        showCountIn(0, 0)
        listener.onSegmentChanged(null, false)
        setTransport(Transport.STOPPED)
        publishPosition(cursorMs, force = true)
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
        restartIfRunning()
    }

    fun setCountInEnabled(enabled: Boolean) {
        mutableState.update { it.copy(countInEnabled = enabled) }
    }

    fun setMetronomeEnabled(enabled: Boolean) {
        if (enabled == state.value.metronomeEnabled) return
        mutableState.update { it.copy(metronomeEnabled = enabled) }
        restartIfRunning()
    }

    /** Tracks to leave out (from the mixer's mute / solo state). */
    fun setSilencedTracks(tracks: Set<Int>) {
        if (tracks == silencedTracks) return
        silencedTracks = tracks
        restartIfRunning()
    }

    /** Volume (percent) and General MIDI program per track, from the mixer. */
    fun setTrackSound(volumes: Map<Int, Int>, programs: Map<Int, Int>) {
        if (volumes == trackVolumes && programs == trackPrograms) return
        trackVolumes = volumes
        trackPrograms = programs
        restartIfRunning()
    }

    /** Sets (and enables) the A–B loop, or clears it with null. */
    fun setLoop(range: TimeRange?) {
        mutableState.update { it.copy(loop = range, loopEnabled = range != null) }
        onLoopChanged()
    }

    fun setLoopEnabled(enabled: Boolean) {
        if (state.value.loop == null || enabled == state.value.loopEnabled) return
        mutableState.update { it.copy(loopEnabled = enabled) }
        onLoopChanged()
    }

    /**
     * "Wait for me": playback pauses just before [scoreMs] (state [Transport.WAITING])
     * and continues once the hold moves past that point. Null removes the hold.
     */
    fun holdAt(scoreMs: Double?) {
        holdMs = scoreMs
        if (transport == Transport.WAITING && (scoreMs == null || scoreMs - HOLD_LEAD_MS > cursorMs + 1.0)) {
            val run = prepared
            if (run == null || !resumable) {
                startRun(cursorMs, withCountIn = false)
                return
            }
            smoother.reset()
            player.start()
            setTransport(Transport.PLAYING)
            job = scope.launch { tickLoop(run) }
        }
    }

    fun release() {
        job?.cancel()
        player.onCompletion = null
        player.release()
    }

    private fun onLoopChanged() {
        val loop = state.value.activeLoop
        if (state.value.isRunning) {
            restartIfRunning()
        } else if (loop != null && cursorMs !in loop) {
            seekTo(loop.startMs)
        } else {
            resumable = false
        }
    }

    /** Applies changed settings: running playback continues from where it is. */
    private fun restartIfRunning() {
        when (transport) {
            Transport.PLAYING -> startRun(currentScoreMs(), withCountIn = false)
            Transport.WAITING -> startRun(cursorMs, withCountIn = false)
            Transport.PREPARING, Transport.COUNTING_IN -> startRun(runStartMs, runCountIn)
            else -> resumable = false
        }
    }

    private fun startRun(scoreMs: Double, withCountIn: Boolean) {
        val midi = source ?: return
        val loop = state.value.activeLoop
        val start = if (loop != null && scoreMs !in loop) loop.startMs else scoreMs
        job?.cancel()
        tracker?.reset()
        showCountIn(0, 0)
        cursorMs = start
        resumable = false
        runStartMs = start
        runCountIn = withCountIn
        setTransport(Transport.PREPARING)
        publishPosition(start, force = true)
        val options = PlaybackOptions(
            startMs = start,
            endMs = loop?.endMs,
            tempoFactor = TempoRange.factorFor(state.value.tempoPercent),
            countIn = countIn.takeIf { withCountIn },
            metronome = metronome.takeIf { state.value.metronomeEnabled },
            measureStartsQuarters = measureStartsQuarters,
            silencedTracks = silencedTracks,
            trackVolumes = trackVolumes,
            trackPrograms = trackPrograms,
        )
        job = scope.launch {
            val run = try {
                val built = withContext(workContext) { PlaybackMidiBuilder.build(midi, options) }
                player.load(built.bytes)
                built
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                prepared = null
                setTransport(Transport.STOPPED)
                listener.onPlaybackError(e.message ?: e::class.simpleName ?: "error")
                return@launch
            }
            prepared = run
            restartAtEnd = loop != null && !player.supportsLooping
            if (loop != null && player.supportsLooping) {
                // Repeats skip the count-in: the loop starts where the music starts.
                player.setLoop(run.window.leadInMs, run.endPlayerMs)
            }
            smoother.reset()
            // Show where the music will start, also during the count-in.
            tracker?.update(run.window.scoreStartMs)?.let { listener.onSegmentChanged(it.segment, true) }
            player.start()
            setTransport(if (run.countInBeats > 0) Transport.COUNTING_IN else Transport.PLAYING)
            tickLoop(run)
        }
    }

    private suspend fun tickLoop(run: PreparedMidi) {
        lastFrameScoreMs = Double.NaN
        while (onFrame(run, awaitFrameNanos())) Unit
    }

    /** Returns false when ticking should stop (waiting at a hold). */
    private fun onFrame(run: PreparedMidi, nowNanos: Long): Boolean {
        val playerMs = smoother.estimate(player.positionMs, nowNanos, playing = true)
        val window = run.window
        if (run.countInBeats > 0 && window.isCountingIn(playerMs)) {
            val beatMs = window.leadInMs / run.countInBeats
            val beat = (playerMs / beatMs).toInt().coerceIn(0, run.countInBeats - 1) + 1
            showCountIn(beat, run.countInBeats)
            return true
        }
        showCountIn(0, run.countInBeats)
        if (transport == Transport.COUNTING_IN) setTransport(Transport.PLAYING)
        val scoreMs = window.scoreTimeAt(playerMs)
        val hold = holdMs
        if (hold != null && scoreMs >= hold - HOLD_LEAD_MS) {
            // Stop a little early: the synth renders ahead of what is heard.
            player.pause()
            cursorMs = currentScoreMs().coerceAtMost(hold)
            resumable = true
            setTransport(Transport.WAITING)
            publishPosition(cursorMs, force = true)
            return false
        }
        if (state.value.activeLoop != null && scoreMs + LOOP_JUMP_MS < lastFrameScoreMs) listener.onLoopPass()
        lastFrameScoreMs = scoreMs
        tracker?.update(scoreMs)?.let { listener.onSegmentChanged(it.segment, true) }
        publishPosition(scoreMs.coerceAtMost(state.value.durationMs))
        return true
    }

    private fun onPlayerCompleted() {
        if (transport != Transport.PLAYING && transport != Transport.COUNTING_IN) return
        val loop = state.value.activeLoop
        if (restartAtEnd && loop != null) {
            listener.onLoopPass()
            startRun(loop.startMs, withCountIn = false)
        } else {
            stop()
        }
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

        /** How far before a hold playback stops (output latency: notes at the hold must not start). */
        const val HOLD_LEAD_MS = 80.0

        /** A backwards jump this large while looping is the loop starting over. */
        const val LOOP_JUMP_MS = 250.0
    }
}
