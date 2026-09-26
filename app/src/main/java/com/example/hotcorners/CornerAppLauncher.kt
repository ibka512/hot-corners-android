package com.example.hotcorners

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import kotlin.math.roundToInt

internal enum class AppLaunchResult {
    STARTED,
    PENDING,
    APP_NOT_FOUND,
    START_REJECTED,
}

/** Starts the selected app, optionally requesting desktop/freeform window bounds. */
internal object CornerAppLauncher {
    private const val TAG = "HotCornersAppLauncher"
    private const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
    private const val WINDOWING_MODE_FREEFORM = 5

    fun launch(
        context: Context,
        packageName: String,
        inSmallWindow: Boolean,
        preferredWindowCenter: Point? = null,
        onComplete: ((AppLaunchResult) -> Unit)? = null,
    ): AppLaunchResult {
        val launchContext = context.applicationContext
        val launchIntent = launchContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return AppLaunchResult.APP_NOT_FOUND
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val bounds = if (inSmallWindow) smallWindowBounds(launchContext, preferredWindowCenter) else null
        val component = launchIntent.component
        if (bounds != null && component != null && ShizukuFreeformLauncher.isAuthorized(launchContext)) {
            ShizukuFreeformLauncher.launch(component, bounds) { requestResult ->
                val result = when (requestResult) {
                    FreeformRequestResult.CONFIRMED -> AppLaunchResult.STARTED
                    FreeformRequestResult.START_FAILED -> startWithBounds(launchContext, launchIntent, bounds)
                    FreeformRequestResult.MODE_NOT_CONFIRMED -> AppLaunchResult.START_REJECTED
                }
                onComplete?.invoke(result)
            }
            return AppLaunchResult.PENDING
        }

        return if (bounds != null) {
            startWithBounds(launchContext, launchIntent, bounds)
        } else {
            startNormally(launchContext, launchIntent)
        }
    }

    private fun startWithBounds(context: Context, launchIntent: Intent, bounds: Rect): AppLaunchResult = try {
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        val launchOptions = options.toBundle().apply {
            // HyperOS may ignore launch bounds alone; its ActivityTaskManager honors this AOSP option.
            putInt(WINDOWING_MODE_KEY, WINDOWING_MODE_FREEFORM)
        }
        context.startActivity(launchIntent, launchOptions)
        AppLaunchResult.STARTED
    } catch (exception: ActivityNotFoundException) {
        Log.w(TAG, "No launch activity for ${launchIntent.component}", exception)
        AppLaunchResult.APP_NOT_FOUND
    } catch (exception: RuntimeException) {
        // Background-launch restrictions and OEM policies can reject this request.
        Log.w(TAG, "Could not launch ${launchIntent.component} with requested bounds", exception)
        AppLaunchResult.START_REJECTED
    }

    private fun startNormally(context: Context, launchIntent: Intent): AppLaunchResult = try {
        context.startActivity(launchIntent)
        AppLaunchResult.STARTED
    } catch (exception: ActivityNotFoundException) {
        Log.w(TAG, "No launch activity for ${launchIntent.component}", exception)
        AppLaunchResult.APP_NOT_FOUND
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Could not launch ${launchIntent.component}", exception)
        AppLaunchResult.START_REJECTED
    }

    private fun smallWindowBounds(context: Context, preferredCenter: Point?): Rect {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.density
        val requestedWidth = (width * 0.68f).roundToInt()
            .coerceAtLeast((320 * density).roundToInt())
            .coerceAtMost(width)
        val requestedHeight = (height * 0.68f).roundToInt()
            .coerceAtLeast((360 * density).roundToInt())
            .coerceAtMost(height)
        val centerX = (preferredCenter?.x ?: width / 2)
            .coerceIn(requestedWidth / 2, width - requestedWidth / 2)
        val centerY = (preferredCenter?.y ?: height / 2)
            .coerceIn(requestedHeight / 2, height - requestedHeight / 2)
        val left = centerX - requestedWidth / 2
        val top = centerY - requestedHeight / 2
        return Rect(left, top, left + requestedWidth, top + requestedHeight)
    }
}
