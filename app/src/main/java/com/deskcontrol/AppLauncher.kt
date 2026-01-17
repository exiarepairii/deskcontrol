package com.deskcontrol

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes

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
        @StringRes val detailResId: Int? = null
    )

    fun launchOnExternalDisplay(context: Context, packageName: String): Result {
        val info = DisplaySessionManager.getExternalDisplayInfo()
            ?: return fail(
                context,
                FailureReason.NO_EXTERNAL_DISPLAY,
                R.string.app_launch_detail_no_external_display
            )

        val hasFeature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
        )
        if (!hasFeature) {
            return fail(
                context,
                FailureReason.FEATURE_UNSUPPORTED,
                R.string.app_launch_detail_feature_unsupported
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return fail(
                context,
                FailureReason.NO_LAUNCH_INTENT,
                R.string.app_launch_detail_no_launch_intent
            )

        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(info.displayId)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent, options.toBundle())
            AppLaunchHistory.increment(context.applicationContext, packageName)
            SessionStore.lastLaunchFailure = null
            Result(success = true)
        } catch (se: SecurityException) {
            fail(
                context,
                FailureReason.SECURITY_EXCEPTION,
                R.string.app_launch_detail_security_exception
            )
        } catch (ex: Exception) {
            fail(context, FailureReason.START_FAILED, R.string.app_launch_detail_unknown_failure)
        }
    }

    private fun fail(
        context: Context,
        reason: FailureReason,
        @StringRes detailResId: Int
    ): Result {
        val reasonLabel = context.getString(reasonLabelResId(reason))
        val detail = context.getString(detailResId)
        SessionStore.lastLaunchFailure = context.getString(
            R.string.app_launch_failed_with_detail,
            reasonLabel,
            detail
        )
        return Result(false, reason, detailResId)
    }

    @StringRes
    fun reasonLabelResId(reason: FailureReason): Int {
        return when (reason) {
            FailureReason.NO_EXTERNAL_DISPLAY -> R.string.app_launch_reason_no_external_display
            FailureReason.FEATURE_UNSUPPORTED -> R.string.app_launch_reason_feature_unsupported
            FailureReason.NO_LAUNCH_INTENT -> R.string.app_launch_reason_no_launch_intent
            FailureReason.SECURITY_EXCEPTION -> R.string.app_launch_reason_security_exception
            FailureReason.START_FAILED -> R.string.app_launch_reason_start_failed
        }
    }
}
