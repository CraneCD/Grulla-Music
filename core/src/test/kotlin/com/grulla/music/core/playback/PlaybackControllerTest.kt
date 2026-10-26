package com.grulla.music.core.playback

import com.grulla.music.core.Fixtures
import com.grulla.music.core.midi.MidiReader
import com.grulla.music.core.timemap.Timemap
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    private val timemap = Timemap.parse(Fixtures.text("${Fixtures.CHRONO}.timemap.json"))
    private val midi = MidiReader.read(Fixtures.bytes("${Fixtures.CHRONO}.mid"))
    private val index = ActiveNoteIndex(timemap)

    /** Plays in virtual time: its position advances with the test scheduler's clock. */
    private class FakePlayer(private val now: () -> Long, override val supportsLooping: Boolean = false) : MidiPlayer {
        var loads = 0
        var loop: Pair<Double, Double>? = null
        var loaded: ByteArray? = null
        private var startedAt: Long? = null
        private var offset = 0L
        override var onCompletion: (() -> Unit)? = null

        override fun setLoop(startMs: Double, endMs: Double) {
            loop = startMs to endMs
        }

        override suspend fun load(midi: ByteArray) {
            delay(LOAD_MS)
            loads++
            loop = null
            loaded = midi
            offset = 0
            startedAt = null
        }

        override fun start() {
            startedAt = now()
        }

        override fun pause() {
            offset = positionMs
            startedAt = null
        }

        override fun stop() {
            loaded = null
            offset = 0
            startedAt = null
        }

        override val positionMs: Long
            get() {
                val raw = offset + (startedAt?.let { now() - it } ?: 0)
                val (start, end) = loop?.takeIf { supportsLooping } ?: return raw
                return if (raw < end) raw else (start + (raw - start) % (end - start)).toLong()
            }
        override val isPlaying: Boolean get() = startedAt != null
        override fun release() = Unit
    }

    private class RecordingListener : PlaybackListener {
        val segments = mutableListOf<Segment?>()
        val beats = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        var loopPasses = 0
        override fun onLoopPass() {
            loopPasses++
        }
        override fun onSegmentChanged(segment: Segment?, follow: Boolean) {
            segments += segment
        }
        override fun onCountInBeat(beat: Int, total: Int) {
            beats += beat
        }
        override fun onPlaybackError(message: String) {
            errors += message
        }
    }

    private class Harness(val player: FakePlayer, val listener: RecordingListener, val controller: PlaybackController)

    private fun TestScope.harness(looping: Boolean = false): Harness {
        val player = FakePlayer({ testScheduler.currentTime }, looping)
        val listener = RecordingListener()
        val controller = PlaybackController(
            player = player,
            scope = backgroundScope,
            awaitFrameNanos = {
                delay(FRAME_MS)
                testScheduler.currentTime * 1_000_000
            },
            listener = listener,
            workContext = EmptyCoroutineContext,
        )
        controller.setScore(timemap, midi)
        listener.segments.clear() // setScore() stops, which clears the highlight.
        return Harness(player, listener, controller)
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 80.0) {
        assertTrue(abs(expected - actual) <= tolerance, "expected ~$expected, got $actual")
    }

    @Test
    fun `plays from the start and highlights the sounding notes`() = runTest {
        val h = harness()
        h.controller.play()
        assertEquals(Transport.PREPARING, h.controller.state.value.transport)
        advanceTimeBy(LOAD_MS + 1)
        runCurrent()
        assertEquals(Transport.PLAYING, h.controller.state.value.transport)
        assertEquals(0.0, h.listener.segments.first()?.startMs)

        advanceTimeBy(3000)
        val position = h.controller.state.value.positionMs
        assertNear(3000.0, position)
        val last = h.listener.segments.last()!!
        assertEquals(index.segmentAt(position)?.index ?: -1, last.index, "highlight follows the clock")
        val starts = h.listener.segments.map { it!!.startMs }
        assertEquals(starts.sorted(), starts, "segments arrive in order")
        assertTrue(starts.size > 5)
    }

    @Test
    fun `count-in clicks before the music starts`() = runTest {
        val h = harness()
        h.controller.setCountInEnabled(true)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 100)
        assertEquals(Transport.COUNTING_IN, h.controller.state.value.transport)
        // One bar of 4/4 at 87.5 BPM: 4 beats of 685.7 ms.
        advanceTimeBy(4 * 686L + 50)
        assertEquals(Transport.PLAYING, h.controller.state.value.transport)
        assertEquals(listOf(1, 2, 3, 4, 0), h.listener.beats)
        // During the count-in only the start position was shown.
        assertEquals(0.0, h.listener.segments.first()?.startMs)
        assertNear(50.0, h.controller.state.value.positionMs)
    }

    @Test
    fun `pause keeps the position and play resumes without reloading`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 2000)
        h.controller.pause()
        val paused = h.controller.state.value.positionMs
        assertEquals(Transport.PAUSED, h.controller.state.value.transport)
        assertNear(2000.0, paused)
        advanceTimeBy(1000)
        assertEquals(paused, h.controller.state.value.positionMs)

        h.controller.play()
        assertEquals(1, h.player.loads)
        assertEquals(Transport.PLAYING, h.controller.state.value.transport)
        advanceTimeBy(1000)
        assertNear(paused + 1000, h.controller.state.value.positionMs)
    }

    @Test
    fun `changing tempo while playing continues from the same place`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 3000)
        val before = h.controller.state.value.positionMs
        h.controller.setTempoPercent(50)
        assertEquals(50, h.controller.state.value.tempoPercent)
        advanceTimeBy(LOAD_MS + 1)
        assertEquals(2, h.player.loads)
        advanceTimeBy(1000)
        // Half speed: one second of playing covers half a second of score.
        assertNear(before + 500, h.controller.state.value.positionMs)
    }

    @Test
    fun `seeking while stopped shows the measure and plays from it`() = runTest {
        val h = harness()
        h.controller.setCountInEnabled(true)
        val measure = timemap.measures[10]
        assertTrue(h.controller.seekToMeasure(measure.id))
        assertEquals(measure.timeMs, h.controller.state.value.positionMs)
        assertEquals(measure.timeMs, h.listener.segments.last()?.startMs)
        assertEquals(Transport.STOPPED, h.controller.state.value.transport)

        h.controller.setCountInEnabled(false)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 1000)
        assertNear(measure.timeMs + 1000, h.controller.state.value.positionMs)
        assertEquals(false, h.controller.seekToMeasure("no-such-measure"))
    }

    @Test
    fun `seeking while playing restarts there without a count-in`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 1000)
        h.controller.setCountInEnabled(true)
        val target = timemap.measures[20].timeMs
        h.controller.seekTo(target)
        advanceTimeBy(LOAD_MS + 500)
        assertEquals(Transport.PLAYING, h.controller.state.value.transport)
        assertNear(target + 500, h.controller.state.value.positionMs)
        assertTrue(h.listener.beats.isEmpty())
    }

    @Test
    fun `stop clears the highlight and rewinds`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 1500)
        h.controller.stop()
        assertEquals(Transport.STOPPED, h.controller.state.value.transport)
        assertEquals(0.0, h.controller.state.value.positionMs)
        assertNull(h.listener.segments.last())
        assertNull(h.player.loaded)
    }

    @Test
    fun `player completion stops playback`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 500)
        h.player.onCompletion?.invoke()
        assertEquals(Transport.STOPPED, h.controller.state.value.transport)
        assertNull(h.listener.segments.last())
    }

    @Test
    fun `pausing during the count-in resumes with a fresh count-in`() = runTest {
        val h = harness()
        h.controller.setCountInEnabled(true)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 800)
        h.controller.pause()
        assertEquals(Transport.PAUSED, h.controller.state.value.transport)
        assertEquals(listOf(1, 2, 0), h.listener.beats)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 100)
        assertEquals(2, h.player.loads)
        assertEquals(Transport.COUNTING_IN, h.controller.state.value.transport)
    }

    @Test
    fun `a new score stops the old one`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 500)
        h.controller.setScore(timemap, midi)
        assertEquals(Transport.STOPPED, h.controller.state.value.transport)
        assertTrue(h.controller.state.value.hasScore)
    }

    private fun loopOf(first: Int, last: Int): TimeRange {
        val (start, end) = timemap.rangeOfMeasures(timemap.measures[first].id, timemap.measures[last].id)!!
        return TimeRange(start, end)
    }

    @Test
    fun `setting a loop while stopped moves to its start`() = runTest {
        val h = harness()
        val loop = loopOf(4, 6)
        h.controller.setLoop(loop)
        assertTrue(h.controller.state.value.loopEnabled)
        assertEquals(loop.startMs, h.controller.state.value.positionMs)
        h.controller.stop()
        assertEquals(loop.startMs, h.controller.state.value.positionMs, "stop rewinds to the loop start")
    }

    @Test
    fun `a player without seamless looping restarts at A when it reaches B`() = runTest {
        val h = harness()
        val loop = loopOf(4, 6)
        h.controller.setLoop(loop)
        h.controller.setCountInEnabled(true)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 4000)
        assertEquals(1, h.player.loads)
        h.player.onCompletion?.invoke() // the prepared file ends at B
        advanceTimeBy(LOAD_MS + 500)
        assertEquals(2, h.player.loads)
        assertEquals(Transport.PLAYING, h.controller.state.value.transport, "no count-in on repeats")
        assertNear(loop.startMs + 500, h.controller.state.value.positionMs)
    }

    @Test
    fun `loop passes are reported, with and without seamless looping`() = runTest {
        val restarting = harness()
        restarting.controller.setLoop(loopOf(4, 6))
        restarting.controller.play()
        advanceTimeBy(LOAD_MS + 1000)
        restarting.player.onCompletion?.invoke()
        assertEquals(1, restarting.listener.loopPasses)

        val seamless = harness(looping = true)
        val loop = loopOf(4, 6)
        seamless.controller.setLoop(loop)
        seamless.controller.play()
        advanceTimeBy(LOAD_MS + ((loop.endMs - loop.startMs) * 2.5).toLong())
        assertEquals(2, seamless.listener.loopPasses)
    }

    @Test
    fun `wait for me holds playback until the hold moves on`() = runTest {
        val h = harness(looping = true)
        h.controller.holdAt(3000.0)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 4000)
        assertEquals(Transport.WAITING, h.controller.state.value.transport)
        assertTrue(h.controller.state.value.isRunning)
        val waitingAt = h.controller.state.value.positionMs
        assertTrue(waitingAt in 2850.0..3000.0, "waits just before the hold, at $waitingAt")
        advanceTimeBy(1000)
        assertEquals(waitingAt, h.controller.state.value.positionMs, "stays put while waiting")

        h.controller.holdAt(5000.0)
        assertEquals(Transport.PLAYING, h.controller.state.value.transport)
        assertEquals(1, h.player.loads, "resumes without reloading")
        advanceTimeBy(3000)
        assertEquals(Transport.WAITING, h.controller.state.value.transport)
        assertNear(4950.0, h.controller.state.value.positionMs, 100.0)

        h.controller.holdAt(null)
        advanceTimeBy(1000)
        assertEquals(Transport.PLAYING, h.controller.state.value.transport)
        assertNear(5900.0, h.controller.state.value.positionMs, 100.0)
    }

    @Test
    fun `a looping player gets the loop region in player time`() = runTest {
        val h = harness(looping = true)
        val loop = loopOf(10, 11)
        h.controller.setTempoPercent(50)
        h.controller.setLoop(loop)
        h.controller.play()
        advanceTimeBy(LOAD_MS + 1)
        val (start, end) = h.player.loop!!
        assertEquals(0.0, start, 1e-6)
        // Timemap times are whole milliseconds; at half speed the rounding error doubles.
        assertEquals((loop.endMs - loop.startMs) / 0.5, end, 3.0)
    }

    @Test
    fun `playing from outside the loop starts at A`() = runTest {
        val h = harness()
        h.controller.seekTo(timemap.measures[20].timeMs)
        h.controller.setLoop(loopOf(2, 3))
        h.controller.play()
        advanceTimeBy(LOAD_MS + 300)
        assertNear(loopOf(2, 3).startMs + 300, h.controller.state.value.positionMs)
        h.controller.setLoopEnabled(false)
        advanceTimeBy(LOAD_MS + 1)
        assertEquals(2, h.player.loads, "disabling the loop while playing re-prepares")
    }

    @Test
    fun `metronome and mixer changes apply immediately while playing`() = runTest {
        val h = harness()
        h.controller.play()
        advanceTimeBy(LOAD_MS + 1000)
        h.controller.setMetronomeEnabled(true)
        advanceTimeBy(LOAD_MS + 1)
        assertEquals(2, h.player.loads)
        h.controller.setSilencedTracks(setOf(1))
        advanceTimeBy(LOAD_MS + 1)
        assertEquals(3, h.player.loads)
        h.controller.setSilencedTracks(setOf(1))
        advanceTimeBy(LOAD_MS + 1)
        assertEquals(3, h.player.loads, "unchanged settings do not reload")
        assertNear(1000.0 + 2 * LOAD_MS, h.controller.state.value.positionMs, 150.0)
    }

    private companion object {
        const val LOAD_MS = 50L
        const val FRAME_MS = 16L
    }
}
