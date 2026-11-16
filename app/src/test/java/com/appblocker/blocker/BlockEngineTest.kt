package com.appblocker.blocker

import com.appblocker.data.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockEngineTest {

    private val engine = BlockEngine()

    // Default rule: hold 120 s, session 10 min.
    private val social = Block(id = "social", name = "Social", packages = setOf(INSTA, TWITTER))

    // Shares INSTA with `social`, longer hold, shorter session.
    private val evening = Block(
        id = "evening",
        name = "Evening",
        packages = setOf(INSTA, YOUTUBE),
        holdSeconds = 300,
        sessionMinutes = 5,
    )

    private fun eval(blocks: List<Block>, vararg visible: String, at: Long): Decision =
        engine.evaluate(blocks, visible.toSet(), at)

    private fun hold(millis: Long, vararg packages: String) = Decision.ShowHold(millis, packages.toSet())

    private fun unlock(blocks: List<Block>, at: Long, vararg packages: String) =
        engine.onHoldCompleted(packages.toSet(), blocks, at)

    // ---------------------------------------------------------------- basics

    @Test
    fun nothingVisible_isNone() {
        assertEquals(Decision.None, eval(listOf(social), at = 0))
    }

    @Test
    fun unrelatedApp_isNone() {
        assertEquals(Decision.None, eval(listOf(social), OTHER, at = 0))
    }

    @Test
    fun blockedApp_showsHoldWithBlockHoldTime() {
        assertEquals(hold(120_000, INSTA), eval(listOf(social), INSTA, at = 0))
    }

    @Test
    fun disabledBlock_neverBlocks() {
        assertEquals(Decision.None, eval(listOf(social.copy(enabled = false)), INSTA, at = 0))
    }

    @Test
    fun editedHoldTime_appliesToHoldScreenStraightAway() {
        assertEquals(hold(30_000, INSTA), eval(listOf(social.copy(holdSeconds = 30)), INSTA, at = 0))
    }

    @Test
    fun zeroOrNegativeValues_areClampedToMinimums() {
        val odd = social.copy(holdSeconds = 0, sessionMinutes = -5)
        assertEquals(hold(1_000, INSTA), eval(listOf(odd), INSTA, at = 0))
        unlock(listOf(odd), 0, INSTA)
        assertEquals(MIN, engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- unlocking

    @Test
    fun holdCompleted_unlocksOnlyThatApp_notTheRestOfTheBlock() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)

        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(hold(120_000, TWITTER), eval(blocks, TWITTER, at = 1 * MIN))
    }

    @Test
    fun unlockingAnotherApp_replacesTheSession() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // Quick hop to Twitter and unlock it within the grace period.
        assertEquals(hold(120_000, TWITTER), eval(blocks, TWITTER, at = 1 * MIN))
        unlock(blocks, 1 * MIN + 1_000, TWITTER)
        assertEquals(Decision.None, eval(blocks, TWITTER, at = 1 * MIN + 1_000))

        // Only one session: Instagram is locked again straight away.
        assertEquals(hold(120_000, INSTA), eval(blocks, INSTA, at = 1 * MIN + 2_000))
    }

    @Test
    fun splitScreen_oneHoldUnlocksEveryBlockedAppOnScreen() {
        val blocks = listOf(social)
        assertEquals(hold(120_000, INSTA, TWITTER), eval(blocks, INSTA, TWITTER, OTHER, at = 0))
        unlock(blocks, 0, INSTA, TWITTER)
        assertEquals(Decision.None, eval(blocks, INSTA, TWITTER, OTHER, at = 1_000))
    }

    @Test
    fun splitScreen_lockedAppNextToUnlockedOne_holdCoversBoth() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // The new hold unlocks both, so finishing it doesn't lock Instagram.
        assertEquals(hold(120_000, INSTA, TWITTER), eval(blocks, INSTA, TWITTER, at = 1 * MIN))
        unlock(blocks, 3 * MIN, INSTA, TWITTER)
        assertEquals(Decision.None, eval(blocks, INSTA, TWITTER, at = 3 * MIN))
        assertEquals(13 * MIN, engine.nextExpiryMs())
    }

    @Test
    fun holdCompleted_ignoresAppsNoLongerBlocked() {
        unlock(listOf(social.copy(enabled = false)), 0, INSTA)
        assertNull(engine.nextExpiryMs())
        unlock(listOf(evening), 0, TWITTER)
        assertNull(engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- leaving the app

    @Test
    fun returningWithinGrace_needsNoNewHold() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // e.g. fold/unfold relaunch, or a quick look at another app.
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 1 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 1 * MIN + GRACE - 1))
        // The grace restarts each time it leaves.
        assertEquals(Decision.None, eval(blocks, OTHER, at = 2 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 2 * MIN + GRACE - 1))
    }

    @Test
    fun awayLongerThanGrace_locksAgain_andEndsTheSession() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 1 * MIN))
        assertEquals(hold(120_000, INSTA), eval(blocks, INSTA, at = 1 * MIN + GRACE))
        assertNull(engine.nextExpiryMs())
    }

    @Test
    fun splitScreen_appClosedForGrace_dropsOutWhileTheOtherStays() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA, TWITTER)
        assertEquals(Decision.None, eval(blocks, INSTA, TWITTER, at = 0))

        assertEquals(Decision.None, eval(blocks, INSTA, at = 1 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 1 * MIN + GRACE))
        assertEquals(hold(120_000, INSTA, TWITTER), eval(blocks, INSTA, TWITTER, at = 2 * MIN))
    }

    // ---------------------------------------------------------------- session length

    @Test
    fun sessionEndsWhileAppInUse_goesHome_thenNextOpenNeedsHold() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(10 * MIN, engine.nextExpiryMs())

        assertEquals(Decision.GoHome, eval(blocks, INSTA, at = 10 * MIN))
        assertNull(engine.nextExpiryMs())

        // If the app is somehow still on screen afterwards: hold screen, not GoHome again.
        assertEquals(hold(120_000, INSTA), eval(blocks, INSTA, at = 10 * MIN + 100))
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 10 * MIN + 500))
        assertEquals(hold(120_000, INSTA), eval(blocks, INSTA, at = 11 * MIN))
    }

    @Test
    fun sessionTimeIsTotal_notResetByQuickHops() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 9 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 9 * MIN + 1_000))
        assertEquals(Decision.GoHome, eval(blocks, INSTA, at = 10 * MIN))
    }

    @Test
    fun sessionTimeRunsOutUnderHoldScreen_keepsHoldInsteadOfGoingHome() {
        val blocks = listOf(social)
        unlock(blocks, 0, INSTA)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        // Twitter opens in split screen: hold screen covers both.
        assertEquals(hold(120_000, INSTA, TWITTER), eval(blocks, INSTA, TWITTER, at = 9 * MIN))
        assertEquals(hold(120_000, INSTA, TWITTER), eval(blocks, INSTA, TWITTER, at = 10 * MIN))
    }

    @Test
    fun zeroMinuteSession_neverSendsHome() {
        val blocks = listOf(social.copy(sessionMinutes = 0))
        unlock(blocks, 0, INSTA)

        assertNull(engine.nextExpiryMs())
        assertEquals(Decision.None, eval(blocks, INSTA, at = 600 * MIN))
        // ...but leaving still locks it.
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 601 * MIN))
        assertEquals(hold(120_000, INSTA), eval(blocks, INSTA, at = 601 * MIN + GRACE))
    }

    @Test
    fun editedSessionLength_appliesFromTheNextHold() {
        unlock(listOf(social), 0, INSTA)
        val edited = social.copy(sessionMinutes = 30)
        assertEquals(Decision.None, eval(listOf(edited), INSTA, at = 1 * MIN))
        assertEquals(10 * MIN, engine.nextExpiryMs())

        unlock(listOf(edited), 12 * MIN, INSTA)
        assertEquals(42 * MIN, engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- overlapping blocks

    @Test
    fun appInTwoBlocks_usesLongestHold_andShortestSession() {
        val blocks = listOf(social, evening)
        assertEquals(hold(300_000, INSTA), eval(blocks, INSTA, at = 0))
        unlock(blocks, 0, INSTA)
        assertEquals(5 * MIN, engine.nextExpiryMs())
    }

    @Test
    fun appInTwoBlocks_zeroMinuteBlockDoesNotOverrideTimedOne() {
        val blocks = listOf(social.copy(sessionMinutes = 0), evening)
        unlock(blocks, 0, INSTA)
        assertEquals(5 * MIN, engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- editing blocks

    @Test
    fun blockDisabledWhileHoldScreenShowing_holdScreenGoesAway() {
        assertEquals(hold(120_000, INSTA), eval(listOf(social), INSTA, at = 0))
        assertEquals(Decision.None, eval(listOf(social.copy(enabled = false)), INSTA, at = 500))
    }

    @Test
    fun appRemovedFromBlock_isNoLongerBlocked() {
        assertEquals(Decision.None, eval(listOf(social.copy(packages = setOf(TWITTER))), INSTA, at = 0))
    }

    @Test
    fun appAddedToBlock_isLockedStraightAway() {
        val edited = social.copy(packages = social.packages + YOUTUBE)
        assertEquals(hold(120_000, YOUTUBE), eval(listOf(edited), YOUTUBE, at = 0))
    }

    private companion object {
        const val MIN = 60_000L
        const val GRACE = BlockEngine.LEAVE_GRACE_MS
        const val INSTA = "com.instagram.android"
        const val TWITTER = "com.twitter.android"
        const val YOUTUBE = "com.google.android.youtube"
        const val OTHER = "com.example.notes"
        const val LAUNCHER = "com.sec.android.app.launcher"
    }
}
