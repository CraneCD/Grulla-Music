package com.grulla.music.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionSmootherTest {
    private fun ms(value: Long) = value * 1_000_000L

    @Test
    fun `extrapolates between coarse reports`() {
        val smoother = PositionSmoother()
        assertEquals(1000.0, smoother.estimate(1000, ms(0)))
        assertEquals(1016.0, smoother.estimate(1000, ms(16)))
        assertEquals(1033.0, smoother.estimate(1000, ms(33)))
        // New report: follows it.
        assertEquals(1040.0, smoother.estimate(1040, ms(40)))
    }

    @Test
    fun `caps extrapolation when the player stops reporting`() {
        val smoother = PositionSmoother(maxExtrapolationMs = 100.0)
        smoother.estimate(0, ms(0))
        assertEquals(100.0, smoother.estimate(0, ms(5_000)))
    }

    @Test
    fun `does not step backwards on small corrections`() {
        val smoother = PositionSmoother()
        smoother.estimate(1000, ms(0))
        assertEquals(1050.0, smoother.estimate(1000, ms(50)))
        // The player reports slightly less than we extrapolated: hold still.
        assertEquals(1050.0, smoother.estimate(1030, ms(51)))
        assertEquals(1060.0, smoother.estimate(1030, ms(81)))
    }

    @Test
    fun `follows a real seek backwards`() {
        val smoother = PositionSmoother(seekThresholdMs = 250.0)
        smoother.estimate(5000, ms(0))
        assertEquals(1000.0, smoother.estimate(1000, ms(16)))
    }

    @Test
    fun `does not extrapolate while paused`() {
        val smoother = PositionSmoother()
        smoother.estimate(2000, ms(0), playing = false)
        assertEquals(2000.0, smoother.estimate(2000, ms(500), playing = false))
    }

    @Test
    fun `reset forgets the previous position`() {
        val smoother = PositionSmoother()
        smoother.estimate(5000, ms(0))
        smoother.reset()
        assertEquals(4900.0, smoother.estimate(4900, ms(10)))
    }
}
