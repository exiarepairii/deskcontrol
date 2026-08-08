package com.deskcontrol

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Process

object DiagnosticsState {
    fun process(): String {
        return "pid=${Process.myPid()} device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "sdk=${Build.VERSION.SDK_INT}"
    }

    fun activity(activity: Activity): String {
        val orientation = when (activity.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_UNDEFINED -> "undefined"
            else -> activity.resources.configuration.orientation.toString()
        }
        return "pid=${Process.myPid()} instance=${System.identityHashCode(activity)} " +
            "orientation=$orientation rotation=${activity.display?.rotation ?: -1} " +
            "displayId=${activity.display?.displayId ?: -1}"
    }
}
