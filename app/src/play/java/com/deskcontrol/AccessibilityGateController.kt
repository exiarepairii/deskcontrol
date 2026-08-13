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
    private var destroyed = false
    private var runtimeListenerRegistered = false
    private val runtimeStateListener: () -> Unit = {
        if (!destroyed) refresh()
    }

    init {
        advancedEnableButton.isVisible = false
        openSettingsButton.setOnClickListener {
            AccessibilityDisclosure.showIfNeeded(activity) {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    fun onStart() {
        if (!runtimeListenerRegistered) {
            ControlAccessibilityService.addRuntimeStateListener(runtimeStateListener)
            runtimeListenerRegistered = true
        }
        refresh()
    }

    fun refresh() {
        val configured = ControlAccessibilityService.isConfigured(activity)
        val ready = configured && ControlAccessibilityService.isReady()
        gate.isVisible = !configured
        content.alpha = if (configured) 1f else DISABLED_CONTENT_ALPHA
        controlArea.isEnabled = ready
        tuningPanel.isEnabled = configured
        onEnabledChanged(ready)
    }

    fun onStop() {
        if (runtimeListenerRegistered) {
            ControlAccessibilityService.removeRuntimeStateListener(runtimeStateListener)
            runtimeListenerRegistered = false
        }
    }

    fun onDestroy() {
        destroyed = true
        onStop()
    }

    private companion object {
        const val DISABLED_CONTENT_ALPHA = 0.35f
    }
}
