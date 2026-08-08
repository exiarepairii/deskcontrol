package com.deskcontrol

import android.content.Context

object PlayEntitlements {
    private const val PREFS = "play_entitlements"
    private const val KEY_SUPPORTER = "supporter_icon_pack"
    private const val KEY_REVIEW_MODE = "review_mode"

    fun hasSupporterPack(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUPPORTER, false)

    fun setSupporterPack(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUPPORTER, enabled).apply()
    }

    fun isReviewMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REVIEW_MODE, false)

    fun enableReviewMode(context: Context) {
        prefs(context).edit().putBoolean(KEY_REVIEW_MODE, true).apply()
    }

    fun hasPremiumAccess(context: Context): Boolean =
        hasSupporterPack(context) || isReviewMode(context)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
