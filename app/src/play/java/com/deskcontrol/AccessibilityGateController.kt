package com.deskcontrol

import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

/** Google Play accessibility gate. Play builds never include or invoke Shizuku. */
class AccessibilityGateController(
    private val activity: AppCompatActivity,
    private val gate: View,
    private val content: View,
    private val controlArea: View,
    private val tuningPanel: View,
    private val openSettingsButton: View,
    advancedEnableButton: View,
    private val onEnabledChanged: (Boolean) -> Unit
) {
    init {
        advancedEnableButton.isVisible = false
        openSettingsButton.setOnClickListener {
            AccessibilityDisclosure.showIfNeeded(activity) {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    fun onStart() = refresh()

    fun refresh() {
        val enabled = ControlAccessibilityService.isEnabled(activity)
        gate.isVisible = !enabled
        content.alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA
        controlArea.isEnabled = enabled
        tuningPanel.isEnabled = enabled
        onEnabledChanged(enabled)
    }

    fun onDestroy() = Unit

    private companion object {
        const val DISABLED_CONTENT_ALPHA = 0.35f
    }
}
