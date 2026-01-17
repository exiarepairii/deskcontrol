package com.deskcontrol

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class DeskControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        AppCompatDelegate.setDefaultNightMode(SettingsStore.nightMode)
        DisplaySessionManager.init(this)
    }
}
