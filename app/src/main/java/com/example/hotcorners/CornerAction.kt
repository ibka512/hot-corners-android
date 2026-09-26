package com.example.hotcorners

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent

/** Screen corner and its persistent settings identity. */
internal enum class HotCorner(
    val preferenceId: String,
    val gravity: Int,
    val labelResId: Int,
    val defaultAction: CornerAction,
) {
    TOP_LEFT("top_left", Gravity.TOP or Gravity.LEFT, R.string.corner_top_left, CornerAction.RECENTS),
    TOP_RIGHT("top_right", Gravity.TOP or Gravity.RIGHT, R.string.corner_top_right, CornerAction.NONE),
    BOTTOM_LEFT("bottom_left", Gravity.BOTTOM or Gravity.LEFT, R.string.corner_bottom_left, CornerAction.NONE),
    BOTTOM_RIGHT("bottom_right", Gravity.BOTTOM or Gravity.RIGHT, R.string.corner_bottom_right, CornerAction.NONE)
    ;

    companion object {
        fun fromPreferenceKey(key: String?): HotCorner? =
            entries.firstOrNull {
                HotCornersSettings.keyBelongsToCorner(key, it)
            }
    }
}

/** Input accepted by a configured corner. Mouse button triggers run on press. */
internal enum class CornerTrigger(
    val id: String,
    val labelResId: Int,
    val summaryResId: Int,
    val button: Int? = null,
) {
    HOVER("hover", R.string.trigger_hover, R.string.trigger_summary_hover),
    LEFT_BUTTON(
        "left_button",
        R.string.trigger_left_button,
        R.string.trigger_summary_left_button,
        MotionEvent.BUTTON_PRIMARY,
    ),
    RIGHT_BUTTON(
        "right_button",
        R.string.trigger_right_button,
        R.string.trigger_summary_right_button,
        MotionEvent.BUTTON_SECONDARY,
    ),
    MIDDLE_BUTTON(
        "middle_button",
        R.string.trigger_middle_button,
        R.string.trigger_summary_middle_button,
        MotionEvent.BUTTON_TERTIARY,
    ),
    BACK_BUTTON(
        "back_button",
        R.string.trigger_back_button,
        R.string.trigger_summary_back_button,
        MotionEvent.BUTTON_BACK,
    ),
    FORWARD_BUTTON(
        "forward_button",
        R.string.trigger_forward_button,
        R.string.trigger_summary_forward_button,
        MotionEvent.BUTTON_FORWARD,
    ),
    ;

    companion object {
        val mouseButtons: List<CornerTrigger>
            get() = entries.filter { it.button != null }

        fun fromId(id: String?): CornerTrigger? = entries.firstOrNull { it.id == id }

        fun fromButton(button: Int): CornerTrigger? =
            mouseButtons.firstOrNull { it.button == button }
    }
}

/** Actions supported by the current demo. IDs are stored instead of localized labels. */
internal enum class CornerAction(
    val id: String,
    val labelResId: Int,
    private val minimumApi: Int,
) {
    NONE("none", R.string.action_none, 26),
    BACK("back", R.string.action_back, 26),
    HOME("home", R.string.action_home, 26),
    RECENTS("recents", R.string.action_recents, 26),
    NOTIFICATIONS("notifications", R.string.action_notifications, 26),
    QUICK_SETTINGS("quick_settings", R.string.action_quick_settings, 26),
    LOCK_SCREEN("lock_screen", R.string.action_lock_screen, 28),
    SCREENSHOT("screenshot", R.string.action_screenshot, 28),
    SPLIT_SCREEN("split_screen", R.string.action_split_screen, 30),
    APP_TRAY("app_tray", R.string.action_app_tray, 26),
    OPEN_APP("open_app", R.string.action_open_app, 26),
    OPEN_APP_IN_WINDOW("open_app_in_window", R.string.action_open_app_in_window, 26)
    ;

    val launchesApp: Boolean
        get() = this == OPEN_APP || this == OPEN_APP_IN_WINDOW

    val opensAppTray: Boolean
        get() = this == APP_TRAY

    val requestsSmallWindow: Boolean
        get() = this == OPEN_APP_IN_WINDOW

    fun globalActionId(): Int? = when (this) {
        NONE -> null
        BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        APP_TRAY -> null
        LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        } else {
            null
        }
        SCREENSHOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        } else {
            null
        }
        SPLIT_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        } else {
            null
        }
        OPEN_APP, OPEN_APP_IN_WINDOW -> null
    }

    fun isSupportedByOs(): Boolean = Build.VERSION.SDK_INT >= minimumApi

    /**
     * API 30+ can report which global actions are available on this device right now.
     * Older versions fall back to the API level that introduced each action.
     */
    fun isAvailableOn(service: AccessibilityService): Boolean {
        if (this == NONE || !isSupportedByOs()) return this == NONE
        if (opensAppTray) return true
        if (launchesApp) return true
        val actionId = globalActionId() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.systemActions.any { it.id == actionId }
        } else {
            true
        }
    }

    companion object {
        fun fromId(id: String): CornerAction? = entries.firstOrNull { it.id == id }
    }
}
