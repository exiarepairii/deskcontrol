package com.deskcontrol

import android.content.Context

object SwitchBarStore {
    private const val PREFS_NAME = "switch_bar_prefs"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_FAVORITE_SLOT_PREFIX = "favorite_slot_"
    private const val KEY_DEFAULT_SEEDED = "default_seeded"
    private const val MAX_FAVORITES = 3

    fun getFavoriteSlots(context: Context): List<String?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val slots = MutableList<String?>(MAX_FAVORITES) { index ->
            prefs.getString(slotKey(index), null)?.trim()?.ifEmpty { null }
        }
        if (!prefs.getBoolean(KEY_DEFAULT_SEEDED, false)) {
            val browserPackage = resolveDefaultBrowserPackage(context)
            if (!browserPackage.isNullOrBlank() && slots[0].isNullOrBlank()) {
                prefs.edit()
                    .putString(slotKey(0), browserPackage)
                    .putBoolean(KEY_DEFAULT_SEEDED, true)
                    .apply()
                slots[0] = browserPackage
            } else {
                prefs.edit().putBoolean(KEY_DEFAULT_SEEDED, true).apply()
            }
        }
        if (slots.any { !it.isNullOrBlank() }) return slots
        val raw = prefs.getString(KEY_FAVORITES, null)
        if (raw.isNullOrBlank()) return slots
        val migrated = raw.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_FAVORITES)
        if (migrated.isNotEmpty()) {
            val editor = prefs.edit()
            migrated.forEachIndexed { index, pkg ->
                editor.putString(slotKey(index), pkg)
            }
            editor.remove(KEY_FAVORITES).apply()
        }
        return slots.apply {
            migrated.forEachIndexed { index, pkg ->
                this[index] = pkg
            }
        }
    }

    fun setFavoriteSlot(context: Context, slotIndex: Int, packageName: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (slotIndex !in 0 until MAX_FAVORITES) return
        val editor = prefs.edit()
        if (packageName.isNullOrBlank()) {
            editor.remove(slotKey(slotIndex))
        } else {
            for (i in 0 until MAX_FAVORITES) {
                if (i == slotIndex) continue
                if (prefs.getString(slotKey(i), null) == packageName) {
                    editor.remove(slotKey(i))
                }
            }
            editor.putString(slotKey(slotIndex), packageName)
        }
        editor.apply()
    }

    fun getFavorites(
        context: Context,
        launchablePackages: List<String>
    ): List<String> {
        val slots = getFavoriteSlots(context)
        val filtered = slots.mapNotNull { pkg ->
            pkg?.takeIf { it in launchablePackages }
        }.distinct().take(MAX_FAVORITES)
        if (filtered.size < slots.count { !it.isNullOrBlank() }) {
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            for (i in 0 until MAX_FAVORITES) {
                editor.remove(slotKey(i))
            }
            filtered.forEachIndexed { index, pkg ->
                editor.putString(slotKey(index), pkg)
            }
            editor.apply()
        }
        return filtered
    }

    private fun slotKey(index: Int): String = "$KEY_FAVORITE_SLOT_PREFIX$index"

    private fun resolveDefaultBrowserPackage(context: Context): String? {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("http://www.example.com")
        )
        val pm = context.packageManager
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                ?.activityInfo
                ?.packageName
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName
        }
    }
}
