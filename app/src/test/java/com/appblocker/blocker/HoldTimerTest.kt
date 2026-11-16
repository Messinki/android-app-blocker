package com.appblocker.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTimerTest {

    @Test
    fun press_returnsFullDuration_andCompletesExactlyOnce() {
        val timer = HoldTimer(120_000)
        assertEquals(120_000L, timer.press(1_000))
        assertTrue(timer.isPressed)

        assertFalse(timer.tryComplete(120_999))
        assertTrue(timer.tryComplete(121_000))
        assertTrue(timer.isCompleted)
        assertFalse(timer.isPressed)

        assertFalse(timer.tryComplete(121_001))
        assertFalse(timer.tryComplete(500_000))
    }

    @Test
    fun release_resetsCountToZero() {
        val timer = HoldTimer(120_000)
        timer.press(0)
        timer.release()
        assertFalse(timer.isPressed)
        assertFalse(timer.tryComplete(120_000))

        // A new press counts from its own start, not the old one.
        assertEquals(120_000L, timer.press(130_000))
        assertFalse(timer.tryComplete(249_999))
        assertTrue(timer.tryComplete(250_000))
    }

    @Test
    fun pressingAgainWhileHeld_doesNotRestartCount() {
        val timer = HoldTimer(120_000)
        timer.press(0)
        assertNull(timer.press(60_000))
        assertTrue(timer.tryComplete(120_000))
    }

    @Test
    fun remainingMillis_tracksPress() {
        val timer = HoldTimer(120_000)
        assertNull(timer.remainingMillis(0))
        timer.press(10_000)
        assertEquals(60_000L, timer.remainingMillis(70_000))
        assertEquals(0L, timer.remainingMillis(500_000))
        timer.release()
        assertNull(timer.remainingMillis(80_000))
    }

    @Test
    fun afterCompletion_pressesIgnoredUntilReset() {
        val timer = HoldTimer(1_000)
        timer.press(0)
        assertTrue(timer.tryComplete(1_000))
        assertNull(timer.press(2_000))
        timer.release() // harmless after completion
        assertFalse(timer.tryComplete(10_000))

        timer.reset()
        assertFalse(timer.isCompleted)
        assertEquals(1_000L, timer.press(20_000))
        assertTrue(timer.tryComplete(21_000))
    }

    @Test
    fun changingDurationWhileHeld_keepsPressStart() {
        val timer = HoldTimer(120_000)
        timer.press(0)
        timer.requiredMillis = 300_000
        assertFalse(timer.tryComplete(120_000))
        assertEquals(180_000L, timer.remainingMillis(120_000))
        assertTrue(timer.tryComplete(300_000))
    }

    @Test
    fun tryComplete_withoutPress_isFalse() {
        val timer = HoldTimer(0)
        assertFalse(timer.tryComplete(1_000))
    }

    @Test
    fun zeroDuration_completesImmediately_andNegativeIsClamped() {
        val timer = HoldTimer(-5)
        assertEquals(0L, timer.requiredMillis)
        assertEquals(0L, timer.press(5))
        assertTrue(timer.tryComplete(5))
    }
}
