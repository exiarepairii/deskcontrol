package com.deskcontrol

import android.content.Context
import android.content.Intent
import android.view.View

/** Entry points that exist only in the Google Play distribution. */
object DistributionFeatures {
    fun createSettingsRow(context: Context): View? =
        context.navigationRow(
            R.drawable.ic_settings_support,
            R.string.play_support_title,
            context.getString(R.string.play_support_summary)
        ) {
            context.startActivity(Intent(context, PlaySupportActivity::class.java))
        }
}
