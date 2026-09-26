package com.example.hotcorners

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import java.text.Collator

internal data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

/** Lists launchable apps visible through the manifest's launcher-intent query. */
internal object LaunchableAppRepository {
    fun load(context: Context): List<LaunchableApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = queryLauncherActivities(packageManager, launcherIntent)

        return activities
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                LaunchableApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager).toString().ifBlank { packageName },
                    icon = resolveInfo.loadIcon(packageManager),
                )
            }
            .sortedWith { left, right -> Collator.getInstance().compare(left.label, right.label) }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(
        packageManager: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L),
        )
    } else {
        packageManager.queryIntentActivities(intent, 0)
    }
}
