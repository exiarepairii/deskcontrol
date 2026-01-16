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
            "No external display detected"
        } else {
            "Running on display ${info.displayId} (${info.width}x${info.height})"
        }

        binding.btnHostPickApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.btnHostFinish.setOnClickListener {
            DisplaySessionManager.stopSession()
            finish()
        }
    }
}
