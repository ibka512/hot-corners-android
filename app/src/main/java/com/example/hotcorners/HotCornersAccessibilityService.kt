package com.example.hotcorners

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

/**
 * Creates four transparent, mouse-hoverable edge windows. The first demo action is
 * intentionally limited to top-left -> Recent apps.
 */
class HotCornersAccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private enum class Corner(val gravity: Int, val triggersRecents: Boolean) {
        TOP_LEFT(Gravity.TOP or Gravity.LEFT, true),
        TOP_RIGHT(Gravity.TOP or Gravity.RIGHT, false),
        BOTTOM_LEFT(Gravity.BOTTOM or Gravity.LEFT, false),
        BOTTOM_RIGHT(Gravity.BOTTOM or Gravity.RIGHT, false),
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cornerViews = mutableMapOf<Corner, View>()
    private var preferences: SharedPreferences? = null
    private var windowManager: WindowManager? = null
    private var enabled = HotCornersSettings.DEFAULT_ENABLED
    private var topLeftArmed = true
    private var topLeftPointerInside = false
    private var pendingTrigger: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(HotCornersSettings.PREFERENCES_NAME, MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(this) }
        enabled = HotCornersSettings.isEnabled(this)
        rebuildCornerOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-anchor gravity-based windows after rotation or a size-class change.
        rebuildCornerOverlays()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == HotCornersSettings.KEY_ENABLED) {
            enabled = HotCornersSettings.isEnabled(this)
            rebuildCornerOverlays()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        removeCornerOverlays()
        super.onDestroy()
    }

    private fun rebuildCornerOverlays() {
        removeCornerOverlays()
        if (!enabled) return

        val manager = windowManager ?: return
        val sizePx = (HOTSPOT_SIZE_DP * resources.displayMetrics.density).roundToInt()
        Corner.entries.forEach { corner ->
            val view = HoverCornerView(corner)
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = corner.gravity
                alpha = 1f
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            try {
                manager.addView(view, params)
                cornerViews[corner] = view
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Could not add $corner accessibility overlay", exception)
                removeCornerOverlays()
                return
            }
        }
    }

    private fun removeCornerOverlays() {
        cancelPendingTrigger()
        topLeftPointerInside = false
        topLeftArmed = true
        val manager = windowManager
        cornerViews.values.toList().forEach { view ->
            try {
                manager?.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // The window may already have been detached during a service restart.
            }
        }
        cornerViews.clear()
    }

    private fun onCornerHover(corner: Corner, event: MotionEvent): Boolean {
        // Do not react to touchscreens, styluses, trackballs, or other pointer sources.
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (corner.triggersRecents && !topLeftPointerInside) {
                    topLeftPointerInside = true
                    scheduleRecentsIfArmed()
                }
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                if (corner.triggersRecents) {
                    topLeftPointerInside = false
                    cancelPendingTrigger()
                    // Rearm only after the mouse leaves the top-left hotspot.
                    topLeftArmed = true
                }
            }

            else -> return false
        }
        return true
    }

    private fun scheduleRecentsIfArmed() {
        if (!enabled || !topLeftArmed || pendingTrigger != null) return

        val trigger = Runnable {
            pendingTrigger = null
            if (!enabled || !topLeftPointerInside || !topLeftArmed) return@Runnable

            // Lock before invoking the system action so multiple hover-move events
            // cannot cause repeated calls while the pointer remains in the corner.
            topLeftArmed = false
            val performed = performGlobalAction(GLOBAL_ACTION_RECENTS)
            Log.i(TAG, "Top-left hover triggered Recent apps; performed=$performed")
        }
        pendingTrigger = trigger
        mainHandler.postDelayed(trigger, DWELL_TIME_MS)
    }

    private fun cancelPendingTrigger() {
        pendingTrigger?.let(mainHandler::removeCallbacks)
        pendingTrigger = null
    }

    private inner class HoverCornerView(
        private val corner: Corner,
    ) : View(this@HotCornersAccessibilityService) {
        init {
            // Leave the visual surface fully transparent; only its input bounds matter.
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isClickable = false
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            return onCornerHover(corner, event) || super.onGenericMotionEvent(event)
        }
    }

    private companion object {
        const val TAG = "HotCornersService"
        const val HOTSPOT_SIZE_DP = 24
        const val DWELL_TIME_MS = 300L
    }
}
