package com.example.hotcorners

import android.content.Context

/** Persistent settings shared by the activity and the accessibility service. */
internal object HotCornersSettings {
    const val PREFERENCES_NAME = "hot_corners_settings"

    // Keep this key unchanged so existing installs retain their master on/off setting.
    const val KEY_ENABLED = "top_left_enabled"
    const val DEFAULT_ENABLED = true

    const val KEY_AVAILABLE_ACTION_IDS = "available_system_action_ids"
    const val KEY_DWELL_TIME_MS = "dwell_time_ms"
    const val DEFAULT_DWELL_TIME_MS = 300
    const val MIN_DWELL_TIME_MS = 0
    const val MAX_DWELL_TIME_MS = 1000
    const val DWELL_TIME_STEP_MS = 50

    fun actionKey(corner: HotCorner): String = "action_${corner.preferenceId}"
    fun triggerKey(corner: HotCorner): String = "trigger_${corner.preferenceId}"
    fun appPackageKey(corner: HotCorner): String = "app_package_${corner.preferenceId}"

    fun getTrigger(context: Context, corner: HotCorner): CornerTrigger {
        val storedValue = preferences(context).getString(triggerKey(corner), null)
        return if (storedValue == null) {
            CornerTrigger.HOVER
        } else {
            CornerTrigger.fromId(storedValue) ?: CornerTrigger.HOVER
        }
    }

    fun getAppPackage(context: Context, corner: HotCorner): String? =
        preferences(context).getString(appPackageKey(corner), null)

    fun getDwellTimeMs(context: Context): Int = preferences(context)
        .getInt(KEY_DWELL_TIME_MS, DEFAULT_DWELL_TIME_MS)
        .coerceIn(MIN_DWELL_TIME_MS, MAX_DWELL_TIME_MS)

    fun getAction(context: Context, corner: HotCorner): CornerAction {
        val storedValue = preferences(context).getString(actionKey(corner), null)
        return if (storedValue == null) {
            corner.defaultAction
        } else {
            CornerAction.fromId(storedValue) ?: CornerAction.NONE
        }
    }

    fun getAvailableActionIds(context: Context): Set<Int>? =
        preferences(context).getStringSet(KEY_AVAILABLE_ACTION_IDS, null)
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
