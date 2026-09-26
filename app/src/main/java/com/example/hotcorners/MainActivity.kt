package com.example.hotcorners

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min

/** Native-view settings screen styled with Material 3 Expressive color and shape tokens. */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var featureSwitch: Switch
    private lateinit var countText: TextView
    private lateinit var serviceTitle: TextView
    private lateinit var serviceBody: TextView
    private lateinit var serviceGlyph: StatusGlyphView
    private lateinit var overviewGlyph: CornerGlyphView
    private lateinit var actionGrid: GridLayout
    private val actionCards = mutableMapOf<HotCorner, CornerActionCard>()
    private var applyingSwitchUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        refreshContent()
    }

    private fun buildSettingsScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(color(R.color.md_background))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(28))
        }
        val centeredContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val availableWidth = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(280))
        centeredContent.layoutParams = FrameLayout.LayoutParams(
            min(availableWidth, dp(680)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )

        val centeredFrame = FrameLayout(this).apply {
            addView(centeredContent)
        }
        page.addView(centeredFrame, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(page)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
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
            view.setPadding(left, 0, right, 0)
            page.setPadding(0, dp(20) + safeInsets[1], 0, dp(28) + safeInsets[3])
            val contentWidth = (view.width - left - right).coerceAtLeast(dp(280))
            centeredContent.layoutParams = (centeredContent.layoutParams as FrameLayout.LayoutParams).apply {
                width = min(contentWidth, dp(680))
            }
            insets
        }
        setContentView(scroll)

        val eyebrow = text(getString(R.string.settings_eyebrow), 12f, R.color.md_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(R.color.md_primary_container, 100)
        }
        centeredContent.addView(eyebrow, wrap())

        centeredContent.addView(
            text(getString(R.string.settings_headline), 32f, R.color.md_on_surface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = -0.025f
                setLineSpacing(dp(1).toFloat(), 1.0f)
            },
            spaced(dp(16)),
        )
        centeredContent.addView(
            text(getString(R.string.settings_summary), 15f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            spaced(dp(8)),
        )

        // Expressive, supportive container combines a compact visual with live setup progress.
        val overview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(R.color.md_primary_container, 28)
        }
        overviewGlyph = CornerGlyphView(this, null)
        overview.addView(overviewGlyph, LinearLayout.LayoutParams(dp(64), dp(64)))
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
        overview.addView(overviewCopy, LinearLayout.LayoutParams(0, -2, 1f))
        centeredContent.addView(overview, spaced(dp(24)))

        // Main enable row has a full-height click target and a native accessible switch.
        val switchCard = LinearLayout(this).apply {
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
        centeredContent.addView(switchCard, spaced(dp(12)))

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
        centeredContent.addView(sectionHeader, spaced(dp(24)))

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
        centeredContent.addView(actionGrid, spaced(dp(8)))

        val serviceCard = LinearLayout(this).apply {
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
        centeredContent.addView(serviceCard, spaced(dp(24)))

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.md_tertiary_container, 22)
        }
        note.addView(
            text(getString(R.string.usage_note_title), 15f, R.color.md_on_tertiary_container).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            wrap(),
        )
        note.addView(
            text(getString(R.string.usage_note_body), 13f, R.color.md_on_tertiary_container).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(6)),
        )
        centeredContent.addView(note, spaced(dp(14)))
    }

    private fun refreshContent() {
        if (!::featureSwitch.isInitialized) return
        applyingSwitchUpdate = true
        featureSwitch.isChecked = HotCornersSettings.isEnabled(this)
        applyingSwitchUpdate = false

        val configured = HotCorner.entries.count { HotCornersSettings.getAction(this, it) != CornerAction.NONE }
        countText.text = getString(R.string.configured_count, configured)
        overviewGlyph.invalidate()
        actionCards.forEach { (corner, card) ->
            val action = HotCornersSettings.getAction(this, corner)
            card.bind(action, formatActionLabel(action, isAvailableInPicker(action)))
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

    private inner class CornerActionCard(private val corner: HotCorner) : LinearLayout(this@MainActivity) {
        private val glyph = CornerGlyphView(this@MainActivity, corner)
        private val cornerTitle = text(getString(corner.labelResId), 15f, R.color.md_on_surface).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        private val actionTitle = text("", 14f, R.color.md_primary).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
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
            minimumHeight = dp(146)
            addView(glyph, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(cornerTitle, spaced(dp(9)))
            addView(actionTitle, wrap())
            addView(hint, wrap())
            setOnClickListener { showActionPicker(corner) }
        }

        fun bind(action: CornerAction, label: String) {
            actionTitle.text = label
            glyph.configured = action != CornerAction.NONE
            contentDescription = getString(
                R.string.corner_action_button_description,
                getString(corner.labelResId),
                label,
            )
        }
    }

    private fun showActionPicker(corner: HotCorner) {
        val actions = selectableActions(corner)
        val selected = HotCornersSettings.getAction(this, corner)
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(16))
            background = rounded(R.color.md_surface_container_high, 28)
        }
        content.addView(
            text(getString(R.string.choose_action_title, getString(corner.labelResId)), 23f, R.color.md_on_surface).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            wrap(),
        )
        content.addView(
            text(getString(R.string.choose_action_summary), 14f, R.color.md_on_surface_variant).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            spaced(dp(7)),
        )
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { action ->
            val available = isAvailableInPicker(action)
            val label = formatActionLabel(action, available)
            val radio = RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(color(R.color.md_on_surface))
                minHeight = dp(52)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                buttonTintList = ColorStateList.valueOf(color(if (action == selected) R.color.md_primary else R.color.md_outline))
                isChecked = action == selected
                contentDescription = label
                setOnClickListener {
                    preferences.edit().putString(HotCornersSettings.actionKey(corner), action.id).apply()
                    dialog.dismiss()
                }
            }
            choices.addView(radio, LinearLayout.LayoutParams(-1, -2))
        }
        val choiceScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(choices)
        }
        val choiceHeight = min(dp(380), resources.displayMetrics.heightPixels - dp(260)).coerceAtLeast(dp(180))
        content.addView(choiceScroll, LinearLayout.LayoutParams(-1, choiceHeight).apply { topMargin = dp(10) })

        val cancel = TextView(this).apply {
            text = getString(android.R.string.cancel)
            textSize = 14f
            setTextColor(color(R.color.md_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(R.color.md_surface_variant, 100)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(cancel, LinearLayout.LayoutParams(-2, dp(48)).apply {
            gravity = Gravity.END
            topMargin = dp(6)
        })

        dialog.setContentView(content)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = min(resources.displayMetrics.widthPixels - dp(32), dp(480))
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setLayout(min(resources.displayMetrics.widthPixels - dp(32), dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                HotCornersSettings.getAction(context, item) != CornerAction.NONE
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
