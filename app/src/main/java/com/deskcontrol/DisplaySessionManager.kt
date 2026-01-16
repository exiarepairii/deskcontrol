package com.deskcontrol

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

object DisplaySessionManager {
    data class ExternalDisplayInfo(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val rotation: Int
    )

    interface Listener {
        fun onDisplayChanged(info: ExternalDisplayInfo?)
    }

    private val listeners = mutableSetOf<Listener>()
    private var displayManager: DisplayManager? = null
    private var displayInfo: ExternalDisplayInfo? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshDisplays()
        }
    }

    fun init(context: Context) {
        if (displayManager != null) return
        displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
        refreshDisplays()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onDisplayChanged(displayInfo)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun getExternalDisplayInfo(): ExternalDisplayInfo? = displayInfo

    fun stopSession() {
        SessionStore.clear()
        ControlAccessibilityService.requestDetachOverlay()
    }

    private fun refreshDisplays() {
        val externalDisplay = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull()
        val newInfo = externalDisplay?.let { buildInfo(it) }

        val previousDisplayId = displayInfo?.displayId
        displayInfo = newInfo
        if (previousDisplayId != null && newInfo == null) {
            stopSession()
        }
        if (previousDisplayId != newInfo?.displayId) {
            ControlAccessibilityService.requestAttachToDisplay(newInfo)
        }

        listeners.forEach { it.onDisplayChanged(displayInfo) }
    }

    private fun buildInfo(display: Display): ExternalDisplayInfo {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return ExternalDisplayInfo(
            displayId = display.displayId,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            rotation = display.rotation
        )
    }
}
