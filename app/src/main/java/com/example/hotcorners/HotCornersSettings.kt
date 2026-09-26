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
    private const val KEY_BINDING_SCHEMA_VERSION = "corner_binding_schema_version"
    private const val BINDING_SCHEMA_VERSION = 1
    const val DEFAULT_DWELL_TIME_MS = 300
    const val MIN_DWELL_TIME_MS = 0
    const val MAX_DWELL_TIME_MS = 1000
    const val DWELL_TIME_STEP_MS = 50

    // Legacy keys retained for the one-time migration from v1.3 and earlier.
    fun actionKey(corner: HotCorner): String = "action_${corner.preferenceId}"
    fun triggerKey(corner: HotCorner): String = "trigger_${corner.preferenceId}"
    fun appPackageKey(corner: HotCorner): String = "app_package_${corner.preferenceId}"

    fun hoverActionKey(corner: HotCorner): String = "hover_action_${corner.preferenceId}"
    fun buttonActionKey(corner: HotCorner, trigger: CornerTrigger): String =
        "button_action_${corner.preferenceId}_${trigger.id}"

    fun hoverAppPackageKey(corner: HotCorner): String = "hover_app_package_${corner.preferenceId}"
    fun buttonAppPackageKey(corner: HotCorner, trigger: CornerTrigger): String =
        "button_app_package_${corner.preferenceId}_${trigger.id}"

    fun keyBelongsToCorner(key: String?, corner: HotCorner): Boolean {
        if (key == null) return false
        return key == actionKey(corner) || key == triggerKey(corner) || key == appPackageKey(corner) ||
            key == hoverActionKey(corner) || key == hoverAppPackageKey(corner) ||
            CornerTrigger.mouseButtons.any { key == buttonActionKey(corner, it) || key == buttonAppPackageKey(corner, it) }
    }

    /** Converts the former single-trigger setting to separate hover and button bindings once. */
    fun ensureMigrated(context: Context) {
        val prefs = preferences(context)
        if (prefs.getInt(KEY_BINDING_SCHEMA_VERSION, 0) >= BINDING_SCHEMA_VERSION) return

        val editor = prefs.edit()
        HotCorner.entries.forEach { corner ->
            val oldActionValue = prefs.getString(actionKey(corner), null)
            val oldTriggerValue = prefs.getString(triggerKey(corner), null)
            if (oldActionValue == null && oldTriggerValue == null) return@forEach

            val action = if (oldActionValue == null) {
                corner.defaultAction
            } else {
                CornerAction.fromId(oldActionValue) ?: CornerAction.NONE
            }
            val trigger = oldTriggerValue?.let(CornerTrigger::fromId) ?: CornerTrigger.HOVER
            val appPackage = prefs.getString(appPackageKey(corner), null)

            if (trigger == CornerTrigger.HOVER) {
                editor.putString(hoverActionKey(corner), action.id)
                if (action.launchesApp && appPackage != null) {
                    editor.putString(hoverAppPackageKey(corner), appPackage)
                }
            } else {
                // A former button-trigger corner did not have a hover action.
                editor.putString(hoverActionKey(corner), CornerAction.NONE.id)
                if (action != CornerAction.NONE) {
                    editor.putString(buttonActionKey(corner, trigger), action.id)
                    if (action.launchesApp && appPackage != null) {
                        editor.putString(buttonAppPackageKey(corner, trigger), appPackage)
                    }
                }
            }
        }
        editor.putInt(KEY_BINDING_SCHEMA_VERSION, BINDING_SCHEMA_VERSION).apply()
    }

    fun getHoverAction(context: Context, corner: HotCorner): CornerAction {
        val storedValue = preferences(context).getString(hoverActionKey(corner), null)
        return storedValue?.let(CornerAction::fromId) ?: if (storedValue == null) {
            corner.defaultAction
        } else {
            CornerAction.NONE
        }
    }

    fun getButtonActions(context: Context, corner: HotCorner): Map<CornerTrigger, CornerAction> {
        val prefs = preferences(context)
        return CornerTrigger.mouseButtons.mapNotNull { trigger ->
            val action = prefs.getString(buttonActionKey(corner, trigger), null)
                ?.let(CornerAction::fromId)
                ?.takeIf { it != CornerAction.NONE }
            action?.let { trigger to it }
        }.toMap()
    }

    fun getButtonAction(context: Context, corner: HotCorner, trigger: CornerTrigger): CornerAction? {
        if (trigger.button == null) return null
        return preferences(context).getString(buttonActionKey(corner, trigger), null)
            ?.let(CornerAction::fromId)
            ?.takeIf { it != CornerAction.NONE }
    }

    fun getAppPackage(context: Context, corner: HotCorner, trigger: CornerTrigger? = null): String? {
        val key = if (trigger == null || trigger == CornerTrigger.HOVER) {
            hoverAppPackageKey(corner)
        } else {
            buttonAppPackageKey(corner, trigger)
        }
        return preferences(context).getString(key, null)
    }

    fun saveBinding(
        context: Context,
        corner: HotCorner,
        trigger: CornerTrigger?,
        action: CornerAction,
        appPackageName: String? = null,
    ) {
        val prefs = preferences(context)
        val hover = trigger == null || trigger == CornerTrigger.HOVER
        if (action.launchesApp && appPackageName == null) return

        val actionKey = if (hover) hoverActionKey(corner) else buttonActionKey(corner, trigger!!)
        val packageKey = if (hover) hoverAppPackageKey(corner) else buttonAppPackageKey(corner, trigger!!)
        val editor = prefs.edit()
        if (!hover && action == CornerAction.NONE) {
            editor.remove(actionKey).remove(packageKey)
        } else {
            editor.putString(actionKey, action.id)
            if (action.launchesApp) editor.putString(packageKey, appPackageName) else editor.remove(packageKey)
        }
        editor.apply()
    }

    fun removeButtonBinding(context: Context, corner: HotCorner, trigger: CornerTrigger) {
        if (trigger.button == null) return
        preferences(context).edit()
            .remove(buttonActionKey(corner, trigger))
            .remove(buttonAppPackageKey(corner, trigger))
            .apply()
    }

    fun isCornerConfigured(context: Context, corner: HotCorner): Boolean =
        getHoverAction(context, corner) != CornerAction.NONE || getButtonActions(context, corner).isNotEmpty()

    fun getDwellTimeMs(context: Context): Int = preferences(context)
        .getInt(KEY_DWELL_TIME_MS, DEFAULT_DWELL_TIME_MS)
        .coerceIn(MIN_DWELL_TIME_MS, MAX_DWELL_TIME_MS)

    fun getAvailableActionIds(context: Context): Set<Int>? =
        preferences(context).getStringSet(KEY_AVAILABLE_ACTION_IDS, null)
            ?.mapNotNull(String::toIntOrNull)
            ?.toSet()

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
