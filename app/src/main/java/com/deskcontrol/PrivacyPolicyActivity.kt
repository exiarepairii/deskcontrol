package com.deskcontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar

class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_policy)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(findViewById(R.id.privacyRoot))
        findViewById<MaterialToolbar>(R.id.privacyToolbar)
            .setNavigationOnClickListener { finish() }
    }
}
