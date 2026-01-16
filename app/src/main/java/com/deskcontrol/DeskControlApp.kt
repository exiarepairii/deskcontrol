package com.deskcontrol

import android.app.Application

class DeskControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DisplaySessionManager.init(this)
    }
}
