package com.example.hotcorners

import android.content.Context

internal object HotCornersSettings {
    const val PREFERENCES_NAME = "hot_corners_settings"
    const val KEY_ENABLED = "top_left_enabled"
    const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
}
