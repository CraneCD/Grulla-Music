package com.grulla.music.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackWindowTest {
    @Test
    fun `maps player time to score time at the notated tempo`() {
        val window = PlaybackWindow(scoreStartMs = 0.0, tempoFactor = 1.0)
        assertEquals(1234.0, window.scoreTimeAt(1234.0))
    }

    @Test
    fun `slower tempo stretches player time`() {
        val window = PlaybackWindow(scoreStartMs = 10_000.0, tempoFactor = 0.5)
        assertEquals(10_000.0, window.scoreTimeAt(0.0))
        assertEquals(10_500.0, window.scoreTimeAt(1000.0))
        assertEquals(2000.0, window.playerTimeFor(11_000.0))
    }

    @Test
    fun `faster tempo compresses player time`() {
        val window = PlaybackWindow(scoreStartMs = 0.0, tempoFactor = 1.5)
        assertEquals(1500.0, window.scoreTimeAt(1000.0))
    }

    @Test
    fun `count-in precedes the start point`() {
        val window = PlaybackWindow(scoreStartMs = 4000.0, tempoFactor = 0.8, leadInMs = 2500.0)
        assertTrue(window.isCountingIn(2499.0))
        assertFalse(window.isCountingIn(2500.0))
        assertTrue(window.scoreTimeAt(0.0) < 4000.0)
        assertEquals(4000.0, window.scoreTimeAt(2500.0))
        assertEquals(4800.0, window.scoreTimeAt(3500.0), 1e-9)
    }

    @Test
    fun `scoreTimeAt and playerTimeFor are inverse`() {
        val window = PlaybackWindow(scoreStartMs = 1234.0, tempoFactor = 0.73, leadInMs = 987.0)
        for (player in listOf(0.0, 500.0, 987.0, 10_000.0)) {
            assertEquals(player, window.playerTimeFor(window.scoreTimeAt(player)), 1e-9)
        }
    }

    @Test
    fun `tempo range is 50 to 150 percent`() {
        assertEquals(0.5, TempoRange.factorFor(10))
        assertEquals(1.0, TempoRange.factorFor(100))
        assertEquals(1.5, TempoRange.factorFor(400))
        assertEquals(0.75, TempoRange.factorFor(75))
        assertFailsWith<IllegalArgumentException> { PlaybackWindow(0.0, 0.0) }
    }
}
