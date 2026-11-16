package com.appblocker.blocker

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.appblocker.data.BlockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches which apps are on screen and covers blocked ones with the hold screen.
 *
 * Flow: window event -> work out visible packages -> [BlockEngine.evaluate] -> show the
 * hold screen / hide it / send the user home. All of this runs on the main thread
 * (accessibility events, the Handler and the coroutine all use it), so no locking.
 *
 * Clocks: unlock sessions use SystemClock.elapsedRealtime (keeps counting while the
 * phone sleeps); the hold uses SystemClock.uptimeMillis (same clock as Handler).
 *
 * Multi-display limitation: we only look at windows on the default display
 * (`windows`) and put one overlay there. On the Z Fold the cover and main screens are
 * normally the same logical display (the system swaps the panel underneath), so this
 * covers both. A real second display (DeX, external monitor) is NOT covered. To add
 * that: read `windowsOnAllDisplays`, and keep one [HoldOverlay] per display id, each
 * built from a context for that display.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val engine = BlockEngine()
    private val holdTimer = HoldTimer()

    private var scope: CoroutineScope? = null

    /** The hold screen on the default display. Null while the service isn't connected. */
    private var overlay: HoldOverlay? = null

    private var systemEventsReceiver: BroadcastReceiver? = null

    /** Packages that never count as "visible": us, System UI, the home screen app. */
    private var ignoredPackages: Set<String> = emptySet()

    /** windowId -> package, so we don't ask every window for its root node on every event. */
    private val windowPackageCache = HashMap<Int, String>()

    /** Last known set of visible app packages (kept as-is through transient states). */
    private var visiblePackages: Set<String> = emptySet()

    /** Apps that completing the current hold will unlock. */
    private var holdPackages: Set<String> = emptySet()

    /** After sending the user home, don't pop the hold screen up during the home animation. */
    private var noHoldScreenUntilUptime = 0L

    private val holdTimeReached = Runnable { onHoldTimeReached() }
    private val delayedHide = Runnable { hideHoldScreen() }
    private val sessionExpiryCheck = Runnable { recheck() }
    private val afterHomeCheck = Runnable { recheck() }

    private val overlayListener = object : HoldOverlay.Listener {
        override fun onHoldStarted() {
            if (holdTimer.press(SystemClock.uptimeMillis()) != null) scheduleHoldTimeReached()
        }

        override fun onHoldStopped() {
            resetHold()
        }

        override fun onBackPressed() {
            // Back leaves, it never reveals the app: go to the home screen. The hold
            // screen then disappears because the blocked app is no longer visible.
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        tearDown() // in case we are reconnected without being destroyed

        ignoredPackages = buildSet {
            add(packageName)
            add(SYSTEM_UI_PACKAGE)
            // Never block the home screen: Home would no longer be an escape route.
            defaultHomePackage()?.let { add(it) }
        }
        overlay = HoldOverlay(this, overlayListener)
        registerSystemEventsReceiver()
        readVisiblePackages(null)?.let { visiblePackages = it }

        // Re-evaluate whenever blocks change (e.g. a block is disabled while its hold
        // screen is up -> it goes away). A StateFlow also emits its current value
        // straight away, which gives us our first evaluation.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { BlockRepository.blocks.collect { reevaluate() } }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || overlay == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        // null = transient state we can't read (shade, mid-transition): keep what we had.
        val packages = readVisiblePackages(event) ?: return
        visiblePackages = packages
        reevaluate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold or rotation: the screen size changed under the user's finger.
        // Restart the hold (acceptable) and make sure the window still fits the screen.
        resetHold()
        overlay?.relayout()
    }

    override fun onInterrupt() {
        // Meant for "stop talking" in screen readers; we give no spoken feedback.
        // Deliberately does NOT remove the overlay: any app can trigger an interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The user turned the service off (or the system is unbinding it).
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    /** Removes the overlay and stops everything. Safe to call more than once. */
    private fun tearDown() {
        scope?.cancel()
        scope = null
        handler.removeCallbacksAndMessages(null)
        holdTimer.reset()
        holdPackages = emptySet()
        overlay?.hide()
        overlay = null
        systemEventsReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // wasn't registered; fine
            }
        }
        systemEventsReceiver = null
        windowPackageCache.clear()
    }

    // ------------------------------------------------------------------ what is visible

    /**
     * Packages of all app windows on screen (split screen, pop-up windows, picture-in-
     * picture), minus [ignoredPackages]. Returns null when the current state can't be
     * read reliably, meaning "keep the previous answer".
     *
     * Only TYPE_APPLICATION windows count, so the keyboard, notification shade, volume
     * panel, system dialogs and our own overlay never change the answer on their own.
     * Our overlay is TYPE_ACCESSIBILITY_OVERLAY, which the system does not treat as
     * hiding the windows beneath it, so the blocked app stays in the list while covered.
     *
     * Home screen and Recents (both the launcher on One UI) are ordinary app windows of
     * an ignored package: being there means the user has left the blocked app.
     */
    private fun readVisiblePackages(event: AccessibilityEvent?): Set<String>? {
        val found = HashSet<String>()
        val seenWindowIds = HashSet<Int>()
        var sawAppWindow = false

        for (window in windows.orEmpty()) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = packageOf(window) ?: continue
            seenWindowIds += window.id
            sawAppWindow = true
            found += pkg
        }
        windowPackageCache.keys.retainAll(seenWindowIds)

        // Even if the only app on screen is ours (settings), that's a real answer: empty.
        if (sawAppWindow) return found - ignoredPackages

        // No app windows at all. Happens briefly during transitions and when a
        // full-screen system window (notification shade, lock screen) hides apps.
        // Fall back to the event's package, but only for a real "window changed" event
        // from an actual app; system UI / our own events mean "don't know".
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return null
        }
        val eventPackage = event.packageName?.toString() ?: return null
        if (eventPackage == packageName ||
            eventPackage == SYSTEM_UI_PACKAGE ||
            eventPackage == ANDROID_PACKAGE
        ) {
            return null
        }
        return setOf(eventPackage) - ignoredPackages
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? {
        windowPackageCache[window.id]?.let { return it }
        // root can be null for a moment while a window is being created.
        val pkg = window.root?.packageName?.toString() ?: return null
        windowPackageCache[window.id] = pkg
        return pkg
    }

    /**
     * The current default launcher, if we can see it. Needs a <queries> entry for
     * MAIN/HOME in the manifest on Android 11+ (package visibility), else returns null.
     */
    private fun defaultHomePackage(): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        // "android" = the system chooser, i.e. no default launcher picked.
        info?.activityInfo?.packageName?.takeIf { it != ANDROID_PACKAGE }
    } catch (e: RuntimeException) {
        null
    }

    // ------------------------------------------------------------------ decisions

    /** Read windows afresh (no event), then decide. Used by timers and broadcasts. */
    private fun recheck() {
        if (overlay == null) return
        readVisiblePackages(null)?.let { visiblePackages = it }
        reevaluate()
    }

    private fun reevaluate() {
        if (overlay == null) return
        val decision = engine.evaluate(
            BlockRepository.blocks.value,
            visiblePackages,
            SystemClock.elapsedRealtime(),
        )
        when (decision) {
            Decision.None -> hideHoldScreenSoon()
            is Decision.ShowHold -> showHoldScreen(decision)
            Decision.GoHome -> sendHome()
        }
        scheduleSessionExpiryCheck()
    }

    private fun showHoldScreen(decision: Decision.ShowHold) {
        val ov = overlay ?: return
        handler.removeCallbacks(delayedHide)

        if (ov.isShowing) {
            // Already up (never add it twice). Follow changes, e.g. another blocked app
            // opened in split screen: unlock the new set, and keep the current press going.
            holdPackages = decision.packages
            if (holdTimer.requiredMillis != decision.holdMillis) {
                holdTimer.requiredMillis = decision.holdMillis
                scheduleHoldTimeReached()
            }
            return
        }

        // Never cover the lock screen: the PIN pad would be unreachable. We re-check
        // when the user unlocks (USER_PRESENT).
        if (isDeviceLocked()) return

        val wait = noHoldScreenUntilUptime - SystemClock.uptimeMillis()
        if (wait > 0) {
            handler.removeCallbacks(afterHomeCheck)
            handler.postDelayed(afterHomeCheck, wait)
            return
        }

        holdTimer.reset()
        holdTimer.requiredMillis = decision.holdMillis
        holdPackages = decision.packages
        ov.show() // if this fails, the next window event tries again
    }

    /**
     * Hide after a short delay. Opening the hold screen is instant, but hiding waits a
     * moment and is cancelled if a blocked app shows up again, so window shuffles
     * (fold/unfold relaunch, activity transitions) don't make it flicker off and on.
     */
    private fun hideHoldScreenSoon() {
        if (overlay?.isShowing != true) return
        if (!handler.hasCallbacks(delayedHide)) handler.postDelayed(delayedHide, HIDE_DELAY_MS)
    }

    private fun hideHoldScreen() {
        handler.removeCallbacks(delayedHide)
        handler.removeCallbacks(holdTimeReached)
        holdTimer.reset()
        holdPackages = emptySet()
        overlay?.hide()
    }

    /** The session ended while an unlocked app was in use. */
    private fun sendHome() {
        Log.i(TAG, "Unlock session over; going home")
        hideHoldScreen()
        noHoldScreenUntilUptime = SystemClock.uptimeMillis() + AFTER_HOME_GRACE_MS
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // If going home didn't work, the app is still visible after the grace period and
        // (its session now being over) the hold screen covers it.
        handler.removeCallbacks(afterHomeCheck)
        handler.postDelayed(afterHomeCheck, AFTER_HOME_GRACE_MS)
    }

    private fun scheduleSessionExpiryCheck() {
        handler.removeCallbacks(sessionExpiryCheck)
        val expiry = engine.nextExpiryMs() ?: return
        // Handler runs on uptime, which pauses in deep sleep, so this can fire late while
        // the screen is off; window events on wake-up re-evaluate anyway.
        val delay = (expiry - SystemClock.elapsedRealtime() + EXPIRY_MARGIN_MS).coerceAtLeast(0L)
        handler.postDelayed(sessionExpiryCheck, delay)
    }

    // ------------------------------------------------------------------ the hold

    /** Post one check for the moment the hold should complete (no polling). */
    private fun scheduleHoldTimeReached() {
        handler.removeCallbacks(holdTimeReached)
        val remaining = holdTimer.remainingMillis(SystemClock.uptimeMillis()) ?: return
        handler.postDelayed(holdTimeReached, remaining)
    }

    private fun onHoldTimeReached() {
        if (!holdTimer.tryComplete(SystemClock.uptimeMillis())) {
            scheduleHoldTimeReached() // fired a touch early, or the press already ended
            return
        }
        engine.onHoldCompleted(
            holdPackages,
            BlockRepository.blocks.value,
            SystemClock.elapsedRealtime(),
        )
        hideHoldScreen()
        // Normally gives None now; shows the screen again if something else is still locked.
        reevaluate()
    }

    /** Count back to zero; the finger must be lifted and pressed again. */
    private fun resetHold() {
        holdTimer.release()
        handler.removeCallbacks(holdTimeReached)
        overlay?.cancelPress()
    }

    // ------------------------------------------------------------------ screen on/off

    private fun registerSystemEventsReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    // Screen off: the hold resets, and the overlay must not sit on top of
                    // the lock screen. It comes back after unlocking if still needed.
                    Intent.ACTION_SCREEN_OFF -> hideHoldScreen()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> recheck()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Only protected system broadcasts, so no RECEIVER_EXPORTED flag is needed (API 34+).
        registerReceiver(receiver, filter)
        systemEventsReceiver = receiver
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private companion object {
        const val TAG = "BlockerService"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val ANDROID_PACKAGE = "android"

        /** How long the hold screen lingers after the blocked app seems to have gone. */
        const val HIDE_DELAY_MS = 300L

        /** After sending the user home, how long before we cover a still-visible app. */
        const val AFTER_HOME_GRACE_MS = 1_000L

        /** Check a little after the session end so it is definitely over. */
        const val EXPIRY_MARGIN_MS = 20L
    }
}
