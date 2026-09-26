package com.example.hotcorners

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.PopupMenu
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Full-screen input layer with app icons arranged along an arc from the selected corner. */
internal class CornerAppTrayOverlay(
    context: Context,
    private val corner: HotCorner,
    private val apps: List<LaunchableApp>,
    private val iconShape: HotCornersSettings.TrayIconShape,
    private val onOpenApp: (LaunchableApp) -> Unit,
    private val onOpenSmallWindow: (LaunchableApp, Int, Int) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val touchTargetSize = dp(TILE_TOUCH_SIZE_DP)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val easing = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val appTiles = mutableListOf<AppTile>()
    private val dragClipPath = Path()
    private val dragIconBounds = RectF()
    private var activeTile: AppTile? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var pointerRawX = 0f
    private var pointerRawY = 0f
    private var holdReady = false
    private var dragging = false
    private var dismissing = false
    private var draggedIcon: Drawable? = null
    private var fanRadius = 0f
    private val holdRunnable = Runnable {
        val tile = activeTile ?: return@Runnable
        if (dismissing || !tile.isPressed) return@Runnable
        holdReady = true
        animateTileHoldReady(tile)
        updateDragState(pointerRawX, pointerRawY)
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.app_tray_accessibility_description)

        apps.forEach { app ->
            val tile = AppTile(app)
            appTiles += tile
            addView(tile, LayoutParams(touchTargetSize, touchTargetSize))
            tile.alpha = 0f
            tile.scaleX = 0.9f
            tile.scaleY = 0.9f
        }
    }

    fun requestInitialFocus() {
        (appTiles.firstOrNull() ?: this).requestFocus()
    }

    fun animateIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            appTiles.forEach { tile ->
                tile.alpha = 1f
                tile.scaleX = 1f
                tile.scaleY = 1f
            }
            return
        }

        setCornerPivot()
        alpha = 0f
        scaleX = 0.96f
        scaleY = 0.96f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190L)
            .setInterpolator(easing)
            .start()
        appTiles.forEachIndexed { index, tile ->
            tile.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(index * TILE_STAGGER_MS)
                .setDuration(TILE_ENTRY_DURATION_MS)
                .setInterpolator(easing)
                .start()
        }
    }

    fun animateOut(onFinished: () -> Unit) {
        if (dismissing) return
        dismissing = true
        removeCallbacks(holdRunnable)
        activeTile?.let {
            it.isPressed = false
            it.alpha = 1f
            animateTileScale(it, TILE_REST_SCALE, PRESS_RELEASE_DURATION_MS)
        }
        activeTile = null
        draggedIcon = null
        holdReady = false
        dragging = false

        if (!ValueAnimator.areAnimatorsEnabled()) {
            onFinished()
            return
        }

        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(TRAY_EXIT_DURATION_MS)
            .setInterpolator(easing)
            .withEndAction(onFinished)
            .start()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fanRadius = minOf(dp(MAX_FAN_RADIUS_DP).toFloat(), width - dp(32f), height - dp(32f))
            .coerceAtLeast(dp(128f))
        positionTiles(width, height)
        setCornerPivot()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (dragging) drawDraggedIcon(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestDismiss()
        // The full-screen overlay intentionally consumes the dismissing click.
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        ) {
            requestDismiss()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            requestDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onTileTouch(tile: AppTile, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTileGesture(tile, event.rawX, event.rawY)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTile !== tile) return true
                pointerRawX = event.rawX
                pointerRawY = event.rawY
                updateDragState(pointerRawX, pointerRawY)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeTile !== tile) return true
                endTileGesture(tile, event.rawX, event.rawY)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                tile.isPressed = false
                animateTilePress(tile, pressed = false)
                tile.alpha = 1f
                activeTile = null
                draggedIcon = null
                holdReady = false
                dragging = false
                invalidate()
                return true
            }
        }
        return false
    }

    private fun beginTileGesture(tile: AppTile, rawX: Float, rawY: Float) {
        if (activeTile === tile || dismissing) return
        removeCallbacks(holdRunnable)
        activeTile = tile
        tile.isPressed = true
        tile.alpha = 1f
        animateTilePress(tile, pressed = true)
        draggedIcon = tile.app.icon.constantState?.newDrawable(resources)?.mutate() ?: tile.app.icon
        downRawX = rawX
        downRawY = rawY
        pointerRawX = rawX
        pointerRawY = rawY
        holdReady = false
        dragging = false
        postDelayed(holdRunnable, HOLD_TO_DRAG_MS)
    }

    private fun endTileGesture(tile: AppTile, rawX: Float, rawY: Float) {
        removeCallbacks(holdRunnable)
        pointerRawX = rawX
        pointerRawY = rawY
        val wasDragging = dragging
        val moved = distance(pointerRawX, pointerRawY) > touchSlop
        tile.isPressed = false
        animateTilePress(tile, pressed = false)
        tile.alpha = 1f
        activeTile = null
        draggedIcon = null
        holdReady = false
        dragging = false
        invalidate()

        if (wasDragging) {
            val local = rawToLocal(pointerRawX, pointerRawY)
            if (!isWithinDragRegion(local.first, local.second)) {
                onOpenSmallWindow(tile.app, pointerRawX.roundToInt(), pointerRawY.roundToInt())
            }
        } else if (!moved) {
            tile.performClick()
        }
    }

    private fun animateTilePress(tile: AppTile, pressed: Boolean) {
        animateTileScale(
            tile,
            if (pressed) ICON_PRESS_SCALE else TILE_REST_SCALE,
            PRESS_RELEASE_DURATION_MS,
        )
    }

    private fun animateTileHoldReady(tile: AppTile) {
        animateTileScale(tile, HOLD_READY_SCALE, HOLD_READY_DURATION_MS)
    }

    private fun animateTileScale(tile: AppTile, scale: Float, durationMs: Long) {
        tile.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            tile.scaleX = scale
            tile.scaleY = scale
            return
        }
        tile.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(durationMs)
            .setInterpolator(easing)
            .start()
    }

    private fun updateDragState(rawX: Float, rawY: Float) {
        val hasMoved = distance(rawX, rawY) > touchSlop
        if (holdReady && hasMoved && !dragging) {
            dragging = true
            activeTile?.let { animateTileScale(it, TILE_REST_SCALE, DRAG_START_DURATION_MS) }
        }
        invalidate()
    }

    private fun distance(rawX: Float, rawY: Float): Float = hypot(rawX - downRawX, rawY - downRawY)

    private fun rawToLocal(rawX: Float, rawY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rawX - location[0] to rawY - location[1]
    }

    /** Invisible quarter-circle used only to decide when a dragged icon has left the tray area. */
    private fun isWithinDragRegion(x: Float, y: Float): Boolean {
        val originX = if (corner.isRight()) width.toFloat() else 0f
        val originY = if (corner.isBottom()) height.toFloat() else 0f
        val inwardX = (x - originX) * if (corner.isRight()) -1 else 1
        val inwardY = (y - originY) * if (corner.isBottom()) -1 else 1
        return inwardX >= 0f && inwardY >= 0f && hypot(inwardX, inwardY) <= fanRadius
    }

    private fun positionTiles(width: Int, height: Int) {
        if (appTiles.isEmpty()) return
        val originX = if (corner.isRight()) width.toFloat() else 0f
        val originY = if (corner.isBottom()) height.toFloat() else 0f
        val signX = if (corner.isRight()) -1f else 1f
        val signY = if (corner.isBottom()) -1f else 1f
        val tileRadius = fanRadius - dp(36f)
        val firstAngle = Math.toRadians(FIRST_TILE_ANGLE_DEGREES.toDouble())
        val lastAngle = Math.toRadians(LAST_TILE_ANGLE_DEGREES.toDouble())
        val angleStep = if (appTiles.size <= 1) 0.0 else (lastAngle - firstAngle) / (appTiles.size - 1)

        appTiles.forEachIndexed { index, tile ->
            val angle = firstAngle + angleStep * index
            val centerX = originX + signX * tileRadius * cos(angle).toFloat()
            val centerY = originY + signY * tileRadius * sin(angle).toFloat()
            val params = tile.layoutParams as LayoutParams
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = (centerX - touchTargetSize / 2f).roundToInt()
            params.topMargin = (centerY - touchTargetSize / 2f).roundToInt()
            tile.layoutParams = params
        }
    }

    private fun drawDraggedIcon(canvas: Canvas) {
        val tile = activeTile ?: return
        val local = rawToLocal(pointerRawX, pointerRawY)
        val size = dp(ICON_SIZE_DP).toFloat()
        dragIconBounds.set(
            local.first - size / 2f,
            local.second - size / 2f,
            local.first + size / 2f,
            local.second + size / 2f,
        )
        dragClipPath.reset()
        when (iconShape) {
            HotCornersSettings.TrayIconShape.CIRCLE -> dragClipPath.addOval(dragIconBounds, Path.Direction.CW)
            HotCornersSettings.TrayIconShape.ROUNDED_RECTANGLE ->
                dragClipPath.addRoundRect(dragIconBounds, dp(14f), dp(14f), Path.Direction.CW)
            HotCornersSettings.TrayIconShape.RECTANGLE -> dragClipPath.addRect(dragIconBounds, Path.Direction.CW)
        }

        val icon = draggedIcon ?: tile.app.icon
        canvas.save()
        canvas.clipPath(dragClipPath)
        icon.setBounds(
            dragIconBounds.left.roundToInt(),
            dragIconBounds.top.roundToInt(),
            dragIconBounds.right.roundToInt(),
            dragIconBounds.bottom.roundToInt(),
        )
        icon.alpha = 220
        icon.draw(canvas)
        icon.alpha = 255
        canvas.restore()
    }

    private fun requestDismiss() {
        if (!dismissing) onDismiss()
    }

    private fun openSmallWindow(tile: AppTile) {
        val location = IntArray(2)
        tile.getLocationOnScreen(location)
        onOpenSmallWindow(
            tile.app,
            location[0] + tile.width / 2,
            location[1] + tile.height / 2,
        )
    }

    private fun showTileActions(tile: AppTile) {
        val popup = PopupMenu(context, tile)
        popup.menu.add(0, MENU_OPEN_APP, 0, context.getString(R.string.tray_menu_open_app))
        popup.menu.add(
            0,
            MENU_OPEN_SMALL_WINDOW,
            1,
            context.getString(R.string.tray_menu_open_small_window),
        )
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN_APP -> onOpenApp(tile.app)
                MENU_OPEN_SMALL_WINDOW -> openSmallWindow(tile)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun setCornerPivot() {
        pivotX = if (corner.isRight()) width.toFloat() else 0f
        pivotY = if (corner.isBottom()) height.toFloat() else 0f
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun dp(value: Float): Float = value * density

    private fun createShapeMask(sizePx: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        shape = if (iconShape == HotCornersSettings.TrayIconShape.CIRCLE) {
            GradientDrawable.OVAL
        } else {
            GradientDrawable.RECTANGLE
        }
        if (iconShape == HotCornersSettings.TrayIconShape.ROUNDED_RECTANGLE) {
            cornerRadius = dp(14f)
        }
        setSize(sizePx, sizePx)
    }

    private inner class AppTile(val app: LaunchableApp) : FrameLayout(context) {
        init {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.tray_app_accessibility_description, app.label)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            R.id.tray_action_open_small_window,
                            context.getString(R.string.tray_action_open_small_window),
                        ),
                    )
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    if (action == R.id.tray_action_open_small_window) {
                        openSmallWindow(this@AppTile)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
            foreground = RippleDrawable(
                ColorStateList.valueOf(
                    (context.getColor(R.color.md_primary) and 0x00FFFFFF) or 0x30000000,
                ),
                null,
                createShapeMask(touchTargetSize),
            )

            addView(ImageView(context).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        when (iconShape) {
                            HotCornersSettings.TrayIconShape.CIRCLE ->
                                outline.setOval(0, 0, view.width, view.height)
                            HotCornersSettings.TrayIconShape.ROUNDED_RECTANGLE ->
                                outline.setRoundRect(0, 0, view.width, view.height, dp(14f))
                            HotCornersSettings.TrayIconShape.RECTANGLE ->
                                outline.setRect(0, 0, view.width, view.height)
                        }
                    }
                }
            }, LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER))

            setOnClickListener { onOpenApp(app) }
            setOnTouchListener { _, event -> onTileTouch(this, event) }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showTileActions(this)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (!event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                return super.onGenericMotionEvent(event)
            }
            if (event.actionButton != MotionEvent.BUTTON_PRIMARY) return super.onGenericMotionEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    beginTileGesture(this, event.rawX, event.rawY)
                    return true
                }
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (activeTile === this) endTileGesture(this, event.rawX, event.rawY)
                    return true
                }
            }
            return super.onGenericMotionEvent(event)
        }
    }

    private fun HotCorner.isRight(): Boolean = this == HotCorner.TOP_RIGHT || this == HotCorner.BOTTOM_RIGHT

    private fun HotCorner.isBottom(): Boolean = this == HotCorner.BOTTOM_LEFT || this == HotCorner.BOTTOM_RIGHT

    private companion object {
        const val MAX_FAN_RADIUS_DP = 300
        const val ICON_SIZE_DP = 52
        const val TILE_TOUCH_SIZE_DP = 56
        const val FIRST_TILE_ANGLE_DEGREES = 9.0
        const val LAST_TILE_ANGLE_DEGREES = 81.0
        const val HOLD_TO_DRAG_MS = 350L
        const val HOLD_READY_DURATION_MS = 120L
        const val PRESS_RELEASE_DURATION_MS = 110L
        const val DRAG_START_DURATION_MS = 90L
        const val TILE_STAGGER_MS = 22L
        const val TILE_ENTRY_DURATION_MS = 160L
        const val TRAY_EXIT_DURATION_MS = 130L
        const val ICON_PRESS_SCALE = 0.97f
        const val HOLD_READY_SCALE = 1.02f
        const val TILE_REST_SCALE = 1f
        const val MENU_OPEN_APP = 1
        const val MENU_OPEN_SMALL_WINDOW = 2
    }
}
