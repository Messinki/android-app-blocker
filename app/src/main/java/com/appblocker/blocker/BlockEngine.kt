package com.appblocker.blocker

import com.appblocker.data.Block

/** What the accessibility service should do, given what is on screen right now. */
sealed interface Decision {

    /** Nothing locked is on screen (or everything visible is inside an unlock session). */
    data object None : Decision

    /**
     * A locked app is on screen: show (or keep showing) the hold screen.
     * The button must be held for [holdMillis]; completing it unlocks every block in [blockIds].
     */
    data class ShowHold(val holdMillis: Long, val blockIds: Set<String>) : Decision

    /**
     * The unlock session of [blockIds] ran out while the user was using one of its apps.
     * Send them to the home screen (rather than dropping the hold screen on them mid-use).
     */
    data class GoHome(val blockIds: Set<String>) : Decision
}

/**
 * The blocking rules, with no Android code in it so it can be unit tested.
 *
 * Times are "elapsed realtime" milliseconds (a clock that keeps running while the
 * phone sleeps), passed in by the caller so tests can control them.
 *
 * Rules:
 * - A visible app that belongs to an enabled block with no running session is locked.
 * - If an app is in several blocks, it is locked if ANY of them is locked.
 *   The hold screen uses the longest hold time and unlocks all of them.
 * - A session is a fixed window starting at unlock: leaving and coming back inside
 *   it needs no new hold. Editing a block mid-session does not change its end time;
 *   new hold/session lengths apply from the next hold.
 * - When a session ends while its app is in use, the answer is [Decision.GoHome].
 *   When it ended while the user was elsewhere, coming back shows the hold screen.
 * - Disabled blocks and blocks no longer in the list never block. A deleted block's
 *   session is forgotten.
 * - A session length of 0 means "no time limit": the session has no end time and
 *   never sends the user home. It ends when the block's apps have been off screen
 *   for [LEAVE_GRACE_MS] (the grace covers fold/unfold relaunches and quick hops).
 */
class BlockEngine {

    /** blockId -> moment (elapsed realtime ms) its unlock session ends. */
    private val unlockedUntil = HashMap<String, Long>()

    /**
     * Blocks whose running session has no time limit. While their apps are on screen
     * their end time is [OPEN_ENDED]; once the apps leave, it becomes "now + grace".
     */
    private val openEnded = HashSet<String>()

    /**
     * Blocks whose apps were on screen AND usable (unlocked, no hold screen) at the
     * last [evaluate]. This tells "the session ran out while you were using the app"
     * (go home) apart from "you came back after it ran out" (hold screen).
     */
    private var inUseBlockIds: Set<String> = emptySet()

    /**
     * Decide what to do. Call this whenever the visible apps or the block list change,
     * and when a session is due to end (see [nextExpiryMs]).
     *
     * @param visiblePackages every app package currently on screen (split screen,
     *   pop-up windows...), excluding our own app.
     */
    fun evaluate(blocks: List<Block>, visiblePackages: Set<String>, nowMs: Long): Decision {
        // Forget sessions of blocks that were deleted.
        val existingIds = blocks.mapTo(HashSet()) { it.id }
        unlockedUntil.keys.retainAll(existingIds)

        val unlocked = HashSet<String>()
        val expiredWhileInUse = HashSet<String>()
        val locked = ArrayList<Block>()

        for (block in blocks) {
            val visible = block.enabled && block.packages.any { it in visiblePackages }
            if (block.id in openEnded) {
                val current = unlockedUntil.getValue(block.id)
                unlockedUntil[block.id] =
                    if (visible) OPEN_ENDED else minOf(current, nowMs + LEAVE_GRACE_MS)
            }
            if (!visible) continue

            val until = unlockedUntil[block.id]
            when {
                until != null && nowMs < until -> unlocked += block.id
                until != null && block.id in inUseBlockIds -> expiredWhileInUse += block.id
                else -> locked += block
            }
        }

        // Once a session is over it's over, whether or not its apps are visible.
        unlockedUntil.entries.removeAll { it.value <= nowMs }
        openEnded.retainAll(unlockedUntil.keys)

        if (expiredWhileInUse.isNotEmpty()) {
            inUseBlockIds = emptySet()
            return Decision.GoHome(expiredWhileInUse)
        }
        if (locked.isNotEmpty()) {
            // The hold screen covers the whole display, so nothing is "in use" under it.
            inUseBlockIds = emptySet()
            return Decision.ShowHold(
                holdMillis = locked.maxOf { holdMillisFor(it) },
                blockIds = locked.mapTo(HashSet()) { it.id },
            )
        }
        inUseBlockIds = unlocked
        return Decision.None
    }

    /** The hold for [blockIds] was completed: start a session for each (enabled, existing) block. */
    fun onHoldCompleted(blockIds: Set<String>, blocks: List<Block>, nowMs: Long) {
        for (block in blocks) {
            if (block.id !in blockIds || !block.enabled) continue
            if (hasNoTimeLimit(block)) {
                unlockedUntil[block.id] = OPEN_ENDED
                openEnded += block.id
            } else {
                unlockedUntil[block.id] = nowMs + sessionMillisFor(block)
                openEnded -= block.id
            }
        }
    }

    /**
     * When the next session ends (elapsed realtime ms), or null if none is running.
     * The service re-evaluates at that moment so it can send the user home on time.
     * Sessions with no time limit only count once their apps have left the screen.
     */
    fun nextExpiryMs(): Long? = unlockedUntil.values.filter { it != OPEN_ENDED }.minOrNull()

    companion object {
        /** End time of a no-time-limit session while its apps are on screen. */
        private const val OPEN_ENDED = Long.MAX_VALUE

        /** How long a no-time-limit session survives with its apps off screen. */
        const val LEAVE_GRACE_MS = 3_000L

        /** Session length 0 = no time limit (negative values are treated as bad input). */
        fun hasNoTimeLimit(block: Block): Boolean = block.sessionMinutes == 0

        // At least 1 second / 1 minute, so a bad value can't make a block meaningless.
        fun holdMillisFor(block: Block): Long = block.holdSeconds.coerceAtLeast(1) * 1_000L
        fun sessionMillisFor(block: Block): Long = block.sessionMinutes.coerceAtLeast(1) * 60_000L
    }
}
