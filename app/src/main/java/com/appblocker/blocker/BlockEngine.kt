package com.appblocker.blocker

import com.appblocker.data.Block

/**
 * One app's unlock under one block. Sessions are tracked per app, so unlocking
 * Instagram does not unlock the other apps in the same block.
 */
data class AppUnlock(val blockId: String, val packageName: String)

/** What the accessibility service should do, given what is on screen right now. */
sealed interface Decision {

    /** Nothing locked is on screen (or every visible blocked app is inside an unlock session). */
    data object None : Decision

    /**
     * A locked app is on screen: show (or keep showing) the hold screen.
     * The button must be held for [holdMillis]; completing it starts every session in [unlocks].
     */
    data class ShowHold(val holdMillis: Long, val unlocks: Set<AppUnlock>) : Decision

    /**
     * The sessions in [unlocks] ran out while the user was using that app.
     * Send them to the home screen (rather than dropping the hold screen on them mid-use).
     */
    data class GoHome(val unlocks: Set<AppUnlock>) : Decision
}

/**
 * The blocking rules, with no Android code in it so it can be unit tested.
 *
 * Times are "elapsed realtime" milliseconds (a clock that keeps running while the
 * phone sleeps), passed in by the caller so tests can control them.
 *
 * Rules:
 * - A block only sets the rule (hold time, session length) for its apps. Every app is
 *   unlocked on its own: holding to open Instagram leaves the block's other apps locked.
 * - A visible app that belongs to an enabled block, with no running session for that
 *   app under that block, is locked.
 * - If an app is in several blocks, it is locked if ANY of them is locked for it.
 *   The hold screen uses the longest hold time and unlocks the app under all of them.
 * - Several locked apps on screen at once (split screen): one hold unlocks all of them.
 * - A session is a fixed window starting at unlock: leaving and coming back inside
 *   it needs no new hold. Editing a block mid-session does not change its end time;
 *   new hold/session lengths apply from the next hold.
 * - When a session ends while its app is in use, the answer is [Decision.GoHome].
 *   When it ended while the user was elsewhere, coming back shows the hold screen.
 * - Disabled blocks and blocks no longer in the list never block. Sessions of deleted
 *   blocks, and of apps removed from a block, are forgotten.
 * - A session length of 0 means "no time limit": the session has no end time and
 *   never sends the user home. It ends when the app has been off screen for
 *   [LEAVE_GRACE_MS] (the grace covers fold/unfold relaunches and quick hops).
 */
class BlockEngine {

    /** app session -> moment (elapsed realtime ms) it ends. */
    private val unlockedUntil = HashMap<AppUnlock, Long>()

    /**
     * Sessions with no time limit. While their app is on screen their end time is
     * [OPEN_ENDED]; once the app leaves, it becomes "now + grace".
     */
    private val openEnded = HashSet<AppUnlock>()

    /**
     * Sessions whose app was on screen AND usable (unlocked, no hold screen) at the
     * last [evaluate]. This tells "the session ran out while you were using the app"
     * (go home) apart from "you came back after it ran out" (hold screen).
     */
    private var inUse: Set<AppUnlock> = emptySet()

    /**
     * Decide what to do. Call this whenever the visible apps or the block list change,
     * and when a session is due to end (see [nextExpiryMs]).
     *
     * @param visiblePackages every app package currently on screen (split screen,
     *   pop-up windows...), excluding our own app.
     */
    fun evaluate(blocks: List<Block>, visiblePackages: Set<String>, nowMs: Long): Decision {
        // Forget sessions of deleted blocks and of apps taken out of their block.
        val blocksById = blocks.associateBy { it.id }
        unlockedUntil.keys.retainAll { blocksById[it.blockId]?.packages?.contains(it.packageName) == true }
        openEnded.retainAll(unlockedUntil.keys)

        // No-time-limit sessions: no end while the app is on screen, grace once it leaves.
        for (key in openEnded) {
            val visible = blocksById.getValue(key.blockId).enabled && key.packageName in visiblePackages
            val current = unlockedUntil.getValue(key)
            unlockedUntil[key] = if (visible) OPEN_ENDED else minOf(current, nowMs + LEAVE_GRACE_MS)
        }

        val unlocked = HashSet<AppUnlock>()
        val expiredWhileInUse = HashSet<AppUnlock>()
        val locked = HashSet<AppUnlock>()
        var holdMillis = 0L

        for (block in blocks) {
            if (!block.enabled) continue
            for (pkg in block.packages) {
                if (pkg !in visiblePackages) continue
                val key = AppUnlock(block.id, pkg)
                val until = unlockedUntil[key]
                when {
                    until != null && nowMs < until -> unlocked += key
                    until != null && key in inUse -> expiredWhileInUse += key
                    else -> {
                        locked += key
                        holdMillis = maxOf(holdMillis, holdMillisFor(block))
                    }
                }
            }
        }

        // Once a session is over it's over, whether or not its app is visible.
        unlockedUntil.entries.removeAll { it.value <= nowMs }
        openEnded.retainAll(unlockedUntil.keys)

        if (expiredWhileInUse.isNotEmpty()) {
            inUse = emptySet()
            return Decision.GoHome(expiredWhileInUse)
        }
        if (locked.isNotEmpty()) {
            // The hold screen covers the whole display, so nothing is "in use" under it.
            inUse = emptySet()
            return Decision.ShowHold(holdMillis, locked)
        }
        inUse = unlocked
        return Decision.None
    }

    /**
     * The hold for [unlocks] was completed: start each session, if its block still
     * exists, is enabled and still contains that app.
     */
    fun onHoldCompleted(unlocks: Set<AppUnlock>, blocks: List<Block>, nowMs: Long) {
        val blocksById = blocks.associateBy { it.id }
        for (key in unlocks) {
            val block = blocksById[key.blockId] ?: continue
            if (!block.enabled || key.packageName !in block.packages) continue
            if (hasNoTimeLimit(block)) {
                unlockedUntil[key] = OPEN_ENDED
                openEnded += key
            } else {
                unlockedUntil[key] = nowMs + sessionMillisFor(block)
                openEnded -= key
            }
        }
    }

    /**
     * When the next session ends (elapsed realtime ms), or null if none is running.
     * The service re-evaluates at that moment so it can send the user home on time.
     * Sessions with no time limit only count once their app has left the screen.
     */
    fun nextExpiryMs(): Long? = unlockedUntil.values.filter { it != OPEN_ENDED }.minOrNull()

    companion object {
        /** End time of a no-time-limit session while its app is on screen. */
        private const val OPEN_ENDED = Long.MAX_VALUE

        /** How long a no-time-limit session survives with its app off screen. */
        const val LEAVE_GRACE_MS = 3_000L

        /** Session length 0 = no time limit (negative values are treated as bad input). */
        fun hasNoTimeLimit(block: Block): Boolean = block.sessionMinutes == 0

        // At least 1 second / 1 minute, so a bad value can't make a block meaningless.
        fun holdMillisFor(block: Block): Long = block.holdSeconds.coerceAtLeast(1) * 1_000L
        fun sessionMillisFor(block: Block): Long = block.sessionMinutes.coerceAtLeast(1) * 60_000L
    }
}
