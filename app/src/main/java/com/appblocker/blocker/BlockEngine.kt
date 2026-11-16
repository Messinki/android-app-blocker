package com.appblocker.blocker

import com.appblocker.data.Block

/** What the accessibility service should do, given what is on screen right now. */
sealed interface Decision {

    /** No locked app is on screen. */
    data object None : Decision

    /**
     * A locked app is on screen: show (or keep showing) the hold screen.
     * The button must be held for [holdMillis]; completing it unlocks [packages].
     */
    data class ShowHold(val holdMillis: Long, val packages: Set<String>) : Decision

    /**
     * The session ran out while the user was using an unlocked app.
     * Send them to the home screen (rather than dropping the hold screen on them mid-use).
     */
    data object GoHome : Decision
}

/**
 * The blocking rules, with no Android code in it so it can be unit tested.
 *
 * Times are "elapsed realtime" milliseconds (a clock that keeps running while the
 * phone sleeps), passed in by the caller so tests can control them.
 *
 * Rules:
 * - Blocks only set rules (hold time, session length) for their apps.
 * - Any visible app in an enabled block is locked unless it is part of the session.
 * - There is at most ONE session. Completing a hold starts a new one (replacing any
 *   old one) that unlocks every blocked app on screen at that moment: usually one app,
 *   two in split screen.
 * - An app drops out of the session once it has been off screen for [LEAVE_GRACE_MS]
 *   (the grace covers fold/unfold relaunches and quick hops). Coming back later needs
 *   a new hold. The session is over once it has no apps left.
 * - Session length is the most time the session may last. When it runs out while an
 *   unlocked app is in use, the answer is [Decision.GoHome]. It is fixed at unlock:
 *   editing a block later applies from the next hold.
 * - Hold time is the longest of the hold times of the blocks involved. Session
 *   length is the shortest non-zero length of those blocks; 0 = no time limit.
 */
class BlockEngine {

    private class Session(
        /** Elapsed realtime ms at which the session ends, or null for no time limit. */
        val endsAtMs: Long?,
        /** Unlocked app -> when it left the screen (null while it is on screen). */
        val apps: HashMap<String, Long?>,
    )

    private var session: Session? = null

    /**
     * True if an unlocked app was on screen and usable (no hold screen) at the last
     * [evaluate]. Tells "the session ran out while you were using the app" (go home)
     * apart from "the hold screen was already up" (keep it up).
     */
    private var wasInUse = false

    /**
     * Decide what to do. Call this whenever the visible apps or the block list change,
     * and when the session is due to end (see [nextExpiryMs]).
     *
     * @param visiblePackages every app package currently on screen (split screen,
     *   pop-up windows...), excluding our own app and the home screen.
     */
    fun evaluate(blocks: List<Block>, visiblePackages: Set<String>, nowMs: Long): Decision {
        val visibleBlocked = blocks
            .filter { it.enabled }
            .flatMapTo(HashSet()) { it.packages }
            .apply { retainAll(visiblePackages) }

        session?.let { s ->
            trackLeaving(s, visiblePackages, nowMs)
            val timeUp = s.endsAtMs != null && nowMs >= s.endsAtMs
            if (timeUp || s.apps.isEmpty()) session = null
            if (timeUp && wasInUse && s.apps.keys.any { it in visiblePackages }) {
                wasInUse = false
                return Decision.GoHome
            }
        }

        val unlocked = session?.apps?.keys.orEmpty()
        val locked = visibleBlocked - unlocked
        if (locked.isNotEmpty()) {
            // The hold screen covers the whole display, so nothing is "in use" under it.
            wasInUse = false
            val holdMillis = blocks
                .filter { b -> b.enabled && b.packages.any { it in locked } }
                .maxOf { holdMillisFor(it) }
            return Decision.ShowHold(holdMillis, visibleBlocked)
        }
        wasInUse = visibleBlocked.isNotEmpty()
        return Decision.None
    }

    /** Note when session apps leave or return; drop those gone for the grace period. */
    private fun trackLeaving(s: Session, visiblePackages: Set<String>, nowMs: Long) {
        val it = s.apps.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val leftAt = entry.value
            when {
                leftAt != null && nowMs - leftAt >= LEAVE_GRACE_MS -> it.remove()
                entry.key in visiblePackages -> entry.setValue(null)
                leftAt == null -> entry.setValue(nowMs)
            }
        }
    }

    /** The hold for [packages] was completed: start a new session for them. */
    fun onHoldCompleted(packages: Set<String>, blocks: List<Block>, nowMs: Long) {
        val rules = blocks.filter { b -> b.enabled && b.packages.any { it in packages } }
        val apps = packages.filterTo(HashSet()) { p -> rules.any { p in it.packages } }
        if (apps.isEmpty()) return
        val sessionMillis = rules.filter { it.sessionMinutes != 0 }.minOfOrNull { sessionMillisFor(it) }
        session = Session(
            endsAtMs = sessionMillis?.let { nowMs + it },
            apps = apps.associateWithTo(HashMap()) { null },
        )
    }

    /**
     * When the session ends (elapsed realtime ms), or null if there is no session or it
     * has no time limit. The service re-evaluates then so it can send the user home on time.
     */
    fun nextExpiryMs(): Long? = session?.endsAtMs

    companion object {
        /** How long an unlocked app can be off screen before it locks again. */
        const val LEAVE_GRACE_MS = 3_000L

        // At least 1 second / 1 minute, so a bad value can't make a block meaningless.
        fun holdMillisFor(block: Block): Long = block.holdSeconds.coerceAtLeast(1) * 1_000L
        fun sessionMillisFor(block: Block): Long = block.sessionMinutes.coerceAtLeast(1) * 60_000L
    }
}
