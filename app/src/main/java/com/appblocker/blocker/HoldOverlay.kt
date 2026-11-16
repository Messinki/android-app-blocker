package com.appblocker.blocker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The boring black hold screen: a full-screen window with one dim round button.
 *
 * Deliberately shows NO countdown, progress or timer. The only feedback is the button
 * turning slightly lighter while pressed, so the user knows the press registered.
 *
 * This class only draws the screen and reports touches; the timing lives in the
 * service ([HoldTimer]). One instance = one window on one display. Today the service
 * makes a single one for the default display; per-display overlays would mean one
 * instance per display, each built from a display-specific context.
 *
 * [context] MUST be the AccessibilityService itself: its WindowManager carries the
 * service's window token, which is what allows TYPE_ACCESSIBILITY_OVERLAY windows
 * without any extra permission.
 */
class HoldOverlay(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** Finger went down on the button. */
        fun onHoldStarted()

        /** The press ended early: lifted, cancelled by the system, or slid off the button. */
        fun onHoldStopped()

        /** Back key or back gesture while the hold screen has focus. */
        fun onBackPressed()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var button: TextView? = null
    private var buttonShape: GradientDrawable? = null

    /** Pointer id of the finger holding the button, or NO_POINTER. */
    private var trackedPointerId = NO_POINTER

    /** An OnBackInvokedCallback on Android 13+ (typed Any so this class loads on Android 12). */
    private var backCallback: Any? = null

    val isShowing: Boolean get() = root != null

    /** Adds the window. Does nothing if it is already showing. Returns false if adding failed. */
    fun show(): Boolean {
        if (root != null) return true
        val view = buildViews()
        return try {
            windowManager.addView(view, buildLayoutParams())
            root = view
            true
        } catch (e: RuntimeException) {
            // e.g. BadTokenException if the service is being disconnected.
            Log.w(TAG, "Could not add hold screen", e)
            button = null
            buttonShape = null
            false
        }
    }

    /** Removes the window if it is showing. */
    fun hide() {
        val view = root ?: return
        root = null
        button = null
        buttonShape = null
        trackedPointerId = NO_POINTER
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove hold screen", e)
        }
    }

    /**
     * Forget the current press without telling the listener (the service calls this
     * when IT resets the hold, e.g. screen off or fold/unfold). The finger has to be
     * lifted and pressed again to start a new hold.
     */
    fun cancelPress() {
        trackedPointerId = NO_POINTER
        setPressedLook(false)
    }

    /** Re-apply window size/insets and button size, e.g. after fold/unfold or rotation. */
    fun relayout() {
        val view = root ?: return
        applySizes()
        try {
            windowManager.updateViewLayout(view, buildLayoutParams())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not relayout hold screen", e)
        }
    }

    // ---------------------------------------------------------------- window

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // Allowed for accessibility services without SYSTEM_ALERT_WINDOW, drawn above
            // apps, status bar and navigation bar, and not subject to background-launch limits.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Without this, a focusable window steals touches from the WHOLE screen,
                // including a 3-button navigation bar we deliberately leave uncovered.
                // Touches inside our window are still all consumed by our views.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // The screen must not time out in the middle of a 2-minute hold.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            // Note: NOT FLAG_NOT_FOCUSABLE. The window must take input focus so it
            // receives Back. NOT FLAG_SECURE (screenshots are fine).
            // NOT FLAG_LAYOUT_NO_LIMITS: it would override the nav-bar gap below.
            PixelFormat.OPAQUE,
        )
        params.gravity = Gravity.TOP or Gravity.START
        // Cover the camera cutout area too.
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Cover status bar and gesture-navigation area. But a 3-button navigation bar (or
        // the taskbar on the unfolded screen) is BELOW this window type in z-order, so
        // covering it would leave no way to press Home/Back. In that case leave a gap.
        params.fitInsetsTypes =
            if (hasTappableNavigationBar()) WindowInsets.Type.navigationBars() else 0
        // Keep the gap even while an immersive app has the nav bar hidden, so it can
        // always be revealed and tapped.
        params.isFitInsetsIgnoringVisibility = true
        params.title = "AppBlocker hold screen"
        return params
    }

    /**
     * True for 3-button navigation / taskbar (bar has tappable buttons), false for gesture
     * navigation (gestures are detected by the system above all windows, so covering the
     * thin gesture strip is safe). If we can't tell, leave the bar uncovered (safe side).
     */
    private fun hasTappableNavigationBar(): Boolean = try {
        val insets = windowManager.currentWindowMetrics.windowInsets
        val tappable = insets.getInsetsIgnoringVisibility(WindowInsets.Type.tappableElement())
        // Ignore the top edge: the status bar always counts as tappable.
        tappable.bottom > 0 || tappable.left > 0 || tappable.right > 0
    } catch (e: RuntimeException) {
        true
    }

    // ---------------------------------------------------------------- views

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildViews(): View {
        val rootView = object : FrameLayout(context) {
            // Back arrives here as a key event when the app has not opted in to the
            // Android 13+ predictive-back callbacks.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        listener.onBackPressed()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                registerBackCallback(this)
            }

            override fun onDetachedFromWindow() {
                unregisterBackCallback(this)
                super.onDetachedFromWindow()
            }
        }
        rootView.setBackgroundColor(Color.BLACK)
        // Swallow every touch that isn't on the button, so the app underneath can't be used.
        rootView.setOnTouchListener { _, _ -> true }

        // Everything sits in one centred column.
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ---- QUOTE GOES HERE ---------------------------------------------------
        // To show a random quote later, create a TextView here (dim grey text, no
        // animation) and add it to `column` before the button, e.g.:
        //
        //   val quote = TextView(context).apply {
        //       text = pickRandomQuote()
        //       setTextColor(TEXT_COLOR)
        //       gravity = Gravity.CENTER
        //   }
        //   column.addView(quote, LinearLayout.LayoutParams(
        //       ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // -------------------------------------------------------------------------

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BUTTON_COLOR)
        }
        val holdButton = TextView(context).apply {
            text = "Hold"
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            background = circle
            setOnTouchListener { v, e ->
                onButtonTouch(v, e)
                true // the button consumes all its touches
            }
        }
        val size = dp(BUTTON_SIZE_DP)
        column.addView(holdButton, LinearLayout.LayoutParams(size, size))

        rootView.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        button = holdButton
        buttonShape = circle
        trackedPointerId = NO_POINTER
        applySizes()
        return rootView
    }

    private fun applySizes() {
        val b = button ?: return
        val size = dp(BUTTON_SIZE_DP)
        b.layoutParams?.let {
            it.width = size
            it.height = size
            b.layoutParams = it
        }
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
    }

    private fun onButtonTouch(v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (trackedPointerId != NO_POINTER) endPress() // stale press, shouldn't happen
                trackedPointerId = e.getPointerId(0)
                setPressedLook(true)
                listener.onHoldStarted()
            }

            MotionEvent.ACTION_MOVE -> {
                if (trackedPointerId == NO_POINTER) return
                val index = e.findPointerIndex(trackedPointerId)
                // Sliding off the button counts as letting go.
                if (index < 0 || !isInside(v, e.getX(index), e.getY(index))) endPress()
            }

            // A second finger lifting doesn't matter; the holding finger lifting does.
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == trackedPointerId) endPress()
            }

            // CANCEL happens e.g. when a system gesture (Home swipe) takes over the touch.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPress()
        }
    }

    private fun endPress() {
        if (trackedPointerId == NO_POINTER) return
        trackedPointerId = NO_POINTER
        setPressedLook(false)
        listener.onHoldStopped()
    }

    private fun isInside(v: View, x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x <= v.width && y <= v.height

    private fun setPressedLook(pressed: Boolean) {
        buttonShape?.setColor(if (pressed) BUTTON_PRESSED_COLOR else BUTTON_COLOR)
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    ).toInt()

    // ---------------------------------------------------------------- back (Android 13+)

    // If the app opts in to predictive back (android:enableOnBackInvokedCallback in the
    // manifest), Back is NOT delivered as a key event any more, only to callbacks like
    // this one. If it hasn't opted in, the system ignores this callback and the key path
    // above handles Back. Either way exactly one of them fires.
    private fun registerBackCallback(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback { listener.onBackPressed() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    private fun unregisterBackCallback(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = backCallback as? OnBackInvokedCallback ?: return
        view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
        backCallback = null
    }

    private companion object {
        const val TAG = "HoldOverlay"
        const val NO_POINTER = -1

        // Big enough to hold comfortably, small enough for the narrow cover screen.
        const val BUTTON_SIZE_DP = 180f
        const val TEXT_SIZE_SP = 20f

        // Dim greys on black. Pressed is only slightly lighter: the one permitted feedback.
        val BUTTON_COLOR = Color.rgb(38, 38, 38)
        val BUTTON_PRESSED_COLOR = Color.rgb(58, 58, 58)
        val TEXT_COLOR = Color.rgb(110, 110, 110)
    }
}
