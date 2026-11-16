package com.appblocker.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** One launchable app, with a small pre-rendered icon. */
class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

private const val ICON_SIZE_PX = 96 // small and fixed, to keep memory low
private const val OWN_PACKAGE = "com.appblocker"

/** Blocking call: run it on Dispatchers.IO. Returns apps sorted by label, excluding this app. */
fun loadLaunchableApps(pm: PackageManager): List<AppInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .filter { it.activityInfo.packageName != OWN_PACKAGE }
        .distinctBy { it.activityInfo.packageName }
        .map {
            AppInfo(
                packageName = it.activityInfo.packageName,
                label = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}
