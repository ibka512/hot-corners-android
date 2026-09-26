package com.example.hotcorners

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var serviceStatus: TextView
    private lateinit var featureSwitch: Switch
    private lateinit var preferences: SharedPreferences
    private val actionButtons = mutableMapOf<HotCorner, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(HotCornersSettings.PREFERENCES_NAME, MODE_PRIVATE)
        buildSettingsScreen()
    }

    override fun onResume() {
        super.onResume()
        preferences.registerOnSharedPreferenceChangeListener(this)
        refreshServiceStatus()
        refreshActionButtons()
    }

    override fun onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null || key == HotCornersSettings.KEY_AVAILABLE_ACTION_IDS) {
            refreshActionButtons()
        }
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
                preferences.edit()
                    .putBoolean(HotCornersSettings.KEY_ENABLED, checked)
                    .apply()
            }
        }
        root.addView(featureSwitch, spacedMatchWidth(spacing))

        val cornerActionsTitle = TextView(this).apply {
            text = getString(R.string.corner_actions_title)
            textSize = 20f
        }
        root.addView(cornerActionsTitle, spacedMatchWidth(spacing))

        val cornerActionsHint = TextView(this).apply {
            text = getString(R.string.corner_actions_hint)
            textSize = 14f
        }
        root.addView(cornerActionsHint, spacedMatchWidth(dp(8)))

        HotCorner.entries.forEach { corner ->
            val button = Button(this).apply {
                isAllCaps = false
                setOnClickListener { showActionPicker(corner) }
            }
            actionButtons[corner] = button
            root.addView(button, spacedMatchWidth(dp(8)))
        }

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

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                // Android 15 enforces edge-to-edge for target SDK 35. Keep the
                // scrollable settings content clear of system bars.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    view.setPadding(0, bars.top, 0, bars.bottom)
                }
                insets
            }
        }
        setContentView(scrollView)
        refreshActionButtons()
    }

    private fun refreshServiceStatus() {
        if (!::serviceStatus.isInitialized) return
        val enabled = isHotCornersServiceEnabled()
        serviceStatus.text = getString(
            if (enabled) R.string.accessibility_status_on else R.string.accessibility_status_off,
        )
        serviceStatus.contentDescription = serviceStatus.text
    }

    private fun refreshActionButtons() {
        if (actionButtons.isEmpty()) return
        HotCorner.entries.forEach { corner ->
            val selectedAction = HotCornersSettings.getAction(this, corner)
            val available = isAvailableInPicker(selectedAction)
            val actionLabel = formatActionLabel(selectedAction, available)
            actionButtons[corner]?.apply {
                text = getString(R.string.corner_action_button, getString(corner.labelResId), actionLabel)
                contentDescription = getString(
                    R.string.corner_action_button_description,
                    getString(corner.labelResId),
                    actionLabel,
                )
            }
        }
    }

    private fun showActionPicker(corner: HotCorner) {
        val actions = selectableActions(corner)
        val selected = HotCornersSettings.getAction(this, corner)
        val labels = actions.map { action ->
            formatActionLabel(action, isAvailableInPicker(action))
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_action_title, getString(corner.labelResId)))
            .setSingleChoiceItems(labels, actions.indexOf(selected)) { dialog, which ->
                preferences.edit()
                    .putString(HotCornersSettings.actionKey(corner), actions[which].id)
                    .apply()
                refreshActionButtons()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectableActions(corner: HotCorner): List<CornerAction> {
        val choices = CornerAction.entries.filter { action ->
            action == CornerAction.NONE || (action.isSupportedByOs() && isAvailableInPicker(action))
        }.toMutableList()
        val current = HotCornersSettings.getAction(this, corner)
        if (current !in choices) choices.add(current)
        return choices
    }

    private fun isAvailableInPicker(action: CornerAction): Boolean {
        if (action == CornerAction.NONE) return true
        if (!action.isSupportedByOs()) return false

        val reportedActions = HotCornersSettings.getAvailableActionIds(this)
        val serviceEnabled = isHotCornersServiceEnabled()
        val actionId = action.globalActionId() ?: return false

        if (action == CornerAction.SPLIT_SCREEN) {
            // Split screen is only effective when the active system action list
            // explicitly advertises it. The list is cached by the service.
            return reportedActions?.contains(actionId) == true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && serviceEnabled && reportedActions != null) {
            return reportedActions.contains(actionId)
        }
        return true
    }

    private fun formatActionLabel(action: CornerAction, available: Boolean): String {
        val label = getString(action.labelResId)
        return if (available) label else getString(R.string.action_unavailable_format, label)
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
