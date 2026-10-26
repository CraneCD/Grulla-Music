package com.grulla.music.core.practice

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeTest {
    @Test
    fun `speed trainer goes up a step every few passes and stops at the target`() {
        val trainer = SpeedTrainer(SpeedTrainerSettings(startPercent = 70, targetPercent = 80, stepPercent = 5, repeats = 2))
        assertEquals(70, trainer.tempoPercent)
        assertNull(trainer.onPass())
        assertEquals(75, trainer.onPass())
        assertNull(trainer.onPass())
        assertEquals(80, trainer.onPass())
        assertTrue(trainer.reachedTarget)
        assertNull(trainer.onPass())
        assertEquals(80, trainer.tempoPercent)
    }

    @Test
    fun `trainer settings are kept in range`() {
        val settings = SpeedTrainerSettings(startPercent = 20, targetPercent = 10, stepPercent = 0, repeats = 0).normalized()
        assertEquals(SpeedTrainerSettings(50, 50, 1, 1), settings)
    }

    @Test
    fun `practice time adds up per day and old days are dropped`() {
        val today = LocalDate.of(2026, 10, 26)
        var practice = ScorePractice()
            .withTime(60_000, today.minusDays(200))
            .withTime(30_000, today.minusDays(3))
            .withTime(20_000, today)
            .withTime(10_000, today)
        assertEquals(120_000, practice.totalMs)
        assertEquals(60_000, practice.recentMs(today))
        assertEquals(30_000, practice.recentMs(today, count = 1))
        assertEquals(setOf("2026-10-23", "2026-10-26"), practice.days.keys)
        practice = practice.withLoopPass().withLoopPass()
        assertEquals(2, practice.loopPasses)
    }

    @Test
    fun `trouble bars are the ones with the most wrong notes`() {
        var practice = ScorePractice()
        repeat(8) { practice = practice.withHit(1) }
        repeat(3) { practice = practice.withMiss(5) }
        repeat(1) { practice = practice.withHit(5) }
        repeat(2) { practice = practice.withMiss(9) }
        repeat(6) { practice = practice.withHit(9) }
        practice = practice.withMiss(12) // A single slip is not trouble.
        assertEquals(listOf(5, 9), practice.troubleBars().map { it.bar })
        assertEquals(0.75, practice.troubleBars().first().missRate)
        assertEquals(15.0 / 21, practice.accuracy!!, 1e-9)
    }

    @Test
    fun `the log survives encoding and a damaged file gives an empty log`() {
        val log = PracticeLog().update("a") { it.withHit(3).withTime(5000, LocalDate.of(2026, 1, 2)) }
        assertEquals(log, PracticeLog.decode(log.encode()))
        assertEquals(PracticeLog(), PracticeLog.decode("{not json"))
    }
}
