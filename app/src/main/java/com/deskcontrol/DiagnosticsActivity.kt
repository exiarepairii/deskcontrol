package com.deskcontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.deskcontrol.databinding.ActivityDiagnosticsBinding

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onStart() {
        super.onStart()
        val displayInfo = DisplaySessionManager.getExternalDisplayInfo()
        val displayText = if (displayInfo == null) {
            "External display: not connected"
        } else {
            "External display: id=${displayInfo.displayId}, size=${displayInfo.width}x${displayInfo.height}, dpi=${displayInfo.densityDpi}, rotation=${displayInfo.rotation}"
        }
        val accessibility = if (ControlAccessibilityService.isEnabled(this)) {
            "Accessibility: enabled"
        } else {
            "Accessibility: disabled"
        }
        val launchFailure = SessionStore.lastLaunchFailure ?: "None"
        val injectionResult = SessionStore.lastInjectionResult ?: "None"
        val logs = DiagnosticsLog.snapshot()

        binding.diagnosticsText.text = listOf(
            displayText,
            accessibility,
            "Last launch failure: $launchFailure",
            "Last injection result: $injectionResult",
            "Logs:",
            if (logs.isEmpty()) "No logs yet" else logs.joinToString("\n")
        ).joinToString("\n")
    }
}
