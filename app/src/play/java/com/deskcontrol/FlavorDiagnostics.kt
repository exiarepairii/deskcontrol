package com.deskcontrol

import android.content.Context

object FlavorDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun snapshot(context: Context): List<String> =
        listOf("DistributionState: flavor=play shizuku=not_packaged")
}
