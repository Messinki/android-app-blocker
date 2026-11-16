package com.appblocker.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

// Referenced by name because the class lives in another package/file.
private const val SERVICE_CLASS = "com.appblocker.blocker.BlockerAccessibilityService"

/** True if our accessibility service is switched on in system settings. */
fun isBlockerServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val target = ComponentName(context.packageName, SERVICE_CLASS)
    // Entries look like "pkg/cls" and may use the short ".cls" form, so parse rather than string-compare.
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
}
