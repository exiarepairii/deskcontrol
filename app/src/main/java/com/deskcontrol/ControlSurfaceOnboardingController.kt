package com.deskcontrol

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Coordinates the post-intro, post-accessibility app-selection prompt. */
class ControlSurfaceOnboardingController(
    private val activity: AppCompatActivity,
    private val logName: String,
    private val canPrompt: () -> Boolean
) {
    private var promptedThisEntry = false
    private var dialog: AlertDialog? = null

    fun onStateChanged() {
        if (promptedThisEntry || dialog != null || !canPrompt()) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (!ControlAccessibilityService.isEnabled(activity)) return
        if (DisplaySessionManager.getExternalDisplayInfo() == null) return
        if (SessionStore.lastLaunchedPackage != null) return

        promptedThisEntry = true
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.control_surface_choose_app_title)
            .setMessage(R.string.control_surface_choose_app_message)
            .setPositiveButton(R.string.control_surface_choose_app_action) { _, _ ->
                DiagnosticsLog.add("$logName: onboarding app picker accepted")
                activity.startActivity(
                    Intent(activity, AppPickerActivity::class.java).apply {
                        putExtra(AppPickerActivity.EXTRA_RETURN_TO_CALLER, true)
                    }
                )
            }
            .setNegativeButton(R.string.control_surface_choose_app_not_now) { _, _ ->
                DiagnosticsLog.add("$logName: onboarding app picker declined")
            }
            .setOnCancelListener {
                DiagnosticsLog.add("$logName: onboarding app picker cancelled")
            }
            .setOnDismissListener { dialog = null }
            .show()
    }

    fun onDestroy() {
        dialog?.dismiss()
        dialog = null
    }
}
