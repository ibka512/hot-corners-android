package com.example.hotcorners

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Point
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

/** Creates transparent mouse-input windows only for corners with a configured action. */
class HotCornersAccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private data class CornerState(
        var armed: Boolean = true,
        var pointerInside: Boolean = false,
        var hoverExitWasButton: Boolean = false,
        var pendingTrigger: Runnable? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cornerViews = mutableMapOf<HotCorner, View>()
    private val cornerStates = HotCorner.entries.associateWith { CornerState() }
    private var preferences: SharedPreferences? = null
    private var windowManager: WindowManager? = null
    private var appTrayOverlay: CornerAppTrayOverlay? = null
    private var enabled = HotCornersSettings.DEFAULT_ENABLED

    override fun onServiceConnected() {
        super.onServiceConnected()
        HotCornersSettings.ensureMigrated(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(HotCornersSettings.PREFERENCES_NAME, MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(this) }
        enabled = HotCornersSettings.isEnabled(this)
        refreshAvailableActions()
        rebuildCornerOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeAppTrayOverlay()
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
            HotCorner.fromPreferenceKey(key)?.let(::updateCornerOverlay)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        removeAppTrayOverlay()
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
        removeAppTrayOverlay()
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
        state.hoverExitWasButton = false
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
        val actions = listOf(HotCornersSettings.getHoverAction(this, corner)) +
            HotCornersSettings.getButtonActions(this, corner).values
        if (actions.none { it != CornerAction.NONE && it.isAvailableOn(this) }) return true

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
            state.hoverExitWasButton = false
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
                    if (state.hoverExitWasButton) {
                        // A click causes HOVER_EXIT before its button event. Keep the existing
                        // armed state when hover resumes after that click.
                        state.hoverExitWasButton = false
                        scheduleActionIfArmed(corner, state)
                        return true
                    }
                    // Rearm on entry only after a genuine leave, not on a click transition.
                    state.armed = true
                    scheduleActionIfArmed(corner, state)
                }
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                state.pointerInside = false
                state.hoverExitWasButton = false
                cancelPendingTrigger(state)
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (!state.pointerInside) state.hoverExitWasButton = true
                val trigger = CornerTrigger.fromButton(event.actionButton)
                if (trigger != null && HotCornersSettings.getButtonAction(this, corner, trigger) != null) {
                    // Android reports button transitions here separately from pointer-down events.
                    // Handling press only means a held button or its release cannot fire twice.
                    performCornerAction(corner, trigger)
                    Log.i(TAG, "$corner triggered by $trigger")
                    return true
                }
                return false
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                // Releases never execute an action; consume releases for configured buttons.
                val trigger = CornerTrigger.fromButton(event.actionButton)
                return trigger != null && HotCornersSettings.getButtonAction(this, corner, trigger) != null
            }

            else -> return false
        }
        return true
    }

    private fun scheduleActionIfArmed(corner: HotCorner, state: CornerState) {
        if (!enabled || !state.armed || state.pendingTrigger != null) return
        val hoverAction = HotCornersSettings.getHoverAction(this, corner)
        if (hoverAction == CornerAction.NONE || !hoverAction.isAvailableOn(this)) return

        val trigger = Runnable {
            state.pendingTrigger = null
            if (!enabled || !state.pointerInside || !state.armed) return@Runnable

            // Disarm before calling Android so repeated hover events cannot retrigger.
            state.armed = false
            performCornerAction(corner, CornerTrigger.HOVER)
        }
        state.pendingTrigger = trigger
        mainHandler.postDelayed(trigger, HotCornersSettings.getDwellTimeMs(this).toLong())
    }

    private fun performCornerAction(corner: HotCorner, trigger: CornerTrigger) {
        val action = if (trigger == CornerTrigger.HOVER) {
            HotCornersSettings.getHoverAction(this, corner)
        } else {
            HotCornersSettings.getButtonAction(this, corner, trigger) ?: return
        }
        if (action == CornerAction.NONE || !action.isAvailableOn(this)) return

        if (action.opensAppTray) {
            showAppTray(corner)
            Log.i(TAG, "$corner opened the app tray")
            return
        }

        if (action.launchesApp) {
            val packageName = HotCornersSettings.getAppPackage(this, corner, trigger)
            if (packageName == null) {
                Toast.makeText(this, R.string.app_not_selected, Toast.LENGTH_SHORT).show()
                return
            }
            val launchResult = CornerAppLauncher.launch(
                this,
                packageName,
                action.requestsSmallWindow,
                onComplete = ::showAppLaunchResult,
            )
            if (launchResult != AppLaunchResult.PENDING) showAppLaunchResult(launchResult)
            Log.i(TAG, "$corner triggered $action for $packageName")
            return
        }

        val actionId = action.globalActionId() ?: return
        val performed = performGlobalAction(actionId)
        Log.i(TAG, "$corner triggered $action; performed=$performed")
    }

    private fun showAppTray(corner: HotCorner) {
        if (appTrayOverlay != null) return

        val selectedPackages = HotCornersSettings.getAppTrayPackages(this)
        val appsByPackage = LaunchableAppRepository.load(this).associateBy(LaunchableApp::packageName)
        val apps = selectedPackages.mapNotNull(appsByPackage::get)
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.app_tray_missing_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val tray = CornerAppTrayOverlay(
            context = this,
            corner = corner,
            apps = apps,
            onOpenApp = { app -> openTrayApp(app, smallWindow = false) },
            onOpenSmallWindow = { app, x, y ->
                openTrayApp(app, smallWindow = true, preferredCenter = Point(x, y))
            },
            onDismiss = ::closeAppTray,
        )
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        try {
            windowManager?.addView(tray, layoutParams) ?: return
            appTrayOverlay = tray
            // The full-screen layer hides hover-exit events from the underlying corner window.
            // Mark the pointer as outside so the next genuine entry can arm that corner again.
            cornerStates.getValue(corner).apply {
                cancelPendingTrigger(this)
                pointerInside = false
                hoverExitWasButton = false
            }
            tray.post {
                if (appTrayOverlay === tray) tray.animateIn()
            }
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not show the app tray at $corner", exception)
            Toast.makeText(this, R.string.app_tray_overlay_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTrayApp(
        app: LaunchableApp,
        smallWindow: Boolean,
        preferredCenter: Point? = null,
    ) {
        closeAppTray {
            val launchResult = CornerAppLauncher.launch(
                this,
                app.packageName,
                smallWindow,
                preferredCenter,
                onComplete = ::showAppLaunchResult,
            )
            if (launchResult != AppLaunchResult.PENDING) showAppLaunchResult(launchResult)
        }
    }

    private fun showAppLaunchResult(result: AppLaunchResult) {
        val message = when (result) {
            AppLaunchResult.STARTED,
            AppLaunchResult.PENDING -> return
            AppLaunchResult.APP_NOT_FOUND -> R.string.selected_app_unavailable
            AppLaunchResult.START_REJECTED -> R.string.app_launch_rejected
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun closeAppTray() {
        closeAppTray(afterClosed = null)
    }

    private fun closeAppTray(afterClosed: (() -> Unit)?) {
        val tray = appTrayOverlay
        if (tray == null) {
            afterClosed?.invoke()
            return
        }
        appTrayOverlay = null
        tray.animateOut {
            try {
                windowManager?.removeViewImmediate(tray)
            } catch (_: IllegalArgumentException) {
                // The service can be disconnected while the tray is dismissing.
            }
            afterClosed?.invoke()
        }
    }

    private fun removeAppTrayOverlay() {
        val tray = appTrayOverlay ?: return
        appTrayOverlay = null
        tray.animate().cancel()
        try {
            windowManager?.removeViewImmediate(tray)
        } catch (_: IllegalArgumentException) {
            // The window may already have been detached during a service restart.
        }
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
