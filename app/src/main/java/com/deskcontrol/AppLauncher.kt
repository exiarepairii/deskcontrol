package com.deskcontrol

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AppLauncher {
    enum class FailureReason {
        NO_EXTERNAL_DISPLAY,
        FEATURE_UNSUPPORTED,
        NO_LAUNCH_INTENT,
        SECURITY_EXCEPTION,
        START_FAILED
    }

    data class Result(
        val success: Boolean,
        val reason: FailureReason? = null,
        val detail: String? = null
    )

    fun launchOnExternalDisplay(context: Context, packageName: String): Result {
        val info = DisplaySessionManager.getExternalDisplayInfo()
            ?: return fail(FailureReason.NO_EXTERNAL_DISPLAY, "No external display detected")

        val hasFeature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
        )
        if (!hasFeature) {
            return fail(FailureReason.FEATURE_UNSUPPORTED, "Device does not support activities on secondary displays")
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return fail(FailureReason.NO_LAUNCH_INTENT, "Selected app has no launchable activity")

        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(info.displayId)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent, options.toBundle())
            SessionStore.lastLaunchFailure = null
            Result(success = true)
        } catch (se: SecurityException) {
            fail(FailureReason.SECURITY_EXCEPTION, se.message ?: "SecurityException")
        } catch (ex: Exception) {
            fail(FailureReason.START_FAILED, ex.message ?: "Unknown launch failure")
        }
    }

    private fun fail(reason: FailureReason, detail: String): Result {
        val message = "${reason.name}: $detail"
        SessionStore.lastLaunchFailure = message
        return Result(false, reason, detail)
    }
}
