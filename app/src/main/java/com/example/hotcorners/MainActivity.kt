package com.example.hotcorners

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.math.min
import java.util.Locale

/** Native-view settings screen styled with Material 3 Expressive color and shape tokens. */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var scrollContainer: ScrollView
    private lateinit var mainContent: LinearLayout
    private lateinit var responsiveBody: LinearLayout
    private lateinit var singleColumnBody: LinearLayout
    private lateinit var wideBody: LinearLayout
    private lateinit var wideSettingsPane: LinearLayout
    private lateinit var wideActionsPane: LinearLayout
    private lateinit var overviewCard: LinearLayout
    private lateinit var switchCard: LinearLayout
    private lateinit var dwellCard: LinearLayout
    private lateinit var appTrayCard: LinearLayout
    private lateinit var freeformCard: LinearLayout
    private lateinit var appTrayCount: TextView
    private lateinit var appTrayDescription: TextView
    private lateinit var freeformStatus: TextView
    private lateinit var freeformActionButton: TextView
    private lateinit var dwellValueText: TextView
    private lateinit var dwellSeekBar: SeekBar
    private lateinit var actionsSection: LinearLayout
    private lateinit var serviceCard: LinearLayout
    private lateinit var usageNote: LinearLayout
    private lateinit var featureSwitch: Switch
    private lateinit var countText: TextView
    private lateinit var serviceTitle: TextView
    private lateinit var serviceBody: TextView
    private lateinit var serviceGlyph: StatusGlyphView
    private lateinit var overviewGlyph: CornerGlyphView
    private lateinit var actionGrid: GridLayout
    private val actionCards = mutableMapOf<HotCorner, CornerActionCard>()
    private var applyingSwitchUpdate = false
    private var updatingDwellTimeControl = false
    private var usingWideLayout: Boolean? = null
    private var safeLeftInsetPx = 0
    private var safeRightInsetPx = 0
    private var safeTopInsetPx = 0
    private var safeBottomInsetPx = 0
    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == FREEFORM_PERMISSION_REQUEST_CODE && ::freeformCard.isInitialized) {
            refreshShizukuCard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        HotCornersSettings.ensureMigrated(this)
        preferences = getSharedPreferences(HotCornersSettings.PREFERENCES_NAME, MODE_PRIVATE)
        buildSettingsScreen()
        refreshContent()
    }

    override fun onResume() {
        super.onResume()
        preferences.registerOnSharedPreferenceChangeListener(this)
        refreshContent()
    }

    override fun onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        refreshContent()
    }

    private fun buildSettingsScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        scrollContainer = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(color(R.color.md_background))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(28))
        }
        mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val availableWidth = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(280))
        mainContent.layoutParams = FrameLayout.LayoutParams(
            min(availableWidth, dp(760)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )

        val contentFrame = FrameLayout(this).apply {
            addView(mainContent)
        }
        page.addView(contentFrame, LinearLayout.LayoutParams(-1, -2))
        scrollContainer.addView(page)
        scrollContainer.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val safeInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout(),
                )
                intArrayOf(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                intArrayOf(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            val left = dp(20) + safeInsets[0]
            val right = dp(20) + safeInsets[2]
            safeLeftInsetPx = safeInsets[0]
            safeRightInsetPx = safeInsets[2]
            safeTopInsetPx = safeInsets[1]
            safeBottomInsetPx = safeInsets[3]
            view.setPadding(left, 0, right, 0)
            page.setPadding(0, dp(20) + safeInsets[1], 0, dp(28) + safeInsets[3])
            updateResponsiveLayout()
            insets
        }
        scrollContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateResponsiveLayout()
        }
        setContentView(scrollContainer)

        val eyebrow = text(getString(R.string.settings_eyebrow), 12f, R.color.md_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(R.color.md_primary_container, 100)
        }
        mainContent.addView(eyebrow, LinearLayout.LayoutParams(-2, -2))

        mainContent.addView(
            text(getString(R.string.settings_headline), 32f, R.color.md_on_surface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = -0.025f
                setLineSpacing(dp(1).toFloat(), 1.0f)
            },
            spaced(dp(16)),
        )
        mainContent.addView(
            text(getString(R.string.settings_summary), 15f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            spaced(dp(8)),
        )

        // Expressive, supportive container combines a compact visual with live setup progress.
        overviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(R.color.md_primary_container, 28)
        }
        overviewGlyph = CornerGlyphView(this, null)
        overviewCard.addView(overviewGlyph, LinearLayout.LayoutParams(dp(64), dp(64)))
        val overviewCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        countText = text("", 21f, R.color.md_on_primary_container).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        overviewCopy.addView(countText, wrap())
        overviewCopy.addView(
            text(getString(R.string.corner_actions_title), 14f, R.color.md_on_primary_container).apply {
                setPadding(0, dp(4), 0, 0)
            },
            wrap(),
        )
        overviewCard.addView(overviewCopy, LinearLayout.LayoutParams(0, -2, 1f))

        // Main enable row has a full-height click target and a native accessible switch.
        switchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(12), dp(12))
            background = rounded(R.color.md_surface_container, 24)
        }
        val switchCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        switchCopy.addView(
            text(getString(R.string.enable_hot_corner), 16f, R.color.md_on_surface).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            },
            wrap(),
        )
        switchCopy.addView(
            text(getString(R.string.hot_corner_switch_summary), 13f, R.color.md_on_surface_variant).apply {
                setPadding(0, dp(4), 0, 0)
            },
            wrap(),
        )
        switchCard.addView(switchCopy, LinearLayout.LayoutParams(0, -2, 1f))
        featureSwitch = Switch(this).apply {
            contentDescription = getString(R.string.enable_hot_corner)
            setOnCheckedChangeListener { _, checked ->
                if (!applyingSwitchUpdate) {
                    preferences.edit().putBoolean(HotCornersSettings.KEY_ENABLED, checked).apply()
                }
            }
        }
        switchCard.addView(featureSwitch, LinearLayout.LayoutParams(-2, dp(56)))

        dwellCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = rounded(R.color.md_surface_container, 24)
        }
        val dwellHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dwellHeader.addView(
            text(getString(R.string.dwell_time_title), 16f, R.color.md_on_surface).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        dwellValueText = text("", 16f, R.color.md_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        dwellHeader.addView(
            dwellValueText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dwellCard.addView(dwellHeader, wrap())
        dwellCard.addView(
            text(getString(R.string.dwell_time_summary), 13f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(4)),
        )
        dwellSeekBar = SeekBar(this).apply {
            max = (HotCornersSettings.MAX_DWELL_TIME_MS - HotCornersSettings.MIN_DWELL_TIME_MS) /
                HotCornersSettings.DWELL_TIME_STEP_MS
            progress = (HotCornersSettings.getDwellTimeMs(this@MainActivity) - HotCornersSettings.MIN_DWELL_TIME_MS) /
                HotCornersSettings.DWELL_TIME_STEP_MS
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            progressTintList = ColorStateList.valueOf(color(R.color.md_primary))
            progressBackgroundTintList = ColorStateList.valueOf(color(R.color.md_outline_variant))
            thumbTintList = ColorStateList.valueOf(color(R.color.md_primary))
        }
        dwellCard.addView(dwellSeekBar, spaced(dp(6)))
        val dwellRangeLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dwellRangeLabels.addView(
            text(getString(R.string.dwell_time_min), 12f, R.color.md_on_surface_variant),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        dwellRangeLabels.addView(
            text(getString(R.string.dwell_time_max), 12f, R.color.md_on_surface_variant).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dwellCard.addView(dwellRangeLabels, wrap())
        dwellSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingDwellTimeControl) return
                val dwellTimeMs = HotCornersSettings.MIN_DWELL_TIME_MS +
                    progress * HotCornersSettings.DWELL_TIME_STEP_MS
                updateDwellValue(dwellTimeMs)
                if (fromUser) {
                    preferences.edit().putInt(HotCornersSettings.KEY_DWELL_TIME_MS, dwellTimeMs).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        appTrayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.md_surface_container, 24)
        }
        val appTrayHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appTrayHeader.addView(
            text(getString(R.string.app_tray_title), 16f, R.color.md_on_surface).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        appTrayCount = text("", 13f, R.color.md_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        appTrayHeader.addView(appTrayCount, wrap())
        appTrayCard.addView(appTrayHeader, wrap())
        appTrayDescription = text("", 13f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            }
        appTrayCard.addView(appTrayDescription, spaced(dp(5)))
        val manageTrayApps = TextView(this).apply {
            text = getString(R.string.app_tray_manage)
            textSize = 14f
            setTextColor(color(R.color.md_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = ripple(R.color.md_surface_container_high, 100)
            isClickable = true
            isFocusable = true
            setOnClickListener { showAppTrayPicker() }
        }
        appTrayCard.addView(manageTrayApps, spaced(dp(8)))

        freeformCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.md_secondary_container, 24)
        }
        freeformCard.addView(
            text(getString(R.string.freeform_support_title), 16f, R.color.md_on_secondary_container).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            },
            wrap(),
        )
        freeformCard.addView(
            text(getString(R.string.freeform_support_summary), 13f, R.color.md_on_secondary_container).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(5)),
        )
        freeformStatus = text("", 13f, R.color.md_on_secondary_container).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        freeformCard.addView(freeformStatus, spaced(dp(9)))
        freeformActionButton = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.md_on_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            background = ripple(R.color.md_primary, 100)
            isClickable = true
            isFocusable = true
            setOnClickListener { handleShizukuAction() }
        }
        freeformCard.addView(freeformActionButton, spaced(dp(9)))

        val sectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        sectionHeader.addView(
            text(getString(R.string.corner_actions_title), 22f, R.color.md_on_surface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            wrap(),
        )
        sectionHeader.addView(
            text(getString(R.string.corner_actions_hint), 14f, R.color.md_on_surface_variant).apply {
                setPadding(0, dp(5), 0, 0)
            },
            wrap(),
        )
        actionGrid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        HotCorner.entries.forEachIndexed { index, corner ->
            val card = CornerActionCard(corner)
            actionCards[corner] = card
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 2, 1, 1f),
                GridLayout.spec(index % 2, 1, 1f),
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                val halfGap = dp(6)
                leftMargin = if (index % 2 == 0) 0 else halfGap
                rightMargin = if (index % 2 == 0) halfGap else 0
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            actionGrid.addView(card, params)
        }
        actionsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionHeader, wrap())
            addView(actionGrid, spaced(dp(8)))
        }

        serviceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = rounded(R.color.md_surface_container, 24)
        }
        val serviceHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        serviceGlyph = StatusGlyphView(this)
        serviceHeader.addView(serviceGlyph, LinearLayout.LayoutParams(dp(20), dp(20)))
        serviceTitle = text("", 17f, R.color.md_on_surface).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }
        serviceHeader.addView(serviceTitle, LinearLayout.LayoutParams(0, -2, 1f))
        serviceCard.addView(serviceHeader, wrap())
        serviceBody = text("", 14f, R.color.md_on_surface_variant).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        serviceCard.addView(serviceBody, spaced(dp(10)))
        val settingsButton = TextView(this).apply {
            text = getString(R.string.manage_accessibility_settings)
            textSize = 14f
            setTextColor(color(R.color.md_on_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            background = ripple(R.color.md_primary, 100)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        serviceCard.addView(settingsButton, spaced(dp(14)))
        usageNote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.md_tertiary_container, 22)
        }
        usageNote.addView(
            text(getString(R.string.usage_note_title), 15f, R.color.md_on_tertiary_container).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            wrap(),
        )
        usageNote.addView(
            text(getString(R.string.usage_note_body), 13f, R.color.md_on_tertiary_container).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(6)),
        )
        configureResponsiveBodies()
        updateResponsiveLayout()
    }

    private fun configureResponsiveBodies() {
        responsiveBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        singleColumnBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wideBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        wideSettingsPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wideActionsPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainContent.addView(responsiveBody, spaced(dp(24)))
        setResponsiveBody(useWideLayout = false)
    }

    private fun updateResponsiveLayout() {
        if (!::scrollContainer.isInitialized || !::mainContent.isInitialized || !::responsiveBody.isInitialized) {
            return
        }
        val windowWidth = scrollContainer.width - safeLeftInsetPx - safeRightInsetPx
        val contentAvailableWidth = scrollContainer.width - scrollContainer.paddingLeft - scrollContainer.paddingRight
        if (windowWidth <= 0 || contentAvailableWidth <= 0) return

        // Use the actual app window width after safe-area insets, so split-screen and
        // freeform resizing switch layouts without relying on device type or orientation.
        val useWideLayout = windowWidth >= dp(EXPANDED_WIDTH_DP)
        val maxContentWidth = dp(if (useWideLayout) WIDE_CONTENT_MAX_DP else MEDIUM_CONTENT_MAX_DP)
        val contentWidth = min(contentAvailableWidth, maxContentWidth)
        val params = mainContent.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != contentWidth) {
            params.width = contentWidth
            mainContent.layoutParams = params
        }
        setResponsiveBody(useWideLayout)
    }

    private fun setResponsiveBody(useWideLayout: Boolean) {
        if (usingWideLayout == useWideLayout) return
        responsiveBody.removeAllViews()
        singleColumnBody.removeAllViews()
        wideSettingsPane.removeAllViews()
        wideActionsPane.removeAllViews()
        wideBody.removeAllViews()

        if (useWideLayout) {
            addSection(wideSettingsPane, overviewCard, topMarginDp = 0)
            addSection(wideSettingsPane, switchCard, topMarginDp = 12)
            addSection(wideSettingsPane, dwellCard, topMarginDp = 12)
            addSection(wideSettingsPane, appTrayCard, topMarginDp = 12)
            addSection(wideSettingsPane, freeformCard, topMarginDp = 12)
            addSection(wideSettingsPane, serviceCard, topMarginDp = 24)
            addSection(wideSettingsPane, usageNote, topMarginDp = 14)
            addSection(wideActionsPane, actionsSection, topMarginDp = 0)

            wideBody.addView(wideSettingsPane, LinearLayout.LayoutParams(0, -2, 2f))
            wideBody.addView(
                wideActionsPane,
                LinearLayout.LayoutParams(0, -2, 3f).apply { marginStart = dp(24) },
            )
            responsiveBody.addView(wideBody, wrap())
        } else {
            addSection(singleColumnBody, overviewCard, topMarginDp = 0)
            addSection(singleColumnBody, switchCard, topMarginDp = 12)
            addSection(singleColumnBody, dwellCard, topMarginDp = 12)
            addSection(singleColumnBody, appTrayCard, topMarginDp = 12)
            addSection(singleColumnBody, freeformCard, topMarginDp = 12)
            addSection(singleColumnBody, actionsSection, topMarginDp = 24)
            addSection(singleColumnBody, serviceCard, topMarginDp = 24)
            addSection(singleColumnBody, usageNote, topMarginDp = 14)
            responsiveBody.addView(singleColumnBody, wrap())
        }
        usingWideLayout = useWideLayout
    }

    private fun addSection(parent: LinearLayout, section: View, topMarginDp: Int) {
        (section.parent as? ViewGroup)?.removeView(section)
        parent.addView(
            section,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topMarginDp) },
        )
    }

    private fun refreshContent() {
        if (!::featureSwitch.isInitialized) return
        applyingSwitchUpdate = true
        featureSwitch.isChecked = HotCornersSettings.isEnabled(this)
        applyingSwitchUpdate = false
        refreshDwellTimeControl()
        refreshAppTraySummary()
        refreshShizukuCard()

        val configured = HotCorner.entries.count { HotCornersSettings.isCornerConfigured(this, it) }
        countText.text = getString(R.string.configured_count, configured)
        overviewGlyph.invalidate()
        actionCards.forEach { (corner, card) ->
            val hoverAction = HotCornersSettings.getHoverAction(this, corner)
            val hoverLabel = formatCornerActionLabel(corner, hoverAction, CornerTrigger.HOVER)
            val buttonCount = HotCornersSettings.getButtonActions(this, corner).size
            card.bind(hoverLabel, buttonCount)
        }

        val serviceEnabled = isHotCornersServiceEnabled()
        serviceTitle.text = getString(
            if (serviceEnabled) R.string.accessibility_status_on else R.string.accessibility_status_off,
        )
        serviceBody.text = getString(
            if (serviceEnabled) R.string.accessibility_body_on else R.string.accessibility_body_off,
        )
        serviceGlyph.isReady = serviceEnabled
    }

    private fun refreshDwellTimeControl() {
        updatingDwellTimeControl = true
        val dwellTimeMs = HotCornersSettings.getDwellTimeMs(this)
        dwellSeekBar.progress = (dwellTimeMs - HotCornersSettings.MIN_DWELL_TIME_MS) /
            HotCornersSettings.DWELL_TIME_STEP_MS
        updateDwellValue(dwellTimeMs)
        updatingDwellTimeControl = false
    }

    private fun refreshAppTraySummary() {
        val selectedCount = HotCornersSettings.getAppTrayPackages(this).size
        appTrayCount.text = getString(R.string.app_tray_count, selectedCount, HotCornersSettings.MAX_APP_TRAY_APPS)
        appTrayDescription.text = if (selectedCount == 0) {
            getString(R.string.app_tray_summary_empty)
        } else {
            getString(R.string.app_tray_summary_selected, HotCornersSettings.MAX_APP_TRAY_APPS)
        }
    }

    private fun refreshShizukuCard() {
        val state = ShizukuFreeformLauncher.state(this)
        val statusRes = when (state) {
            ShizukuState.NOT_INSTALLED -> R.string.freeform_shizuku_missing
            ShizukuState.NOT_RUNNING -> R.string.freeform_shizuku_not_running
            ShizukuState.PERMISSION_REQUIRED -> R.string.freeform_shizuku_permission_required
            ShizukuState.READY -> R.string.freeform_shizuku_ready
        }
        val buttonRes = when (state) {
            ShizukuState.NOT_INSTALLED -> R.string.freeform_shizuku_install
            ShizukuState.NOT_RUNNING -> R.string.freeform_shizuku_open
            ShizukuState.PERMISSION_REQUIRED -> R.string.freeform_shizuku_authorize
            ShizukuState.READY -> R.string.freeform_shizuku_manage
        }
        freeformStatus.text = getString(statusRes)
        freeformActionButton.text = getString(buttonRes)
        freeformActionButton.contentDescription = getString(buttonRes)
    }

    private fun handleShizukuAction() {
        when (ShizukuFreeformLauncher.state(this)) {
            ShizukuState.NOT_INSTALLED -> openShizukuDownloadPage()
            ShizukuState.NOT_RUNNING -> openShizukuApp()
            ShizukuState.PERMISSION_REQUIRED -> requestShizukuPermission()
            ShizukuState.READY -> openShizukuApp()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.freeform_permission_dialog_title)
                    .setMessage(R.string.freeform_permission_dialog_summary)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.freeform_shizuku_authorize) { _, _ ->
                        requestShizukuPermissionFromService()
                    }
                    .show()
            } else {
                requestShizukuPermissionFromService()
            }
        } catch (_: RuntimeException) {
            refreshShizukuCard()
        }
    }

    private fun requestShizukuPermissionFromService() {
        try {
            Shizuku.requestPermission(FREEFORM_PERMISSION_REQUEST_CODE)
        } catch (_: RuntimeException) {
            refreshShizukuCard()
        }
    }

    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(ShizukuFreeformLauncher.SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ShizukuFreeformLauncher.SHIZUKU_PACKAGE}"),
                ),
            )
        }
    }

    private fun openShizukuDownloadPage() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    }

    private fun updateDwellValue(dwellTimeMs: Int) {
        val label = formatDwellTime(dwellTimeMs)
        dwellValueText.text = label
        dwellSeekBar.contentDescription = getString(R.string.dwell_time_accessibility_label, label)
    }

    private fun formatDwellTime(dwellTimeMs: Int): String = if (dwellTimeMs == 0) {
        getString(R.string.dwell_time_immediate)
    } else {
        getString(R.string.dwell_time_value, dwellTimeMs)
    }

    private fun formatCornerActionLabel(
        corner: HotCorner,
        action: CornerAction,
        trigger: CornerTrigger,
    ): String {
        if (!action.launchesApp) {
            return formatActionLabel(action, isAvailableInPicker(action))
        }

        val packageName = HotCornersSettings.getAppPackage(this, corner, trigger)
            ?: return getString(R.string.app_not_selected)
        val appLabel = applicationLabel(packageName) ?: return getString(R.string.app_not_available)
        return getString(
            if (action.requestsSmallWindow) R.string.action_small_window_for_app else R.string.action_open_app_named,
            appLabel,
        )
    }

    private fun applicationLabel(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    private inner class CornerActionCard(private val corner: HotCorner) : LinearLayout(this@MainActivity) {
        private val glyph = CornerGlyphView(this@MainActivity, corner)
        private val cornerTitle = text(getString(corner.labelResId), 15f, R.color.md_on_surface).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        private val hoverTitle = text("", 14f, R.color.md_primary).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(4), 0, 0)
        }
        private val buttonTitle = text("", 12f, R.color.md_on_surface_variant).apply {
            setPadding(0, dp(4), 0, 0)
        }
        private val hint = text(getString(R.string.adjust_action), 12f, R.color.md_on_surface_variant).apply {
            setPadding(0, dp(3), 0, 0)
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(14), dp(12))
            background = ripple(R.color.md_surface_container_high, 22)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(162)
            addView(glyph, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(cornerTitle, spaced(dp(9)))
            addView(hoverTitle, wrap())
            addView(buttonTitle, wrap())
            addView(hint, wrap())
            setOnClickListener { showCornerEditor(corner) }
        }

        fun bind(label: String, buttonCount: Int) {
            hoverTitle.text = getString(R.string.corner_hover_summary, label)
            buttonTitle.text = getString(R.string.corner_button_count, buttonCount)
            glyph.configured = HotCornersSettings.isCornerConfigured(this@MainActivity, corner)
            contentDescription = getString(
                R.string.corner_action_button_description,
                getString(corner.labelResId),
                label,
                buttonCount,
            )
        }
    }

    private inner class LaunchableAppAdapter(
        private val allApps: List<LaunchableApp>,
        private val selectedPackageName: String?,
    ) : BaseAdapter() {
        private var visibleApps = allApps

        override fun getCount(): Int = visibleApps.size

        override fun getItem(position: Int): LaunchableApp = visibleApps[position]

        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun hasStableIds(): Boolean = true

        fun filter(query: String) {
            val normalizedQuery = query.trim().lowercase(Locale.getDefault())
            visibleApps = if (normalizedQuery.isEmpty()) {
                allApps
            } else {
                allApps.filter { app ->
                    app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                        app.packageName.lowercase(Locale.getDefault()).contains(normalizedQuery)
                }
            }
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = getItem(position)
            val holder = (convertView?.tag as? AppRowViews) ?: createAppRow()
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.radio.isChecked = app.packageName == selectedPackageName
            val selectedDescription = if (holder.radio.isChecked) {
                getString(R.string.app_already_selected)
            } else {
                ""
            }
            holder.root.contentDescription = listOf(app.label, selectedDescription)
                .filter(String::isNotBlank)
                .joinToString("，")
            return holder.root
        }

        private fun createAppRow(): AppRowViews {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), dp(8), dp(6), dp(8))
            }
            val icon = ImageView(this@MainActivity).apply {
                contentDescription = null
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val label = text("", 15f, R.color.md_on_surface).apply {
                setPadding(dp(12), 0, dp(8), 0)
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val radio = RadioButton(this@MainActivity).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList.valueOf(color(R.color.md_primary))
                contentDescription = null
            }
            row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(48)))
            return AppRowViews(row, icon, label, radio).also { row.tag = it }
        }
    }

    private class AppRowViews(
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val radio: RadioButton,
    )

    private inner class AppTrayAdapter(
        private val allApps: List<LaunchableApp>,
        private var selectedPackages: List<String>,
    ) : BaseAdapter() {
        private var visibleApps = allApps

        override fun getCount(): Int = visibleApps.size

        override fun getItem(position: Int): LaunchableApp = visibleApps[position]

        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun hasStableIds(): Boolean = true

        fun filter(query: String) {
            val normalizedQuery = query.trim().lowercase(Locale.getDefault())
            visibleApps = if (normalizedQuery.isEmpty()) {
                allApps
            } else {
                allApps.filter { app ->
                    app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                        app.packageName.lowercase(Locale.getDefault()).contains(normalizedQuery)
                }
            }
            notifyDataSetChanged()
        }

        fun updateSelection(packageNames: List<String>) {
            selectedPackages = packageNames.toList()
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = getItem(position)
            val holder = (convertView?.tag as? AppTrayRowViews) ?: createTrayAppRow()
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            val selectedIndex = selectedPackages.indexOf(app.packageName)
            holder.order.text = if (selectedIndex >= 0) {
                (selectedIndex + 1).toString().padStart(2, '0')
            } else {
                ""
            }
            holder.checkBox.isChecked = selectedIndex >= 0
            holder.root.contentDescription = if (selectedIndex >= 0) {
                getString(R.string.tray_app_selected_accessibility_description, app.label, selectedIndex + 1)
            } else {
                getString(R.string.tray_app_unselected_accessibility_description, app.label)
            }
            return holder.root
        }

        private fun createTrayAppRow(): AppTrayRowViews {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(12), dp(6), dp(4), dp(6))
            }
            val icon = ImageView(this@MainActivity).apply {
                contentDescription = null
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val label = text("", 15f, R.color.md_on_surface).apply {
                setPadding(dp(12), 0, dp(8), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val order = text("", 12f, R.color.md_primary).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(order, LinearLayout.LayoutParams(dp(30), dp(40)))
            val checkBox = CheckBox(this@MainActivity).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList.valueOf(color(R.color.md_primary))
                contentDescription = null
            }
            row.addView(checkBox, LinearLayout.LayoutParams(dp(48), dp(48)))
            return AppTrayRowViews(row, icon, label, order, checkBox).also { row.tag = it }
        }
    }

    private class AppTrayRowViews(
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val order: TextView,
        val checkBox: CheckBox,
    )

    private fun showCornerEditor(corner: HotCorner) {
        val dialog = Dialog(this)
        val content = modalContent()
        content.addView(
            text(getString(R.string.configure_corner_title, getString(corner.labelResId)), 23f, R.color.md_on_surface)
                .apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            wrap(),
        )
        content.addView(
            text(getString(R.string.corner_editor_summary), 14f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(6)),
        )

        val editorBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editorBody.addView(sectionLabel(getString(R.string.hover_action_title)), spaced(dp(14)))
        val hoverAction = HotCornersSettings.getHoverAction(this, corner)
        val hoverChoice = TextView(this).apply {
            text = getString(
                R.string.hover_action_choice,
                formatCornerActionLabel(corner, hoverAction, CornerTrigger.HOVER),
            )
            textSize = 15f
            setTextColor(color(R.color.md_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ripple(R.color.md_surface_container, 16)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.hover_action_description)
            setOnClickListener {
                showActionPicker(corner, null) {
                    dialog.dismiss()
                    showCornerEditor(corner)
                }
            }
        }
        editorBody.addView(hoverChoice, spaced(dp(6)))
        editorBody.addView(
            text(getString(R.string.trigger_summary_hover, formatDwellTime(HotCornersSettings.getDwellTimeMs(this))),
                13f, R.color.md_on_surface_variant).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            spaced(dp(4)),
        )

        val buttonActions = HotCornersSettings.getButtonActions(this, corner)
        val buttonHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttonHeader.addView(
            sectionLabel(getString(R.string.button_actions_title)),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        buttonHeader.addView(
            text(getString(R.string.button_action_count, buttonActions.size, MAX_BUTTON_ACTIONS),
                13f, R.color.md_on_surface_variant),
            LinearLayout.LayoutParams(-2, -2),
        )
        editorBody.addView(buttonHeader, spaced(dp(18)))

        if (buttonActions.isEmpty()) {
            editorBody.addView(
                text(getString(R.string.button_actions_empty), 14f, R.color.md_on_surface_variant).apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = rounded(R.color.md_surface_container, 16)
                },
                spaced(dp(6)),
            )
        } else {
            buttonActions.forEach { (trigger, action) ->
                val mappingRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(6), dp(6), dp(6))
                    background = rounded(R.color.md_surface_container, 16)
                }
                val mapping = text(
                    getString(
                        R.string.button_mapping_format,
                        getString(trigger.labelResId),
                        formatCornerActionLabel(corner, action, trigger),
                    ),
                    14f,
                    R.color.md_on_surface,
                ).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                mappingRow.addView(mapping, LinearLayout.LayoutParams(0, -2, 1f))
                mappingRow.addView(
                    compactActionButton(R.string.edit_mapping) {
                        showActionPicker(corner, trigger) {
                            dialog.dismiss()
                            showCornerEditor(corner)
                        }
                    },
                )
                mappingRow.addView(
                    compactActionButton(R.string.remove_mapping) {
                        HotCornersSettings.removeButtonBinding(this, corner, trigger)
                        dialog.dismiss()
                        showCornerEditor(corner)
                    },
                )
                editorBody.addView(mappingRow, spaced(dp(6)))
            }
        }

        val addButton = TextView(this).apply {
            text = getString(
                if (buttonActions.size >= MAX_BUTTON_ACTIONS) R.string.button_actions_limit
                else R.string.add_button_action,
            )
            textSize = 14f
            setTextColor(color(R.color.md_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = ripple(R.color.md_surface_container_high, 100)
            isClickable = buttonActions.size < MAX_BUTTON_ACTIONS
            isFocusable = isClickable
            alpha = if (isClickable) 1f else 0.6f
            contentDescription = text.toString()
            if (isClickable) {
                setOnClickListener {
                    showButtonTriggerPicker(corner) { selectedTrigger ->
                        showActionPicker(corner, selectedTrigger) {
                            dialog.dismiss()
                            showCornerEditor(corner)
                        }
                    }
                }
            }
        }
        editorBody.addView(addButton, spaced(dp(10)))

        val editorScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(editorBody)
        }
        content.addView(editorScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        content.addView(dialogActionButton(R.string.close) { dialog.dismiss() }, footerActionParams())
        showModal(dialog, content, 640)
    }

    private fun showButtonTriggerPicker(corner: HotCorner, onSelected: (CornerTrigger) -> Unit) {
        val configured = HotCornersSettings.getButtonActions(this, corner).keys
        val available = CornerTrigger.mouseButtons.filterNot { it in configured }
        if (available.isEmpty()) return

        val dialog = Dialog(this)
        val content = modalContent()
        content.addView(
            text(getString(R.string.choose_button_title), 23f, R.color.md_on_surface)
                .apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            wrap(),
        )
        content.addView(
            text(getString(R.string.choose_button_summary), 14f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(6)),
        )
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        available.forEach { trigger ->
            val row = pickerChoice(getString(trigger.labelResId), false) {
                dialog.dismiss()
                onSelected(trigger)
            }
            choices.addView(row, wrap())
        }
        val choiceScroll = ScrollView(this).apply { addView(choices) }
        content.addView(choiceScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        content.addView(dialogActionButton(R.string.cancel) { dialog.dismiss() }, footerActionParams())
        showModal(dialog, content, 500)
    }

    private fun showActionPicker(corner: HotCorner, trigger: CornerTrigger?, onSaved: () -> Unit) {
        val bindingTrigger = trigger ?: CornerTrigger.HOVER
        val selected = if (bindingTrigger == CornerTrigger.HOVER) {
            HotCornersSettings.getHoverAction(this, corner)
        } else {
            HotCornersSettings.getButtonAction(this, corner, bindingTrigger)
        }
        val actions = selectableActions(bindingTrigger, selected)
        val dialog = Dialog(this)
        val content = modalContent()
        val title = if (bindingTrigger == CornerTrigger.HOVER) {
            getString(R.string.hover_action_picker_title, getString(corner.labelResId))
        } else {
            getString(
                R.string.button_action_picker_title,
                getString(corner.labelResId),
                getString(bindingTrigger.labelResId),
            )
        }
        content.addView(
            text(title, 23f, R.color.md_on_surface).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
            wrap(),
        )
        val summaryText = if (bindingTrigger == CornerTrigger.HOVER) {
            getString(R.string.trigger_summary_hover, formatDwellTime(HotCornersSettings.getDwellTimeMs(this)))
        } else {
            getString(bindingTrigger.summaryResId)
        }
        content.addView(
            text(summaryText, 14f, R.color.md_on_surface_variant).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            spaced(dp(6)),
        )
        content.addView(sectionLabel(getString(R.string.action_picker_section_title)), spaced(dp(12)))

        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { action ->
            val label = formatActionLabel(action, isAvailableInPicker(action))
            choices.addView(pickerChoice(label, action == selected) {
                dialog.dismiss()
                if (action.launchesApp) {
                    showAppPicker(corner, action, bindingTrigger, onSaved)
                } else {
                    HotCornersSettings.saveBinding(this, corner, trigger, action)
                    onSaved()
                }
            }, wrap())
        }
        val choiceScroll = ScrollView(this).apply { addView(choices) }
        content.addView(choiceScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        content.addView(dialogActionButton(R.string.cancel) { dialog.dismiss() }, footerActionParams())
        showModal(dialog, content, 640)
    }

    private fun showAppPicker(
        corner: HotCorner,
        action: CornerAction,
        trigger: CornerTrigger,
        onSaved: () -> Unit,
    ) {
        val apps = LaunchableAppRepository.load(this)
        val dialog = Dialog(this)
        val content = modalContent()
        content.addView(
            text(getString(R.string.choose_app_title), 23f, R.color.md_on_surface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            wrap(),
        )
        val summaryRes = if (action.requestsSmallWindow) {
            R.string.choose_app_small_window_summary
        } else {
            R.string.choose_app_summary
        }
        content.addView(
            text(getString(summaryRes), 14f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(7)),
        )

        val searchField = EditText(this).apply {
            hint = getString(R.string.search_apps_hint)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            minHeight = dp(52)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(color(R.color.md_on_surface))
            setHintTextColor(color(R.color.md_on_surface_variant))
            background = rounded(R.color.md_surface, 16)
            contentDescription = getString(R.string.search_apps_hint)
        }
        content.addView(searchField, spaced(dp(12)))

        val adapter = LaunchableAppAdapter(apps, HotCornersSettings.getAppPackage(this, corner, trigger))
        val appList = ListView(this).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(color(R.color.md_outline_variant))
            dividerHeight = dp(1)
            isNestedScrollingEnabled = true
            emptyView = text(getString(R.string.app_list_empty), 14f, R.color.md_on_surface_variant).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            setOnItemClickListener { _, _, position, _ ->
                val app = adapter.getItem(position) as LaunchableApp
                HotCornersSettings.saveBinding(this@MainActivity, corner, trigger, action, app.packageName)
                dialog.dismiss()
                onSaved()
            }
        }
        val emptyState = appList.emptyView as View
        val listFrame = FrameLayout(this).apply {
            addView(appList, FrameLayout.LayoutParams(-1, -1))
            addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        }
        val availableHeight = (scrollContainer.height - safeTopInsetPx - safeBottomInsetPx)
            .coerceAtLeast(dp(280))
        val listHeight = min(dp(360), (availableHeight - dp(292)).coerceAtLeast(dp(100)))
        content.addView(listFrame, LinearLayout.LayoutParams(-1, listHeight).apply { topMargin = dp(8) })
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })

        content.addView(dialogActionButton(R.string.cancel) { dialog.dismiss() }, footerActionParams())
        showModal(dialog, content, 640)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    private fun showAppTrayPicker() {
        val apps = LaunchableAppRepository.load(this)
        val availablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
        val selectedPackages = HotCornersSettings.getAppTrayPackages(this)
            .filter(availablePackages::contains)
            .toMutableList()
        val dialog = Dialog(this)
        val content = modalContent()
        content.addView(
            text(getString(R.string.app_tray_picker_title), 23f, R.color.md_on_surface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            wrap(),
        )
        content.addView(
            text(getString(R.string.app_tray_picker_summary, HotCornersSettings.MAX_APP_TRAY_APPS),
                14f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(6)),
        )

        val selectedCount = text("", 13f, R.color.md_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun refreshSelectionSummary() {
            selectedCount.text = getString(
                R.string.app_tray_count,
                selectedPackages.size,
                HotCornersSettings.MAX_APP_TRAY_APPS,
            )
        }
        refreshSelectionSummary()
        content.addView(selectedCount, spaced(dp(8)))

        val searchField = EditText(this).apply {
            hint = getString(R.string.search_apps_hint)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            minHeight = dp(52)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(color(R.color.md_on_surface))
            setHintTextColor(color(R.color.md_on_surface_variant))
            background = rounded(R.color.md_surface, 16)
            contentDescription = getString(R.string.search_apps_hint)
        }
        content.addView(searchField, spaced(dp(10)))

        val adapter = AppTrayAdapter(apps, selectedPackages)
        val appList = ListView(this).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(color(R.color.md_outline_variant))
            dividerHeight = dp(1)
            isNestedScrollingEnabled = true
            emptyView = text(getString(R.string.app_list_empty), 14f, R.color.md_on_surface_variant).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            setOnItemClickListener { _, _, position, _ ->
                val app = adapter.getItem(position)
                if (app.packageName in selectedPackages) {
                    selectedPackages.remove(app.packageName)
                } else if (selectedPackages.size < HotCornersSettings.MAX_APP_TRAY_APPS) {
                    selectedPackages.add(app.packageName)
                } else {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.app_tray_limit, HotCornersSettings.MAX_APP_TRAY_APPS),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnItemClickListener
                }
                adapter.updateSelection(selectedPackages)
                refreshSelectionSummary()
            }
        }
        val emptyState = appList.emptyView as View
        val listFrame = FrameLayout(this).apply {
            addView(appList, FrameLayout.LayoutParams(-1, -1))
            addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        }
        val availableHeight = (scrollContainer.height - safeTopInsetPx - safeBottomInsetPx)
            .coerceAtLeast(dp(300))
        val listHeight = min(dp(360), (availableHeight - dp(270)).coerceAtLeast(dp(100)))
        content.addView(listFrame, LinearLayout.LayoutParams(-1, listHeight).apply { topMargin = dp(8) })
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })

        content.addView(dialogActionButton(R.string.close) {
            HotCornersSettings.saveAppTrayPackages(this, selectedPackages)
            dialog.dismiss()
            refreshAppTraySummary()
        }, footerActionParams())
        showModal(dialog, content, 640)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    private fun actionPickerWidthPx(): Int {
        val availableWidth = scrollContainer.width - scrollContainer.paddingLeft - scrollContainer.paddingRight
        return min((availableWidth - dp(32)).coerceAtLeast(dp(240)), dp(480))
    }

    private fun modalContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(16))
        background = rounded(R.color.md_surface_container_high, 28)
    }

    private fun sectionLabel(label: String) = text(label, 13f, R.color.md_on_surface_variant).apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dialogActionButton(labelRes: Int, onClick: () -> Unit) = TextView(this).apply {
        text = getString(labelRes)
        textSize = 14f
        setTextColor(color(R.color.md_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(72)
        setPadding(dp(16), 0, dp(16), 0)
        background = ripple(R.color.md_surface_variant, 100)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun footerActionParams() = LinearLayout.LayoutParams(-2, dp(48)).apply {
        gravity = Gravity.END
        topMargin = dp(8)
    }

    private fun compactActionButton(labelRes: Int, onClick: () -> Unit) = TextView(this).apply {
        text = getString(labelRes)
        textSize = 12f
        setTextColor(color(R.color.md_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(44)
        setPadding(dp(5), 0, dp(5), 0)
        background = ripple(R.color.md_surface_container_high, 12)
        isClickable = true
        isFocusable = true
        contentDescription = getString(labelRes)
        setOnClickListener { onClick() }
    }

    private fun pickerChoice(label: String, selected: Boolean, onClick: () -> Unit) = RadioButton(this).apply {
        text = label
        textSize = 15f
        setTextColor(color(R.color.md_on_surface))
        minHeight = dp(52)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        buttonTintList = ColorStateList.valueOf(
            color(if (selected) R.color.md_primary else R.color.md_outline),
        )
        isChecked = selected
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun showModal(dialog: Dialog, content: View, maxHeightDp: Int) {
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val availableHeight = (scrollContainer.height - safeTopInsetPx - safeBottomInsetPx - dp(32))
                .coerceAtLeast(dp(240))
            window.setLayout(actionPickerWidthPx(), min(dp(maxHeightDp), availableHeight))
        }
    }

    private fun selectableActions(trigger: CornerTrigger, selected: CornerAction?): List<CornerAction> {
        val choices = CornerAction.entries.filter { action ->
            (trigger == CornerTrigger.HOVER && action == CornerAction.NONE) ||
                (action != CornerAction.NONE && action.isSupportedByOs() && isAvailableInPicker(action))
        }.toMutableList()
        if (selected != null && selected !in choices) choices.add(selected)
        return choices
    }

    private fun isAvailableInPicker(action: CornerAction): Boolean {
        if (action == CornerAction.NONE) return true
        if (action.opensAppTray) return true
        if (action.launchesApp) return true
        if (!action.isSupportedByOs()) return false
        val reportedActions = HotCornersSettings.getAvailableActionIds(this)
        val serviceEnabled = isHotCornersServiceEnabled()
        val actionId = action.globalActionId() ?: return false
        if (action == CornerAction.SPLIT_SCREEN) return reportedActions?.contains(actionId) == true
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

    private fun text(value: CharSequence, sizeSp: Float, colorRes: Int) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color(colorRes))
        includeFontPadding = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun rounded(colorRes: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color(colorRes))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun ripple(colorRes: Int, radiusDp: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(color(R.color.md_outline_variant).let { (it and 0x00FFFFFF) or 0x24000000 }),
        rounded(colorRes, radiusDp),
        null,
    )

    private fun color(colorRes: Int): Int = getColor(colorRes)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun wrap() = LinearLayout.LayoutParams(-1, -2)
    private fun spaced(top: Int) = wrap().apply { topMargin = top }

    private companion object {
        const val MAX_BUTTON_ACTIONS = 5
        const val FREEFORM_PERMISSION_REQUEST_CODE = 7204
        const val EXPANDED_WIDTH_DP = 840
        const val MEDIUM_CONTENT_MAX_DP = 760
        const val WIDE_CONTENT_MAX_DP = 1200
    }
}

/** Tiny four-corner diagram used both on the overview and each configuration card. */
private class CornerGlyphView(
    context: android.content.Context,
    private val corner: HotCorner?,
) : View(context) {
    var configured: Boolean = false
        set(value) { field = value; invalidate() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val scale = size / 100f
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(scale, scale)
        stroke.strokeWidth = if (corner == null) 7f else 8f
        stroke.color = color(R.color.md_on_primary_container)
        fill.color = color(R.color.md_tertiary)
        val corners = listOf(
            Triple(HotCorner.TOP_LEFT, 22f to 40f, 40f to 22f),
            Triple(HotCorner.TOP_RIGHT, 60f to 22f, 78f to 40f),
            Triple(HotCorner.BOTTOM_LEFT, 22f to 60f, 40f to 78f),
            Triple(HotCorner.BOTTOM_RIGHT, 60f to 78f, 78f to 60f),
        )
        corners.forEach { (item, first, second) ->
            val active = if (corner == null) {
                HotCornersSettings.isCornerConfigured(context, item)
            } else {
                item == corner && configured
            }
            stroke.color = color(if (active) R.color.md_primary else R.color.md_on_primary_container)
            path.reset()
            path.moveTo(first.first, first.second)
            val elbowX = when (item) {
                HotCorner.TOP_LEFT, HotCorner.BOTTOM_LEFT -> 22f
                else -> 78f
            }
            val elbowY = when (item) {
                HotCorner.TOP_LEFT, HotCorner.TOP_RIGHT -> 22f
                else -> 78f
            }
            path.lineTo(elbowX, elbowY)
            path.lineTo(second.first, second.second)
            canvas.drawPath(path, stroke)
            if (active) {
                val dotX = if (item == HotCorner.TOP_LEFT || item == HotCorner.BOTTOM_LEFT) 16f else 84f
                val dotY = if (item == HotCorner.TOP_LEFT || item == HotCorner.TOP_RIGHT) 16f else 84f
                canvas.drawCircle(dotX, dotY, 5f, fill)
            }
        }
        canvas.restore()
    }

    private fun color(colorRes: Int): Int = context.getColor(colorRes)
}

private class StatusGlyphView(context: android.content.Context) : View(context) {
    var isReady: Boolean = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) * 0.46f
        paint.color = context.getColor(if (isReady) R.color.md_primary else R.color.md_tertiary)
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.color = context.getColor(if (isReady) R.color.md_on_primary else R.color.md_on_tertiary)
        canvas.drawCircle(width / 2f, height / 2f, radius * 0.28f, paint)
    }
}
