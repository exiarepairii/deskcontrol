package com.deskcontrol

import android.content.Context
import android.content.pm.PackageManager

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable
)

object LaunchableAppCatalog {
    fun load(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        return apps.map { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            LaunchableApp(
                label = resolveInfo.loadLabel(pm).toString(),
                packageName = appInfo.packageName,
                icon = resolveInfo.loadIcon(pm)
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
