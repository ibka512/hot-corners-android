package com.example.hotcorners

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.roundToInt

/** Creates transparent hover windows only for corners with a configured action. */
class HotCornersAccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private data class CornerState(
        var armed: Boolean = true,
        var pointerInside: Boolean = false,
        var pendingTrigger: Runnable? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cornerViews = mutableMapOf<HotCorner, View>()
    private val cornerStates = HotCorner.entries.associateWith { CornerState() }
    private var preferences: SharedPreferences? = null
    private var windowManager: WindowManager? = null
    private var enabled = HotCornersSettings.DEFAULT_ENABLED

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(HotCornersSettings.PREFERENCES_NAME, MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(this) }
        enabled = HotCornersSettings.isEnabled(this)
        refreshAvailableActions()
        rebuildCornerOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Gravity-based windows need to be re-added after rotation or size changes.
        rebuildCornerOverlays()
    }

    override fun onSystemActionsChanged() {
        super.onSystemActionsChanged()
        refreshAvailableActions()
        rebuildCornerOverlays()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == HotCornersSettings.KEY_DWELL_TIME_MS) {
            cornerStates.forEach { (corner, state) ->
                if (state.pointerInside && state.armed) {
                    cancelPendingTrigger(state)
                    scheduleActionIfArmed(corner, state)
                }
            }
        } else if (key == HotCornersSettings.KEY_ENABLED) {
            enabled = HotCornersSettings.isEnabled(this)
            rebuildCornerOverlays(resetArming = true)
        } else {
            HotCorner.fromActionPreferenceKey(key)?.let(::updateCornerOverlay)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        removeCornerOverlays(resetArming = true)
        super.onDestroy()
    }

    private fun refreshAvailableActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val actionIds = getSystemActions().map { it.id }.toSet()
        preferences?.edit()
            ?.putStringSet(HotCornersSettings.KEY_AVAILABLE_ACTION_IDS, actionIds.map(Int::toString).toSet())
            ?.apply()
    }

    private fun rebuildCornerOverlays(resetArming: Boolean = false) {
        removeCornerOverlays(resetArming)
        if (!enabled) return

        val manager = windowManager ?: return
        val sizePx = (HOTSPOT_SIZE_DP * resources.displayMetrics.density).roundToInt()
        for (corner in HotCorner.entries) {
            if (!addCornerOverlay(corner, manager, sizePx)) {
                removeCornerOverlays()
                return
            }
        }
    }

    private fun updateCornerOverlay(corner: HotCorner) {
        val state = cornerStates.getValue(corner)
        cancelPendingTrigger(state)
        state.pointerInside = false
        state.armed = true

        cornerViews.remove(corner)?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // The window may already have been detached during a service restart.
            }
        }

        if (!enabled) return
        val manager = windowManager ?: return
        val sizePx = (HOTSPOT_SIZE_DP * resources.displayMetrics.density).roundToInt()
        if (!addCornerOverlay(corner, manager, sizePx)) {
            removeCornerOverlays()
        }
    }

    private fun addCornerOverlay(
        corner: HotCorner,
        manager: WindowManager,
        sizePx: Int,
    ): Boolean {
        val action = HotCornersSettings.getAction(this, corner)
        if (action == CornerAction.NONE || !action.isAvailableOn(this)) return true

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        return try {
            manager.addView(view, params)
            cornerViews[corner] = view
            true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not add $corner accessibility overlay", exception)
            false
        }
    }

    private fun removeCornerOverlays(resetArming: Boolean = false) {
        cornerStates.values.forEach { state ->
            state.pendingTrigger?.let(mainHandler::removeCallbacks)
            state.pendingTrigger = null
            state.pointerInside = false
            if (resetArming) state.armed = true
        }

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

    private fun onCornerHover(corner: HotCorner, event: MotionEvent): Boolean {
        // Ignore touchscreens, styluses, trackballs, and all non-mouse sources.
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val state = cornerStates.getValue(corner)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!state.pointerInside) {
                    state.pointerInside = true
                    scheduleActionIfArmed(corner, state)
                }
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                state.pointerInside = false
                cancelPendingTrigger(state)
                // Each corner is rearmed only when the mouse leaves that corner.
                state.armed = true
            }

            else -> return false
        }
        return true
    }

    private fun scheduleActionIfArmed(corner: HotCorner, state: CornerState) {
        if (!enabled || !state.armed || state.pendingTrigger != null) return

        val trigger = Runnable {
            state.pendingTrigger = null
            if (!enabled || !state.pointerInside || !state.armed) return@Runnable

            val action = HotCornersSettings.getAction(this, corner)
            // Disarm before calling Android so repeated hover events cannot retrigger.
            state.armed = false
            if (action.launchesApp) {
                val packageName = HotCornersSettings.getAppPackage(this, corner)
                if (packageName == null) {
                    Toast.makeText(this, R.string.app_not_selected, Toast.LENGTH_SHORT).show()
                    return@Runnable
                }
                when (CornerAppLauncher.launch(this, packageName, action.requestsSmallWindow)) {
                    AppLaunchResult.STARTED -> Unit
                    AppLaunchResult.APP_NOT_FOUND -> Toast.makeText(
                        this,
                        R.string.selected_app_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                    AppLaunchResult.START_REJECTED -> Toast.makeText(
                        this,
                        R.string.app_launch_rejected,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                Log.i(TAG, "$corner triggered $action for $packageName")
                return@Runnable
            }

            val actionId = action.globalActionId() ?: return@Runnable
            val performed = performGlobalAction(actionId)
            Log.i(TAG, "$corner triggered $action; performed=$performed")
        }
        state.pendingTrigger = trigger
        mainHandler.postDelayed(trigger, HotCornersSettings.getDwellTimeMs(this).toLong())
    }

    private fun cancelPendingTrigger(state: CornerState) {
        state.pendingTrigger?.let(mainHandler::removeCallbacks)
        state.pendingTrigger = null
    }

    private inner class HoverCornerView(
        private val corner: HotCorner,
    ) : View(this@HotCornersAccessibilityService) {
        init {
            // Transparent surface; only its input bounds are used for hover detection.
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isClickable = false
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean =
            onCornerHover(corner, event) || super.onGenericMotionEvent(event)
    }

    private companion object {
        const val TAG = "HotCornersService"
        const val HOTSPOT_SIZE_DP = 24
    }
}
