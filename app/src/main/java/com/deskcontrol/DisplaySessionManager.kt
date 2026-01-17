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
        fun onDisplaysUpdated(displays: List<ExternalDisplayInfo>, selectedDisplayId: Int?) {}
    }

    private val listeners = mutableSetOf<Listener>()
    private var displayManager: DisplayManager? = null
    private var displayInfo: ExternalDisplayInfo? = null
    private var externalDisplays: List<ExternalDisplayInfo> = emptyList()
    private var selectedDisplayId: Int? = null

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
        listener.onDisplaysUpdated(externalDisplays, selectedDisplayId)
        listener.onDisplayChanged(displayInfo)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun getExternalDisplayInfo(): ExternalDisplayInfo? = displayInfo
    fun getExternalDisplays(): List<ExternalDisplayInfo> = externalDisplays
    fun getSelectedDisplayId(): Int? = selectedDisplayId

    fun setSelectedDisplayId(displayId: Int) {
        if (selectedDisplayId == displayId) return
        selectedDisplayId = displayId
        refreshDisplays()
    }

    fun stopSession() {
        SessionStore.clear()
        ControlAccessibilityService.requestDetachOverlay()
    }

    private fun refreshDisplays() {
        val displays = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.map { buildInfo(it) }
            .orEmpty()
        externalDisplays = displays

        val previousDisplayId = displayInfo?.displayId
        if (externalDisplays.isEmpty()) {
            displayInfo = null
            selectedDisplayId = null
        } else {
            if (selectedDisplayId == null ||
                externalDisplays.none { it.displayId == selectedDisplayId }
            ) {
                selectedDisplayId = externalDisplays.first().displayId
            }
            displayInfo = externalDisplays.first { it.displayId == selectedDisplayId }
        }
        val newInfo = displayInfo
        if (previousDisplayId != null && newInfo == null) {
            stopSession()
        }
        if (previousDisplayId != newInfo?.displayId) {
            ControlAccessibilityService.requestAttachToDisplay(newInfo)
        }

        listeners.forEach {
            it.onDisplaysUpdated(externalDisplays, selectedDisplayId)
            it.onDisplayChanged(displayInfo)
        }
    }

    @Suppress("DEPRECATION")
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
