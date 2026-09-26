package com.example.hotcorners

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Full-screen input layer with a visible quarter-circle tray anchored to one screen corner. */
internal class CornerAppTrayOverlay(
    context: Context,
    private val corner: HotCorner,
    private val apps: List<LaunchableApp>,
    private val onOpenApp: (LaunchableApp) -> Unit,
    private val onOpenSmallWindow: (LaunchableApp, Int, Int) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val tileWidth = dp(TILE_WIDTH_DP)
    private val tileHeight = dp(TILE_HEIGHT_DP)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val sectorPath = Path()
    private val sectorBounds = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.md_surface_container_high)
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.md_outline_variant)
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val easing = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val appTiles = mutableListOf<AppTile>()
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
        if (activeTile != null) {
            holdReady = true
            updateDragState(pointerRawX, pointerRawY)
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.app_tray_accessibility_description)

        apps.forEach { app ->
            val tile = AppTile(app)
            appTiles += tile
            addView(tile, LayoutParams(tileWidth, tileHeight))
            tile.alpha = 0f
            tile.scaleX = 0.96f
            tile.scaleY = 0.96f
        }
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
        activeTile?.isPressed = false
        activeTile = null
        draggedIcon = null

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
        fanRadius = minOf(dp(MAX_FAN_RADIUS_DP).toFloat(), width - dp(72f), height - dp(72f))
            .coerceAtLeast(dp(128f))
        buildSectorPath(width, height)
        positionTiles(width, height)
        setCornerPivot()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(sectorPath, fillPaint)
        canvas.drawPath(sectorPath, outlinePaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (dragging) drawDraggedIcon(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !containsInFan(event.x, event.y)) {
            requestDismiss()
        }
        // The full-screen accessibility overlay intentionally consumes the dismissing click.
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            !containsInFan(event.x, event.y)
        ) {
            requestDismiss()
            return true
        }
        return super.onGenericMotionEvent(event)
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
        if (activeTile === tile) return
        removeCallbacks(holdRunnable)
        activeTile = tile
        tile.isPressed = true
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
        activeTile = null
        draggedIcon = null
        holdReady = false
        dragging = false
        invalidate()

        if (wasDragging) {
            val local = rawToLocal(pointerRawX, pointerRawY)
            if (!containsInFan(local.first, local.second)) {
                onOpenSmallWindow(tile.app, pointerRawX.roundToInt(), pointerRawY.roundToInt())
            }
        } else if (!moved) {
            tile.performClick()
        }
    }

    private fun updateDragState(rawX: Float, rawY: Float) {
        val hasMoved = distance(rawX, rawY) > touchSlop
        if (holdReady && hasMoved) dragging = true
        invalidate()
    }

    private fun distance(rawX: Float, rawY: Float): Float = hypot(rawX - downRawX, rawY - downRawY)

    private fun rawToLocal(rawX: Float, rawY: Float): Pair<Float, Float> {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rawX - location[0] to rawY - location[1]
    }

    private fun containsInFan(x: Float, y: Float): Boolean {
        val originX = if (corner.isRight()) width.toFloat() else 0f
        val originY = if (corner.isBottom()) height.toFloat() else 0f
        val inwardX = (x - originX) * if (corner.isRight()) -1 else 1
        val inwardY = (y - originY) * if (corner.isBottom()) -1 else 1
        return inwardX >= 0f && inwardY >= 0f && hypot(inwardX, inwardY) <= fanRadius
    }

    private fun buildSectorPath(width: Int, height: Int) {
        val originX = if (corner.isRight()) width.toFloat() else 0f
        val originY = if (corner.isBottom()) height.toFloat() else 0f
        val radius = fanRadius
        val left = originX - radius
        val top = originY - radius
        sectorBounds.set(left, top, left + radius * 2f, top + radius * 2f)
        sectorPath.reset()
        sectorPath.moveTo(originX, originY)
        sectorPath.lineTo(if (corner.isRight()) originX - radius else originX + radius, originY)
        val (startAngle, sweepAngle) = when (corner) {
            HotCorner.TOP_LEFT -> 0f to 90f
            HotCorner.TOP_RIGHT -> 180f to -90f
            HotCorner.BOTTOM_LEFT -> 0f to -90f
            HotCorner.BOTTOM_RIGHT -> 180f to 90f
        }
        sectorPath.arcTo(sectorBounds, startAngle, sweepAngle, false)
        sectorPath.close()
    }

    private fun positionTiles(width: Int, height: Int) {
        if (appTiles.isEmpty()) return
        val originX = if (corner.isRight()) width.toFloat() else 0f
        val originY = if (corner.isBottom()) height.toFloat() else 0f
        val signX = if (corner.isRight()) -1f else 1f
        val signY = if (corner.isBottom()) -1f else 1f
        val tileRadius = fanRadius - dp(48f)
        val firstAngle = Math.toRadians(FIRST_TILE_ANGLE_DEGREES.toDouble())
        val lastAngle = Math.toRadians(LAST_TILE_ANGLE_DEGREES.toDouble())
        val angleStep = if (appTiles.size <= 1) 0.0 else (lastAngle - firstAngle) / (appTiles.size - 1)

        appTiles.forEachIndexed { index, tile ->
            val angle = firstAngle + angleStep * index
            val centerX = originX + signX * tileRadius * cos(angle).toFloat()
            val centerY = originY + signY * tileRadius * sin(angle).toFloat()
            val params = tile.layoutParams as LayoutParams
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = (centerX - tileWidth / 2f).roundToInt()
            params.topMargin = (centerY - tileHeight / 2f).roundToInt()
            tile.layoutParams = params
        }
    }

    private fun drawDraggedIcon(canvas: Canvas) {
        val tile = activeTile ?: return
        val local = rawToLocal(pointerRawX, pointerRawY)
        val icon = draggedIcon ?: tile.app.icon
        val iconSize = dp(36)
        val left = (local.first - iconSize / 2f).roundToInt()
        val top = (local.second - iconSize / 2f).roundToInt()
        icon.setBounds(left, top, left + iconSize, top + iconSize)
        icon.alpha = 220
        icon.draw(canvas)
        icon.alpha = 255
    }

    private fun requestDismiss() {
        if (!dismissing) onDismiss()
    }

    private fun setCornerPivot() {
        pivotX = if (corner.isRight()) width.toFloat() else 0f
        pivotY = if (corner.isBottom()) height.toFloat() else 0f
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun dp(value: Float): Float = value * density

    private inner class AppTile(val app: LaunchableApp) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(3))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.md_surface))
                cornerRadius = dp(14).toFloat()
            }
            foreground = RippleDrawable(
                ColorStateList.valueOf(
                    (context.getColor(R.color.md_primary) and 0x00FFFFFF) or 0x30000000,
                ),
                null,
                null,
            )
            elevation = dp(4).toFloat()
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.tray_app_accessibility_description, app.label)

            addView(ImageView(context).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            }, LayoutParams(dp(34), dp(34)))
            addView(TextView(context).apply {
                text = app.label
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.md_on_surface))
                includeFontPadding = false
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

            setOnClickListener { onOpenApp(app) }
            setOnTouchListener { _, event -> onTileTouch(this, event) }
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
        const val MAX_FAN_RADIUS_DP = 260
        const val TILE_WIDTH_DP = 64
        const val TILE_HEIGHT_DP = 58
        const val FIRST_TILE_ANGLE_DEGREES = 13.0
        const val LAST_TILE_ANGLE_DEGREES = 77.0
        const val HOLD_TO_DRAG_MS = 350L
        const val TILE_STAGGER_MS = 22L
        const val TILE_ENTRY_DURATION_MS = 160L
        const val TRAY_EXIT_DURATION_MS = 130L
    }
}
