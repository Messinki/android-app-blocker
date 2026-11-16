package com.appblocker.blocker

import com.appblocker.data.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockEngineTest {

    private val engine = BlockEngine()

    // Default rule: hold 120 s, session 10 min.
    private val social = Block(id = "social", name = "Social", packages = setOf(INSTA, TWITTER))

    // Shares INSTA with `social`, longer hold.
    private val evening = Block(
        id = "evening",
        name = "Evening",
        packages = setOf(INSTA, YOUTUBE),
        holdSeconds = 300,
        sessionMinutes = 5,
    )

    private fun eval(blocks: List<Block>, vararg visible: String, at: Long): Decision =
        engine.evaluate(blocks, visible.toSet(), at)

    private fun u(blockId: String, pkg: String) = AppUnlock(blockId, pkg)

    private fun hold(millis: Long, vararg unlocks: AppUnlock) = Decision.ShowHold(millis, unlocks.toSet())

    private fun unlock(blocks: List<Block>, at: Long, vararg unlocks: AppUnlock) =
        engine.onHoldCompleted(unlocks.toSet(), blocks, at)

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
        assertEquals(hold(120_000, u("social", INSTA)), eval(listOf(social), INSTA, at = 0))
    }

    @Test
    fun disabledBlock_neverBlocks() {
        assertEquals(Decision.None, eval(listOf(social.copy(enabled = false)), INSTA, at = 0))
    }

    @Test
    fun splitScreen_anyVisibleBlockedAppTriggers() {
        assertEquals(hold(120_000, u("social", TWITTER)), eval(listOf(social), OTHER, TWITTER, at = 0))
    }

    @Test
    fun splitScreen_twoLockedAppsFromOneBlock_oneHoldUnlocksBoth() {
        val blocks = listOf(social)
        val decision = eval(blocks, INSTA, TWITTER, at = 0)
        assertEquals(hold(120_000, u("social", INSTA), u("social", TWITTER)), decision)

        engine.onHoldCompleted((decision as Decision.ShowHold).unlocks, blocks, 0)
        assertEquals(Decision.None, eval(blocks, INSTA, TWITTER, at = 1_000))
    }

    @Test
    fun editedHoldTime_appliesToHoldScreenStraightAway() {
        assertEquals(
            hold(30_000, u("social", INSTA)),
            eval(listOf(social.copy(holdSeconds = 30)), INSTA, at = 0),
        )
    }

    @Test
    fun zeroOrNegativeValues_areClampedToMinimums() {
        val odd = social.copy(holdSeconds = 0, sessionMinutes = -5)
        assertEquals(hold(1_000, u("social", INSTA)), eval(listOf(odd), INSTA, at = 0))
        unlock(listOf(odd), 0, u("social", INSTA))
        assertEquals(MIN, engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- per-app sessions

    @Test
    fun holdCompleted_unlocksOnlyThatApp_notTheRestOfTheBlock() {
        val blocks = listOf(social)
        unlock(blocks, 1_000, u("social", INSTA))

        assertEquals(Decision.None, eval(blocks, INSTA, at = 1_000))
        // Go home, open another app from the same block: it needs its own hold.
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 2_000))
        assertEquals(hold(120_000, u("social", TWITTER)), eval(blocks, TWITTER, at = 3_000))
        // Instagram's own session is unaffected.
        assertEquals(Decision.None, eval(blocks, INSTA, at = 9 * MIN))
        assertEquals(1_000 + 10 * MIN, engine.nextExpiryMs())
    }

    @Test
    fun twoAppsInOneBlock_haveIndependentSessions() {
        val blocks = listOf(social)
        unlock(blocks, 0, u("social", INSTA)) // ends at 10 min
        unlock(blocks, 4 * MIN, u("social", TWITTER)) // ends at 14 min

        assertEquals(Decision.None, eval(blocks, TWITTER, at = 4 * MIN))
        assertEquals(10 * MIN, engine.nextExpiryMs())
        // Instagram's session ending doesn't touch Twitter, which is in use.
        assertEquals(Decision.None, eval(blocks, TWITTER, at = 10 * MIN))
        assertEquals(14 * MIN, engine.nextExpiryMs())
        assertEquals(Decision.GoHome(setOf(u("social", TWITTER))), eval(blocks, TWITTER, at = 14 * MIN))
    }

    @Test
    fun leavingAndReturningWithinSession_needsNoNewHold() {
        val blocks = listOf(social)
        unlock(blocks, 0, u("social", INSTA))

        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 1 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 9 * MIN))
    }

    @Test
    fun sessionEndsWhileAppInUse_goesHome_thenNextOpenNeedsHold() {
        val blocks = listOf(social)
        unlock(blocks, 0, u("social", INSTA))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // Exactly at the end of the 10 minutes, still in the app.
        assertEquals(Decision.GoHome(setOf(u("social", INSTA))), eval(blocks, INSTA, at = 10 * MIN))
        assertNull(engine.nextExpiryMs())

        // If the app is somehow still on screen afterwards: hold screen, not GoHome again.
        assertEquals(hold(120_000, u("social", INSTA)), eval(blocks, INSTA, at = 10 * MIN + 100))
        // Normal path: home screen, then reopening.
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 10 * MIN + 500))
        assertEquals(hold(120_000, u("social", INSTA)), eval(blocks, INSTA, at = 11 * MIN))
    }

    @Test
    fun sessionEndsWhileAway_returningShowsHoldNotGoHome() {
        val blocks = listOf(social)
        unlock(blocks, 0, u("social", INSTA))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(Decision.None, eval(blocks, OTHER, at = 1 * MIN))

        // No evaluation happened at the 10-minute mark (e.g. phone asleep).
        assertEquals(hold(120_000, u("social", INSTA)), eval(blocks, INSTA, at = 11 * MIN))
    }

    @Test
    fun expiredSessions_areClearedEvenWhenNotVisible() {
        val blocks = listOf(social, evening)
        unlock(blocks, 0, u("social", INSTA)) // ends at 10 min
        unlock(blocks, 1 * MIN, u("evening", YOUTUBE)) // ends at 6 min
        assertEquals(6 * MIN, engine.nextExpiryMs())

        assertEquals(Decision.None, eval(blocks, OTHER, at = 6 * MIN))
        assertEquals(10 * MIN, engine.nextExpiryMs())
        assertEquals(Decision.None, eval(blocks, at = 10 * MIN))
        assertNull(engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- no time limit (0 min)

    @Test
    fun zeroMinuteSession_neverSendsHome_whileAppStaysOpen() {
        val blocks = listOf(social.copy(sessionMinutes = 0))
        unlock(blocks, 0, u("social", INSTA))

        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertNull(engine.nextExpiryMs())
        assertEquals(Decision.None, eval(blocks, INSTA, at = 600 * MIN))
        assertNull(engine.nextExpiryMs())
    }

    @Test
    fun zeroMinuteSession_endsAfterLeavingTheApp() {
        val blocks = listOf(social.copy(sessionMinutes = 0))
        unlock(blocks, 0, u("social", INSTA))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // Left for the home screen: the session ends after the grace period.
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 5 * MIN))
        assertEquals(5 * MIN + GRACE, engine.nextExpiryMs())
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 5 * MIN + GRACE))
        assertNull(engine.nextExpiryMs())

        assertEquals(hold(120_000, u("social", INSTA)), eval(blocks, INSTA, at = 6 * MIN))
    }

    @Test
    fun zeroMinuteSession_switchingToAnotherAppInBlock_needsHold_andStartsGrace() {
        val blocks = listOf(social.copy(sessionMinutes = 0))
        unlock(blocks, 0, u("social", INSTA))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        assertEquals(hold(120_000, u("social", TWITTER)), eval(blocks, TWITTER, at = 1 * MIN))
        assertEquals(1 * MIN + GRACE, engine.nextExpiryMs())
    }

    @Test
    fun zeroMinuteSession_survivesBriefTripAway() {
        val blocks = listOf(social.copy(sessionMinutes = 0))
        unlock(blocks, 0, u("social", INSTA))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // e.g. fold/unfold relaunch, or a quick look at another app.
        assertEquals(Decision.None, eval(blocks, at = 1 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 1 * MIN + GRACE - 1))
        assertNull(engine.nextExpiryMs()) // back on screen: no end time again
        assertEquals(Decision.None, eval(blocks, INSTA, at = 60 * MIN))
    }

    @Test
    fun zeroMinuteSession_appSharedWithTimedBlock_timedBlockStillGoesHome() {
        val blocks = listOf(social.copy(sessionMinutes = 0), evening)
        unlock(blocks, 0, u("social", INSTA), u("evening", INSTA))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        assertEquals(5 * MIN, engine.nextExpiryMs())
        assertEquals(Decision.GoHome(setOf(u("evening", INSTA))), eval(blocks, INSTA, at = 5 * MIN))
    }

    // ---------------------------------------------------------------- overlapping blocks

    @Test
    fun appInTwoBlocks_oneUnlocked_isStillLockedByTheOther() {
        val blocks = listOf(social, evening)
        unlock(blocks, 0, u("social", INSTA))

        assertEquals(hold(300_000, u("evening", INSTA)), eval(blocks, INSTA, at = 1_000))
    }

    @Test
    fun appInTwoLockedBlocks_usesLongestHold_andCompletionUnlocksOnlyThatApp() {
        val blocks = listOf(social, evening)
        val decision = eval(blocks, INSTA, at = 0)
        assertEquals(hold(300_000, u("social", INSTA), u("evening", INSTA)), decision)

        engine.onHoldCompleted((decision as Decision.ShowHold).unlocks, blocks, 300_000)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 300_000))
        // The other apps of both blocks stay locked.
        assertEquals(hold(300_000, u("evening", YOUTUBE)), eval(blocks, YOUTUBE, at = 301_000))
        assertEquals(hold(120_000, u("social", TWITTER)), eval(blocks, TWITTER, at = 302_000))
    }

    @Test
    fun otherBlocksSessionEndingUnderHoldScreen_extendsHoldInsteadOfGoingHome() {
        val blocks = listOf(social, evening)
        unlock(blocks, 0, u("social", INSTA))
        assertEquals(hold(300_000, u("evening", INSTA)), eval(blocks, INSTA, at = 1_000))
        // `social` runs out while the hold screen is covering INSTA: nothing was in use.
        assertEquals(
            hold(300_000, u("social", INSTA), u("evening", INSTA)),
            eval(blocks, INSTA, at = 10 * MIN),
        )
    }

    // ---------------------------------------------------------------- editing blocks

    @Test
    fun blockDisabledWhileHoldScreenShowing_holdScreenGoesAway() {
        assertEquals(hold(120_000, u("social", INSTA)), eval(listOf(social), INSTA, at = 0))
        assertEquals(Decision.None, eval(listOf(social.copy(enabled = false)), INSTA, at = 500))
    }

    @Test
    fun deletedBlock_neverBlocks_andLosesItsSessions() {
        unlock(listOf(social), 0, u("social", INSTA))
        assertEquals(Decision.None, eval(emptyList(), INSTA, at = 1_000))
        assertNull(engine.nextExpiryMs())

        // Same id comes back (e.g. undo): it needs a fresh hold.
        assertEquals(hold(120_000, u("social", INSTA)), eval(listOf(social), INSTA, at = 2_000))
    }

    @Test
    fun blockEditedDuringSession_newAppIsLocked_newLengthAppliesNextTime() {
        unlock(listOf(social), 0, u("social", INSTA))
        val edited = social.copy(packages = social.packages + YOUTUBE, sessionMinutes = 30)

        // Newly added app is not part of anyone else's session.
        assertEquals(hold(120_000, u("social", YOUTUBE)), eval(listOf(edited), YOUTUBE, at = 1 * MIN))
        // The running session keeps its original 10-minute end.
        assertEquals(Decision.None, eval(listOf(edited), INSTA, at = 2 * MIN))
        assertEquals(10 * MIN, engine.nextExpiryMs())
        assertEquals(Decision.GoHome(setOf(u("social", INSTA))), eval(listOf(edited), INSTA, at = 10 * MIN))

        // The next session uses the new 30 minutes.
        unlock(listOf(edited), 12 * MIN, u("social", INSTA))
        assertEquals(42 * MIN, engine.nextExpiryMs())
    }

    @Test
    fun appRemovedFromBlock_isNoLongerBlocked_andLosesItsSession() {
        unlock(listOf(social), 0, u("social", INSTA))
        val edited = social.copy(packages = setOf(TWITTER))
        assertEquals(Decision.None, eval(listOf(edited), INSTA, at = 1_000))
        assertNull(engine.nextExpiryMs())

        // Put back: needs a fresh hold.
        assertEquals(hold(120_000, u("social", INSTA)), eval(listOf(social), INSTA, at = 2_000))
    }

    @Test
    fun holdCompleted_ignoresDisabledUnknownBlocksAndAppsNotInBlock() {
        unlock(
            listOf(social.copy(enabled = false), evening),
            0,
            u("social", INSTA),
            u("ghost", INSTA),
            u("evening", TWITTER),
        )
        assertNull(engine.nextExpiryMs())
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
