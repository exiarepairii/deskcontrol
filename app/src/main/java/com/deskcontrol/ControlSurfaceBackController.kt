package com.deskcontrol

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** Shared system-Back behavior for phone-side control surfaces. */
class ControlSurfaceBackController(
    private val activity: AppCompatActivity,
    private val logName: String,
    private val isControlActive: () -> Boolean
) {
    init {
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            }
        )
    }

    fun warmUpOnResume(reason: String) {
        ControlAccessibilityService.current()?.warmUpBackPipeline()
        if (SettingsStore.touchpadAutoFocusEnabled) {
            ControlAccessibilityService.requestExternalFocusWarmup(reason)
        }
    }

    fun warmUpOnActivation(reason: String) {
        if (SettingsStore.touchpadAutoFocusEnabled) {
            ControlAccessibilityService.requestExternalFocusWarmup(reason)
        }
    }

    private fun handleBack() {
        if (!isControlActive()) {
            DiagnosticsLog.add("$logName: back exits inactive control page")
            activity.finish()
            return
        }
        if (DisplaySessionManager.getExternalDisplayInfo() == null) {
            DiagnosticsLog.add("$logName: back blocked (no external display)")
            Toast.makeText(
                activity,
                activity.getString(R.string.touchpad_no_external_display),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        DiagnosticsLog.add("$logName: back requested t=${SystemClock.uptimeMillis()}")
        val service = ControlAccessibilityService.current()
        if (service == null) {
            DiagnosticsLog.add("$logName: back failed (accessibility missing)")
            Toast.makeText(
                activity,
                activity.getString(R.string.touchpad_accessibility_required_toast),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val success = service.performBack()
        if (!success) {
            val messageRes = when (SessionStore.lastBackFailure) {
                "external_not_focused" -> R.string.touchpad_back_external_not_focused
                "external_window_missing" -> R.string.touchpad_back_external_window_missing
                "dispatch_failed" -> R.string.touchpad_back_dispatch_failed
                else -> null
            }
            messageRes?.let { service.showToastOnExternalDisplay(activity.getString(it)) }
        }
        DiagnosticsLog.add("$logName: back forwarded success=$success")
    }
}
