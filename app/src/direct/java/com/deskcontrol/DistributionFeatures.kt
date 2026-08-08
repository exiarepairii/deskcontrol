package com.deskcontrol

import android.content.Context
import android.view.View

/** Direct builds intentionally contain no Play Billing or alternate icon UI. */
object DistributionFeatures {
    @Suppress("UNUSED_PARAMETER")
    fun createSettingsRow(context: Context): View? = null
}
