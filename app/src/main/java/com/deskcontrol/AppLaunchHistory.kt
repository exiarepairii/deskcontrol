package com.deskcontrol

import android.content.Context

object AppLaunchHistory {
    private const val PREFS_NAME = "app_launch_history"
    private const val KEY_RECENTS = "_recents"
    private const val MAX_RECENTS = 10

    fun recordLaunch(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(packageName, 0)
        val updatedRecents = updateRecents(prefs, packageName)
        prefs.edit()
            .putInt(packageName, current + 1)
            .putString(KEY_RECENTS, updatedRecents)
            .apply()
    }

    fun getCount(context: Context, packageName: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(packageName, 0)
    }

    fun getRecent(context: Context, max: Int): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return parseRecents(prefs.getString(KEY_RECENTS, null)).take(max)
    }

    private fun updateRecents(
        prefs: android.content.SharedPreferences,
        packageName: String
    ): String {
        val current = parseRecents(prefs.getString(KEY_RECENTS, null)).toMutableList()
        current.remove(packageName)
        current.add(0, packageName)
        if (current.size > MAX_RECENTS) {
            current.subList(MAX_RECENTS, current.size).clear()
        }
        return current.joinToString("|")
    }

    private fun parseRecents(serialized: String?): List<String> {
        if (serialized.isNullOrBlank()) return emptyList()
        return serialized.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
