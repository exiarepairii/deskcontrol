package com.deskcontrol

import android.content.Context

object AppLaunchHistory {
    private const val PREFS_NAME = "app_launch_history"

    fun increment(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(packageName, 0)
        prefs.edit().putInt(packageName, current + 1).apply()
    }

    fun getCount(context: Context, packageName: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(packageName, 0)
    }
}
