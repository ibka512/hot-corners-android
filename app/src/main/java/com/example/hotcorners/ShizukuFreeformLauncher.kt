package com.example.hotcorners

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

internal enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
}

internal enum class FreeformRequestResult {
    CONFIRMED,
    START_FAILED,
    MODE_NOT_CONFIRMED,
}

/** Optional shell-identity channel for explicitly requesting Android freeform window mode. */
internal object ShizukuFreeformLauncher {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val TAG = "HotCornersFreeform"
    private const val FREEFORM_MODE = "5"
    private const val SHIZUKU_NEW_PROCESS_METHOD = "newProcess"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val launchExecutor = Executors.newSingleThreadExecutor()

    fun state(context: Context): ShizukuState {
        if (Shizuku.pingBinder()) {
            return try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuState.READY
                } else {
                    ShizukuState.PERMISSION_REQUIRED
                }
            } catch (_: RuntimeException) {
                ShizukuState.NOT_RUNNING
            }
        }

        return if (isShizukuInstalled(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
    }

    fun isAuthorized(context: Context): Boolean = state(context) == ShizukuState.READY

    fun launch(
        component: ComponentName,
        bounds: Rect,
        onComplete: (FreeformRequestResult) -> Unit,
    ) {
        val requestedBounds = Rect(bounds)
        launchExecutor.execute {
            val result = runCatching {
                startWithShell(component, requestedBounds)
            }.onFailure { exception ->
                Log.w(TAG, "Shizuku freeform launch failed; caller may use the regular launch path", exception)
            }.getOrDefault(FreeformRequestResult.START_FAILED)
            mainHandler.post { onComplete(result) }
        }
    }

    private fun startWithShell(component: ComponentName, bounds: Rect): FreeformRequestResult {
        val start = runShizukuCommand(
            "/system/bin/am",
            "start",
            "-W",
            "--user",
            "current",
            "--windowingMode",
            FREEFORM_MODE,
            "-n",
            component.flattenToString(),
        )
        if (!start.accepted) {
            Log.w(TAG, "Freeform start request failed: ${start.output}")
            return FreeformRequestResult.START_FAILED
        }

        // Android 16's `am start` does not accept launch bounds, so resize the new task afterward.
        val packageName = component.packageName
        val taskDump = runShizukuCommand("/system/bin/dumpsys", "activity", "activities").output
        val taskHeader = taskHeaderFor(taskDump, packageName)
        val taskId = taskHeader?.substringAfter("#")?.takeWhile(Char::isDigit)
        if (taskId.isNullOrEmpty()) {
            Log.w(TAG, "Freeform task was not found for $packageName")
            return FreeformRequestResult.MODE_NOT_CONFIRMED
        }

        val resize = runShizukuCommand(
            "/system/bin/am",
            "task",
            "resize",
            taskId,
            bounds.left.toString(),
            bounds.top.toString(),
            bounds.right.toString(),
            bounds.bottom.toString(),
        )
        val finalDump = runShizukuCommand("/system/bin/dumpsys", "activity", "activities").output
        val freeformConfirmed = taskHeaderFor(finalDump, packageName)?.contains("mode=freeform") == true
        Log.i(
            TAG,
            "Freeform confirmed=$freeformConfirmed resizeAccepted=${resize.accepted} " +
                "task=$taskId output=${resize.output}",
        )
        return if (freeformConfirmed) {
            FreeformRequestResult.CONFIRMED
        } else {
            FreeformRequestResult.MODE_NOT_CONFIRMED
        }
    }

    private fun taskHeaderFor(dump: String, packageName: String): String? =
        dump.lineSequence().firstOrNull { line ->
            line.contains("Task{") && line.contains("A=") && line.contains(":$packageName") &&
                line.contains("mode=")
        }

    /**
     * Shizuku's supported UserService path crashes on this Xiaomi/MediaTek build while creating its
     * shell-process application context. Shizuku API 13.1.5 still contains its command-process bridge;
     * reflection is limited to that library method, and the application does not reflect Android APIs.
     */
    private fun runShizukuCommand(vararg command: String): CommandResult {
        val method = Shizuku::class.java.getDeclaredMethod(
            SHIZUKU_NEW_PROCESS_METHOD,
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, command, null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        return CommandResult(
            accepted = exitCode == 0 && !output.contains("Error:", ignoreCase = true) &&
                !error.contains("Error:", ignoreCase = true),
            output = listOf(output, error).filter(String::isNotEmpty).joinToString("\n"),
        )
    }

    private fun isShizukuInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private data class CommandResult(val accepted: Boolean, val output: String)
}
