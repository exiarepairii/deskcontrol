package com.deskcontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<android.view.View>(R.id.settingsRoot)
        applyEdgeToEdgePadding(root)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.settingsToolbar)
        toolbar.title = "Settings"
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }
}
