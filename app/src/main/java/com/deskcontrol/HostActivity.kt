package com.deskcontrol

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.deskcontrol.databinding.ActivityHostBinding

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val info = DisplaySessionManager.getExternalDisplayInfo()
        binding.hostInfo.text = if (info == null) {
            getString(R.string.host_no_external_display)
        } else {
            getString(
                R.string.host_running_display,
                info.displayId,
                info.width,
                info.height
            )
        }

        binding.btnHostPickApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.btnHostFinish.setOnClickListener {
            DisplaySessionManager.stopSession()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateKeepScreenOn(true)
    }

    override fun onPause() {
        updateKeepScreenOn(false)
        super.onPause()
    }

    private fun updateKeepScreenOn(visible: Boolean) {
        val hasSession = DisplaySessionManager.getExternalDisplayInfo() != null
        if (visible && hasSession && SettingsStore.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
