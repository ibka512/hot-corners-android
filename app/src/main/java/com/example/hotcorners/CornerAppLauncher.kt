package com.example.hotcorners

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import kotlin.math.roundToInt

internal enum class AppLaunchResult {
    STARTED,
    APP_NOT_FOUND,
    START_REJECTED,
}

/** Starts the selected app, optionally requesting desktop/freeform window bounds. */
internal object CornerAppLauncher {
    private const val TAG = "HotCornersAppLauncher"

    fun launch(context: Context, packageName: String, inSmallWindow: Boolean): AppLaunchResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return AppLaunchResult.APP_NOT_FOUND
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            if (inSmallWindow) {
                val options = ActivityOptions.makeBasic()
                    .setLaunchBounds(smallWindowBounds(context))
                context.startActivity(launchIntent, options.toBundle())
            } else {
                context.startActivity(launchIntent)
            }
            AppLaunchResult.STARTED
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No launch activity for $packageName", exception)
            AppLaunchResult.APP_NOT_FOUND
        } catch (exception: RuntimeException) {
            // Background-launch restrictions and OEM policies can reject this request.
            Log.w(TAG, "Could not launch $packageName", exception)
            AppLaunchResult.START_REJECTED
        }
    }

    private fun smallWindowBounds(context: Context): Rect {
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
        val left = (width - requestedWidth) / 2
        val top = (height - requestedHeight) / 2
        return Rect(left, top, left + requestedWidth, top + requestedHeight)
    }
}
