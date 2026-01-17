package com.deskcontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.deskcontrol.databinding.ActivityDiagnosticsBinding

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)
        binding.diagnosticsToolbar.title = getString(R.string.diagnostics_title)
        binding.diagnosticsToolbar.setNavigationOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        val displayInfo = DisplaySessionManager.getExternalDisplayInfo()
        val displayText = if (displayInfo == null) {
            getString(R.string.diagnostics_external_display_not_connected)
        } else {
            getString(
                R.string.diagnostics_external_display_info,
                displayInfo.displayId,
                displayInfo.width,
                displayInfo.height,
                displayInfo.densityDpi,
                displayInfo.rotation
            )
        }
        val accessibility = if (ControlAccessibilityService.isEnabled(this)) {
            getString(R.string.diagnostics_accessibility_enabled)
        } else {
            getString(R.string.diagnostics_accessibility_disabled)
        }
        val launchFailure = SessionStore.lastLaunchFailure ?: getString(R.string.diagnostics_none)
        val injectionResult = SessionStore.lastInjectionResult ?: getString(R.string.diagnostics_none)
        val logs = DiagnosticsLog.snapshot()

        binding.diagnosticsText.text = listOf(
            displayText,
            accessibility,
            getString(R.string.diagnostics_last_launch_failure, launchFailure),
            getString(R.string.diagnostics_last_injection_result, injectionResult),
            getString(R.string.diagnostics_logs_label),
            if (logs.isEmpty()) getString(R.string.diagnostics_logs_empty) else logs.joinToString("\n")
        ).joinToString("\n")
    }
}
