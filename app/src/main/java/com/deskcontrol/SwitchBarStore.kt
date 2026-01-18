package com.deskcontrol

import android.content.Context

object SwitchBarStore {
    private const val PREFS_NAME = "switch_bar_prefs"
    private const val KEY_FAVORITES = "favorites"
    private const val MAX_FAVORITES = 4

    fun getFavorites(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FAVORITES, null)
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setFavorites(context: Context, packageNames: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FAVORITES, packageNames.joinToString("|")).apply()
    }

    fun ensureFavorites(
        context: Context,
        launchablePackages: List<String>
    ): List<String> {
        val existing = getFavorites(context).toMutableList()
        val trimmed = existing.distinct().filter { it in launchablePackages }.toMutableList()
        if (trimmed.size >= MAX_FAVORITES) return trimmed.take(MAX_FAVORITES)

        val candidates = launchablePackages
            .sortedByDescending { AppLaunchHistory.getCount(context, it) }
            .filter { it !in trimmed }

        val filled = (trimmed + candidates).take(MAX_FAVORITES)
        if (filled.isNotEmpty()) {
            setFavorites(context, filled)
        }
        return filled
    }
}
