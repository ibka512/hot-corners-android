package com.example.hotcorners

import android.content.Context

/** Persistent settings shared by the activity and the accessibility service. */
internal object HotCornersSettings {
    const val PREFERENCES_NAME = "hot_corners_settings"

    // Keep this key unchanged so existing installs retain their master on/off setting.
    const val KEY_ENABLED = "top_left_enabled"
    const val DEFAULT_ENABLED = true

    const val KEY_AVAILABLE_ACTION_IDS = "available_system_action_ids"

    fun actionKey(corner: HotCorner): String = "action_${corner.preferenceId}"

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
