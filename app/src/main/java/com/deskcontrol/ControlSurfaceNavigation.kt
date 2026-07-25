package com.deskcontrol

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

enum class ControlSurfaceMode(val persistedValue: String) {
    TOUCHPAD("touchpad"),
    RAY_MOUSE("ray_mouse");

    companion object {
        fun fromPersistedValue(value: String?): ControlSurfaceMode {
            return entries.firstOrNull { it.persistedValue == value } ?: TOUCHPAD
        }
    }
}

fun Context.lastControlSurfaceIntent(): Intent {
    val target = when (SettingsStore.lastControlSurface) {
        ControlSurfaceMode.TOUCHPAD -> TouchpadActivity::class.java
        ControlSurfaceMode.RAY_MOUSE -> RayMouseActivity::class.java
    }
    return Intent(this, target)
}

fun AppCompatActivity.switchControlSurface(target: Class<out AppCompatActivity>) {
    startActivity(Intent(this, target))
    finish()
}
