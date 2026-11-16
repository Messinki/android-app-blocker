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
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(listOf(social), INSTA, at = 0),
        )
    }

    @Test
    fun disabledBlock_neverBlocks() {
        assertEquals(Decision.None, eval(listOf(social.copy(enabled = false)), INSTA, at = 0))
    }

    @Test
    fun splitScreen_anyVisibleBlockedAppTriggers() {
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(listOf(social), OTHER, TWITTER, at = 0),
        )
    }

    @Test
    fun editedHoldTime_appliesToHoldScreenStraightAway() {
        assertEquals(
            Decision.ShowHold(30_000, setOf("social")),
            eval(listOf(social.copy(holdSeconds = 30)), INSTA, at = 0),
        )
    }

    @Test
    fun zeroOrNegativeValues_areClampedToMinimums() {
        val odd = social.copy(holdSeconds = 0, sessionMinutes = -5)
        assertEquals(Decision.ShowHold(1_000, setOf("social")), eval(listOf(odd), INSTA, at = 0))
        engine.onHoldCompleted(setOf("social"), listOf(odd), 0)
        assertEquals(MIN, engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- sessions

    @Test
    fun holdCompleted_unlocksEveryAppInBlockForSession() {
        val blocks = listOf(social)
        engine.onHoldCompleted(setOf("social"), blocks, 1_000)

        assertEquals(Decision.None, eval(blocks, INSTA, at = 1_000))
        assertEquals(Decision.None, eval(blocks, TWITTER, at = 1_000 + 9 * MIN))
        assertEquals(1_000 + 10 * MIN, engine.nextExpiryMs())
    }

    @Test
    fun leavingAndReturningWithinSession_needsNoNewHold() {
        val blocks = listOf(social)
        engine.onHoldCompleted(setOf("social"), blocks, 0)

        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 1 * MIN))
        assertEquals(Decision.None, eval(blocks, INSTA, at = 9 * MIN))
    }

    @Test
    fun sessionEndsWhileAppInUse_goesHome_thenNextOpenNeedsHold() {
        val blocks = listOf(social)
        engine.onHoldCompleted(setOf("social"), blocks, 0)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))

        // Exactly at the end of the 10 minutes, still in the app.
        assertEquals(Decision.GoHome(setOf("social")), eval(blocks, INSTA, at = 10 * MIN))
        assertNull(engine.nextExpiryMs())

        // If the app is somehow still on screen afterwards: hold screen, not GoHome again.
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(blocks, INSTA, at = 10 * MIN + 100),
        )
        // Normal path: home screen, then reopening.
        assertEquals(Decision.None, eval(blocks, LAUNCHER, at = 10 * MIN + 500))
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(blocks, INSTA, at = 11 * MIN),
        )
    }

    @Test
    fun sessionEndsWhileAway_returningShowsHoldNotGoHome() {
        val blocks = listOf(social)
        engine.onHoldCompleted(setOf("social"), blocks, 0)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 0))
        assertEquals(Decision.None, eval(blocks, OTHER, at = 1 * MIN))

        // No evaluation happened at the 10-minute mark (e.g. phone asleep).
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(blocks, INSTA, at = 11 * MIN),
        )
    }

    @Test
    fun expiredSessions_areClearedEvenWhenNotVisible() {
        val blocks = listOf(social, evening)
        engine.onHoldCompleted(setOf("social"), blocks, 0) // ends at 10 min
        engine.onHoldCompleted(setOf("evening"), blocks, 1 * MIN) // ends at 6 min
        assertEquals(6 * MIN, engine.nextExpiryMs())

        assertEquals(Decision.None, eval(blocks, OTHER, at = 6 * MIN))
        assertEquals(10 * MIN, engine.nextExpiryMs())
        assertEquals(Decision.None, eval(blocks, at = 10 * MIN))
        assertNull(engine.nextExpiryMs())
    }

    // ---------------------------------------------------------------- overlapping blocks

    @Test
    fun appInTwoBlocks_oneUnlocked_isStillLockedByTheOther() {
        val blocks = listOf(social, evening)
        engine.onHoldCompleted(setOf("social"), blocks, 0)

        assertEquals(
            Decision.ShowHold(300_000, setOf("evening")),
            eval(blocks, INSTA, at = 1_000),
        )
        // TWITTER is only in the unlocked block.
        assertEquals(Decision.None, eval(blocks, TWITTER, at = 2_000))
    }

    @Test
    fun appInTwoLockedBlocks_usesLongestHold_andCompletionUnlocksBoth() {
        val blocks = listOf(social, evening)
        val decision = eval(blocks, INSTA, at = 0)
        assertEquals(Decision.ShowHold(300_000, setOf("social", "evening")), decision)

        engine.onHoldCompleted((decision as Decision.ShowHold).blockIds, blocks, 300_000)
        assertEquals(Decision.None, eval(blocks, INSTA, at = 300_000))
        assertEquals(Decision.None, eval(blocks, YOUTUBE, at = 301_000))
        assertEquals(Decision.None, eval(blocks, TWITTER, at = 302_000))
    }

    @Test
    fun otherBlocksSessionEndingUnderHoldScreen_extendsHoldInsteadOfGoingHome() {
        val blocks = listOf(social, evening)
        engine.onHoldCompleted(setOf("social"), blocks, 0)
        assertEquals(
            Decision.ShowHold(300_000, setOf("evening")),
            eval(blocks, INSTA, at = 1_000),
        )
        // `social` runs out while the hold screen is covering INSTA: nothing was in use.
        assertEquals(
            Decision.ShowHold(300_000, setOf("social", "evening")),
            eval(blocks, INSTA, at = 10 * MIN),
        )
    }

    // ---------------------------------------------------------------- editing blocks

    @Test
    fun blockDisabledWhileHoldScreenShowing_holdScreenGoesAway() {
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(listOf(social), INSTA, at = 0),
        )
        assertEquals(Decision.None, eval(listOf(social.copy(enabled = false)), INSTA, at = 500))
    }

    @Test
    fun deletedBlock_neverBlocks_andLosesItsSession() {
        engine.onHoldCompleted(setOf("social"), listOf(social), 0)
        assertEquals(Decision.None, eval(emptyList(), INSTA, at = 1_000))
        assertNull(engine.nextExpiryMs())

        // Same id comes back (e.g. undo): it needs a fresh hold.
        assertEquals(
            Decision.ShowHold(120_000, setOf("social")),
            eval(listOf(social), INSTA, at = 2_000),
        )
    }

    @Test
    fun blockEditedDuringSession_newAppIsCovered_newLengthAppliesNextTime() {
        engine.onHoldCompleted(setOf("social"), listOf(social), 0)
        val edited = social.copy(packages = social.packages + YOUTUBE, sessionMinutes = 30)

        // Newly added app is part of the running session.
        assertEquals(Decision.None, eval(listOf(edited), YOUTUBE, at = 1 * MIN))
        // The running session keeps its original 10-minute end.
        assertEquals(10 * MIN, engine.nextExpiryMs())
        assertEquals(Decision.GoHome(setOf("social")), eval(listOf(edited), YOUTUBE, at = 10 * MIN))

        // The next session uses the new 30 minutes.
        engine.onHoldCompleted(setOf("social"), listOf(edited), 12 * MIN)
        assertEquals(42 * MIN, engine.nextExpiryMs())
    }

    @Test
    fun appRemovedFromBlock_isNoLongerBlocked() {
        val edited = social.copy(packages = setOf(TWITTER))
        assertEquals(Decision.None, eval(listOf(edited), INSTA, at = 0))
    }

    @Test
    fun holdCompleted_ignoresDisabledAndUnknownBlocks() {
        engine.onHoldCompleted(setOf("social", "ghost"), listOf(social.copy(enabled = false)), 0)
        assertNull(engine.nextExpiryMs())
    }

    private companion object {
        const val MIN = 60_000L
        const val INSTA = "com.instagram.android"
        const val TWITTER = "com.twitter.android"
        const val YOUTUBE = "com.google.android.youtube"
        const val OTHER = "com.example.notes"
        const val LAUNCHER = "com.sec.android.app.launcher"
    }
}
