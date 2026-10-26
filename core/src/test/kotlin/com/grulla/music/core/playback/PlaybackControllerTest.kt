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
    private class FakePlayer(private val now: () -> Long) : MidiPlayer {
        var loads = 0
        var loaded: ByteArray? = null
        private var startedAt: Long? = null
        private var offset = 0L
        override var onCompletion: (() -> Unit)? = null

        override suspend fun load(midi: ByteArray) {
            delay(LOAD_MS)
            loads++
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

        override val positionMs: Long get() = offset + (startedAt?.let { now() - it } ?: 0)
        override val isPlaying: Boolean get() = startedAt != null
        override fun release() = Unit
    }

    private class RecordingListener : PlaybackListener {
        val segments = mutableListOf<Segment?>()
        val beats = mutableListOf<Int>()
        val errors = mutableListOf<String>()
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

    private fun TestScope.harness(): Harness {
        val player = FakePlayer { testScheduler.currentTime }
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

    private companion object {
        const val LOAD_MS = 50L
        const val FRAME_MS = 16L
    }
}
