package com.example.hotcorners

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
    private lateinit var featureSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildSettingsScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    private fun buildSettingsScreen() {
        val spacing = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 28f
        }
        root.addView(title, matchWidth())

        val summary = TextView(this).apply {
            text = getString(R.string.settings_summary)
            textSize = 16f
        }
        root.addView(summary, spacedMatchWidth(spacing))

        featureSwitch = Switch(this).apply {
            text = getString(R.string.enable_hot_corner)
            isChecked = HotCornersSettings.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(HotCornersSettings.PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(HotCornersSettings.KEY_ENABLED, checked)
                    .apply()
            }
        }
        root.addView(featureSwitch, spacedMatchWidth(spacing))

        serviceStatus = TextView(this).apply {
            textSize = 14f
        }
        root.addView(serviceStatus, spacedMatchWidth(spacing))

        val accessibilityButton = Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(accessibilityButton, spacedMatchWidth(spacing))

        val instructions = TextView(this).apply {
            text = getString(R.string.service_steps)
            textSize = 14f
        }
        root.addView(instructions, spacedMatchWidth(spacing))

        val note = TextView(this).apply {
            text = getString(R.string.other_corners_note)
            textSize = 14f
        }
        root.addView(note, spacedMatchWidth(spacing))

        root.setOnApplyWindowInsetsListener { view, insets ->
            // Android 15 enforces edge-to-edge for target SDK 35. Add the system-bar
            // insets to the normal screen content while leaving corner overlays separate.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(dp(24), dp(24) + bars.top, dp(24), dp(24) + bars.bottom)
            }
            insets
        }
        setContentView(root)
    }

    private fun refreshServiceStatus() {
        if (!::serviceStatus.isInitialized) return
        val enabled = isHotCornersServiceEnabled()
        serviceStatus.text = getString(
            if (enabled) R.string.accessibility_status_on else R.string.accessibility_status_off,
        )
        serviceStatus.contentDescription = serviceStatus.text
    }

    private fun isHotCornersServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, HotCornersAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                service.resolveInfo.serviceInfo.let { info ->
                    info.packageName == expected.packageName && info.name == expected.className
                }
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun spacedMatchWidth(topSpacing: Int): LinearLayout.LayoutParams =
        matchWidth().apply { topMargin = topSpacing }
}
