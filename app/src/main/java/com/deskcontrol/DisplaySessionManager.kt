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
    private var listenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            DiagnosticsLog.add("DisplayListener: added id=$displayId registered=$listenerRegistered")
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            DiagnosticsLog.add("DisplayListener: removed id=$displayId registered=$listenerRegistered")
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            DiagnosticsLog.add("DisplayListener: changed id=$displayId registered=$listenerRegistered")
            refreshDisplays()
        }
    }

    fun init(context: Context) {
        if (displayManager != null) return
        displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
        listenerRegistered = true
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
        val dm = displayManager
        val allDisplays = dm?.getDisplays()?.toList().orEmpty()
        DiagnosticsLog.add("DisplayAll: count=${allDisplays.size} ${formatDisplays(allDisplays)}")
        val presentationDisplays = dm
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()
        DiagnosticsLog.add(
            "DisplayPresentation: count=${presentationDisplays.size} " +
                formatDisplays(presentationDisplays)
        )
        val usingFallback = presentationDisplays.isEmpty()
        val displays = if (!usingFallback) {
            presentationDisplays
        } else {
            // Fallback for OEMs that don't classify external displays as presentations.
            dm?.getDisplays()
                ?.toList()
                ?.filter { it.displayId != Display.DEFAULT_DISPLAY }
                .orEmpty()
        }.map { buildInfo(it) }
        externalDisplays = displays

        val previousDisplayId = displayInfo?.displayId
        if (externalDisplays.isEmpty()) {
            DiagnosticsLog.add("DisplaySelect: no external displays")
            displayInfo = null
            selectedDisplayId = null
        } else {
            val candidates = externalDisplays.joinToString { it.displayId.toString() }
            if (selectedDisplayId == null ||
                externalDisplays.none { it.displayId == selectedDisplayId }
            ) {
                DiagnosticsLog.add(
                    "DisplaySelect: choose first (candidates=[$candidates])"
                )
                selectedDisplayId = externalDisplays.first().displayId
            } else {
                DiagnosticsLog.add(
                    "DisplaySelect: keep selected=$selectedDisplayId (candidates=[$candidates])"
                )
            }
            displayInfo = externalDisplays.first { it.displayId == selectedDisplayId }
        }
        val newInfo = displayInfo
        if (previousDisplayId != null && newInfo == null) {
            stopSession()
        }
        if (previousDisplayId != newInfo?.displayId ||
            (newInfo != null && ControlAccessibilityService.current()?.hasExternalDisplaySession() == false)
        ) {
            ControlAccessibilityService.requestAttachToDisplay(newInfo)
        }
        val ids = displays.joinToString { it.displayId.toString() }
        DiagnosticsLog.add(
            "Displays: count=${displays.size} ids=[$ids] selected=${selectedDisplayId ?: "none"} " +
                "source=${if (usingFallback) "fallback" else "presentation"}"
        )

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

    private fun formatDisplays(displays: List<Display>): String {
        if (displays.isEmpty()) return "[]"
        return displays.joinToString(prefix = "[", postfix = "]") { display ->
            val flags = formatDisplayFlags(display.flags)
            "id=${display.displayId} name=${display.name} valid=${display.isValid} " +
                "state=${display.state} flags=$flags"
        }
    }

    private fun formatDisplayFlags(flags: Int): String {
        val labels = mutableListOf<String>()
        if (flags and Display.FLAG_PRESENTATION != 0) labels.add("PRESENTATION")
        if (flags and Display.FLAG_PRIVATE != 0) labels.add("PRIVATE")
        if (flags and Display.FLAG_SECURE != 0) labels.add("SECURE")
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) {
            labels.add("PROTECTED")
        }
        if (flags and Display.FLAG_ROUND != 0) labels.add("ROUND")
        val labelText = if (labels.isEmpty()) "none" else labels.joinToString("|")
        return "0x${flags.toString(16)}($labelText)"
    }
}
