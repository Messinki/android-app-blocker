package com.appblocker.blocker

/**
 * Tracks one press-and-hold of the hold button. Pure Kotlin so it can be unit tested;
 * the caller passes in timestamps (the service uses SystemClock.uptimeMillis, the same
 * clock Handler.postDelayed uses).
 *
 * - [press] starts the count and returns how long until it completes, so the caller
 *   can schedule a single check for that moment instead of polling.
 * - [release] (finger lifted, touch cancelled, finger slid off) sets the count back to zero.
 * - [tryComplete] returns true exactly once, when the button has been held long enough.
 * - [reset] forgets everything, ready for a fresh hold screen.
 */
class HoldTimer(requiredMillis: Long = 0L) {

    /** How long the button must be held. Can change mid-press; the press start is kept. */
    var requiredMillis: Long = requiredMillis.coerceAtLeast(0L)
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    private var pressedAtMs: Long? = null
    private var completed = false

    val isPressed: Boolean get() = pressedAtMs != null
    val isCompleted: Boolean get() = completed

    /**
     * The button went down. Returns the delay until completion, or null if this press
     * is ignored (already pressed, or already completed and not yet [reset]).
     */
    fun press(nowMs: Long): Long? {
        if (completed || pressedAtMs != null) return null
        pressedAtMs = nowMs
        return requiredMillis
    }

    /** The press ended early: the count goes back to zero. */
    fun release() {
        pressedAtMs = null
    }

    /** Time left until completion, or null when not pressed. */
    fun remainingMillis(nowMs: Long): Long? {
        val start = pressedAtMs ?: return null
        return (start + requiredMillis - nowMs).coerceAtLeast(0L)
    }

    /** True exactly once: the first call made while held for at least [requiredMillis]. */
    fun tryComplete(nowMs: Long): Boolean {
        val start = pressedAtMs ?: return false
        if (completed || nowMs - start < requiredMillis) return false
        completed = true
        pressedAtMs = null
        return true
    }

    /** Back to the initial state (not pressed, not completed). */
    fun reset() {
        pressedAtMs = null
        completed = false
    }
}
