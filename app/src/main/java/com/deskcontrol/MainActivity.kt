package com.deskcontrol

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.deskcontrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), DisplaySessionManager.Listener {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tag = "DeskControl"

        binding.btnPickApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.btnTouchpad.setOnClickListener {
            startActivity(Intent(this, TouchpadActivity::class.java))
        }
        binding.btnDiagnostics.setOnClickListener {
            Log.i(tag, "Open diagnostics tapped")
            DiagnosticsLog.add("Open diagnostics tapped")
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onStart() {
        super.onStart()
        DisplaySessionManager.addListener(this)
        updateAccessibilityHint()
    }

    override fun onStop() {
        super.onStop()
        DisplaySessionManager.removeListener(this)
    }

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        if (info == null) {
            binding.statusText.text = "External display: not connected"
            binding.displayInfoText.text = "No external display detected"
        } else {
            binding.statusText.text = "External display: connected"
            binding.displayInfoText.text =
                "DisplayId=${info.displayId}, ${info.width}x${info.height}, dpi=${info.densityDpi}, rotation=${info.rotation}"
        }
    }

    private fun updateAccessibilityHint() {
        val enabled = ControlAccessibilityService.isEnabled(this)
        val status = if (enabled) "Accessibility service enabled" else "Accessibility service not enabled"
        binding.accessibilityHint.text = status + ". Required for cursor and input injection."
    }
}
