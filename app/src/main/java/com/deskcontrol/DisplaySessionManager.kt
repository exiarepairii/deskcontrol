package com.deskcontrol

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.util.DisplayMetrics
import android.view.Display

object DisplaySessionManager {
    enum class ExternalDisplayState {
        NONE,
        SUSPENDED,
        ACTIVE
    }

    enum class SelectionNoticeType {
        EXCLUDED_UNAVAILABLE,
        DETECTED_UNAVAILABLE
    }

    data class SelectionNotice(
        val type: SelectionNoticeType,
        val selectedDisplayId: Int?,
        val excludedDisplayIds: List<Int>
    )

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
    private var appContext: Context? = null
    private var displayManager: DisplayManager? = null
    private var displayInfo: ExternalDisplayInfo? = null
    private var externalDisplays: List<ExternalDisplayInfo> = emptyList()
    private var selectedDisplayId: Int? = null
    private var selectedDisplayState = ExternalDisplayState.NONE
    private var listenerRegistered = false
    private var lastInventorySignature: String? = null
    private var lastSelectionSignature: String? = null
    private var lastSeenExternalDiagnostics: List<String> = emptyList()
    private var lastPolicyCandidateSignature: String? = null
    private var cachedPolicyProbes: Map<Int, DisplayDiagnostics.ActivityPolicyProbe> = emptyMap()
    private var manuallySelectedDisplayId: Int? = null
    private val selectionNoticeState = OneShotNoticeState<SelectionNotice>()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            DiagnosticsLog.add("DisplayListener: added id=$displayId registered=$listenerRegistered")
            refreshDisplays("added:$displayId")
        }

        override fun onDisplayRemoved(displayId: Int) {
            DiagnosticsLog.add("DisplayListener: removed id=$displayId registered=$listenerRegistered")
            refreshDisplays("removed:$displayId")
        }

        override fun onDisplayChanged(displayId: Int) {
            // Brightness changes on the built-in display can fire continuously. The inventory
            // signature below records only policy-relevant changes instead of flooding the log.
            refreshDisplays("changed:$displayId")
        }
    }

    fun init(context: Context) {
        if (displayManager != null) return
        appContext = context.applicationContext
        displayManager = appContext?.getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
        listenerRegistered = true
        refreshDisplays("init")
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
    fun getSelectedDisplayState(): ExternalDisplayState = selectedDisplayState
    fun getLastSeenExternalDiagnostics(): List<String> = lastSeenExternalDiagnostics.toList()

    fun consumeSelectionNotice(): SelectionNotice? {
        val notice = selectionNoticeState.consume() ?: return null
        DiagnosticsLog.add(
            "DisplayNotice: event=CONSUMED type=${notice.type.name} " +
                "selected=${notice.selectedDisplayId ?: "none"} " +
                "excluded=[${notice.excludedDisplayIds.joinToString()}]"
        )
        return notice
    }

    fun setSelectedDisplayId(displayId: Int) {
        if (externalDisplays.none { it.displayId == displayId }) {
            DiagnosticsLog.add(
                "DisplaySelectRequest: requested=$displayId accepted=false " +
                    "reason=not_selectable selectable=[${externalDisplays.joinToString { it.displayId.toString() }}]"
            )
            return
        }
        val previousSelectedId = selectedDisplayId
        manuallySelectedDisplayId = displayId
        DiagnosticsLog.add(
            "DisplaySelectRequest: requested=$displayId accepted=true " +
                "previous=${previousSelectedId ?: "none"} changed=${previousSelectedId != displayId}"
        )
        if (previousSelectedId == displayId) return
        selectedDisplayId = displayId
        refreshDisplays("selection:$displayId")
    }

    fun stopSession() {
        SessionStore.clear()
        ControlAccessibilityService.requestDetachOverlay()
    }

    private fun suspendSession() {
        ControlAccessibilityService.requestSuspendOverlay()
    }

    private fun refreshDisplays(trigger: String) {
        val dm = displayManager
        val allDisplays = dm?.getDisplays()?.toList().orEmpty()
        val presentationDisplays = dm
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()
        // Some OEMs expose a recording display as PRESENTATION while their real HDMI display is
        // only visible in the complete inventory. Compare every public non-default display and
        // keep presentation membership as a preference rather than a discovery gate.
        val rawDisplays = allDisplays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val presentationIds = presentationDisplays.map { it.displayId }.toSet()
        val policySnapshot = policyProbes(rawDisplays, trigger)
        val policyProbes = policySnapshot.probes
        val mediaRouteDisplayId = selectedMediaRouteDisplayId()
        val rawCandidates = rawDisplays.map { display ->
            val probe = policyProbes[display.displayId]
            ExternalDisplaySelector.Candidate(
                displayId = display.displayId,
                valid = display.isValid,
                stateOff = display.state == Display.STATE_OFF,
                stateOn = display.state == Display.STATE_ON,
                trusted = DisplayFlagFormatter.isTrusted(display.flags),
                standardActivityAllowed = probe?.standardActivityAllowed,
                allowEmbeddedActivityAllowed = probe?.allowEmbeddedActivityAllowed,
                mediaRouteMatch = display.displayId == mediaRouteDisplayId,
                presentation = display.displayId in presentationIds
            )
        }

        val previousInfo = displayInfo
        val previousSelectedId = selectedDisplayId
        val previousDisplayState = selectedDisplayState
        val decision = ExternalDisplaySelector.decide(
            candidates = rawCandidates,
            currentSelectedId = previousSelectedId,
            preferCurrentSelection = manuallySelectedDisplayId == previousSelectedId
        )
        val rawDisplaysById = rawDisplays.associateBy { it.displayId }
        externalDisplays = decision.selectableIds.mapNotNull { displayId ->
            rawDisplaysById[displayId]?.let(::buildInfo)
        }
        selectedDisplayId = decision.selectedId
        if (manuallySelectedDisplayId != selectedDisplayId) {
            manuallySelectedDisplayId = null
        }
        val selectedRawDisplay = selectedDisplayId?.let(rawDisplaysById::get)
        selectedDisplayState = when {
            selectedRawDisplay == null -> ExternalDisplayState.NONE
            selectedRawDisplay.isValid && selectedRawDisplay.state == Display.STATE_ON ->
                ExternalDisplayState.ACTIVE
            else -> ExternalDisplayState.SUSPENDED
        }
        displayInfo = selectedRawDisplay
            ?.takeIf {
                selectedDisplayState == ExternalDisplayState.ACTIVE
            }
            ?.let(::buildInfo)

        if (previousDisplayState != selectedDisplayState) {
            DiagnosticsLog.add(
                "DisplayLifecycle: trigger=$trigger previous=${previousDisplayState.name} " +
                    "current=${selectedDisplayState.name} " +
                    "selected=${selectedDisplayId ?: "none"}"
            )
        }

        val inventorySignature = buildString {
            append(allDisplays.joinToString("||", transform = ::inventorySignature))
            append("|presentation=")
            append(presentationDisplays.joinToString(",") { it.displayId.toString() })
        }
        val inventoryChanged = lastInventorySignature != inventorySignature
        if (inventoryChanged) {
            lastInventorySignature = inventorySignature
            DiagnosticsLog.add(
                "DisplayInventory: trigger=$trigger listenerRegistered=$listenerRegistered changed=true"
            )
            DiagnosticsLog.add(
                "DisplayAll: count=${allDisplays.size} ${formatDisplays(allDisplays)}"
            )
            DiagnosticsLog.add(
                "DisplayPresentation: count=${presentationDisplays.size} " +
                    formatDisplays(presentationDisplays)
            )
            allDisplays.forEach { display ->
                DiagnosticsLog.add(DisplayDiagnostics.detailLine(display))
                val cachedProbe = policyProbes[display.displayId]
                if (cachedProbe != null) {
                    DiagnosticsLog.add(DisplayDiagnostics.policyProbeLine(display, cachedProbe))
                } else {
                    appContext?.let { context ->
                        DiagnosticsLog.add(DisplayDiagnostics.policyProbeLine(context, display))
                    }
                }
            }
        }

        if (rawDisplays.isNotEmpty() && (inventoryChanged || policySnapshot.refreshed)) {
            val externalIds = rawDisplays.map { it.displayId }.toSet()
            lastSeenExternalDiagnostics = allDisplays
                .filter { it.displayId in externalIds }
                .flatMap { display ->
                    buildList {
                        add(DisplayDiagnostics.detailLine(display).replaceFirst(
                            "DisplayDetail:",
                            "LastSeenDisplayDetail:"
                        ))
                        policyProbes[display.displayId]?.let { probe ->
                            add(DisplayDiagnostics.policyProbeLine(display, probe).replaceFirst(
                                "DisplayPolicyProbe:",
                                "LastSeenDisplayPolicyProbe:"
                            ))
                        }
                    }
                }
        }

        val candidates = externalDisplays.joinToString { it.displayId.toString() }
        val rawCandidateSummary = rawCandidates.joinToString { candidate ->
            val rejection = decision.rejected[candidate.displayId]?.name ?: "none"
            "${candidate.displayId}{valid=${candidate.valid},off=${candidate.stateOff}," +
                "on=${candidate.stateOn}," +
                "trusted=${candidate.trusted},standard=${candidate.standardActivityAllowed}," +
                "embedded=${candidate.allowEmbeddedActivityAllowed}," +
                "route=${candidate.mediaRouteMatch},presentation=${candidate.presentation}," +
                "reject=$rejection}"
        }
        val selectionSignature =
            "raw=[$rawCandidateSummary] ids=[$candidates] selected=${selectedDisplayId ?: "none"} " +
                "state=${selectedDisplayState.name} source=all_non_default"
        updateSelectionNotice(rawCandidates, decision)
        if (lastSelectionSignature != selectionSignature) {
            lastSelectionSignature = selectionSignature
            DiagnosticsLog.add(
                "DisplayAutoSelect: trigger=$trigger previous=${previousSelectedId ?: "none"} " +
                    "selected=${selectedDisplayId ?: "none"} reason=${decision.reason.name} " +
                    "manual=${manuallySelectedDisplayId ?: "none"} " +
                    "route=${mediaRouteDisplayId ?: "none"} raw=[$rawCandidateSummary] " +
                    "selectable=[$candidates]"
            )
            DiagnosticsLog.add(
                if (externalDisplays.isEmpty()) {
                    "DisplaySelect: no external displays"
                } else {
                    "DisplaySelect: selected=$selectedDisplayId candidates=[$candidates]"
                }
            )
            DiagnosticsLog.add(
                "Displays: count=${externalDisplays.size} rawCount=${rawDisplays.size} " +
                    selectionSignature
            )
        }

        val newInfo = displayInfo
        when (selectedDisplayState) {
            ExternalDisplayState.NONE -> {
                if (previousDisplayState != ExternalDisplayState.NONE) {
                    stopSession()
                }
            }

            ExternalDisplayState.SUSPENDED -> {
                if (previousDisplayState == ExternalDisplayState.ACTIVE) {
                    suspendSession()
                }
            }

            ExternalDisplayState.ACTIVE -> {
                if (newInfo != null &&
                    (previousDisplayState != ExternalDisplayState.ACTIVE ||
                        previousInfo != newInfo)
                ) {
                    ControlAccessibilityService.requestAttachToDisplay(newInfo)
                }
            }
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

    private fun inventorySignature(display: Display): String {
        if (display.displayId != Display.DEFAULT_DISPLAY) {
            return DisplayDiagnostics.detailLine(display)
        }
        val info = buildInfo(display)
        // Adaptive refresh-rate and brightness callbacks on the phone screen are irrelevant to
        // external launch policy and can otherwise churn the persistent diagnostic ring.
        return "id=${display.displayId}|name=${display.name}|valid=${display.isValid}|" +
            "state=${display.state}|flags=${display.flags}|${info.width}x${info.height}|" +
            "dpi=${info.densityDpi}|rotation=${info.rotation}"
    }

    private data class PolicyProbeSnapshot(
        val probes: Map<Int, DisplayDiagnostics.ActivityPolicyProbe>,
        val refreshed: Boolean
    )

    private fun updateSelectionNotice(
        rawCandidates: List<ExternalDisplaySelector.Candidate>,
        decision: ExternalDisplaySelector.Decision
    ) {
        val excludedIds = decision.rejected.keys.sorted()
        val notice = when {
            rawCandidates.isNotEmpty() && decision.selectedId == null -> SelectionNotice(
                type = SelectionNoticeType.DETECTED_UNAVAILABLE,
                selectedDisplayId = null,
                excludedDisplayIds = excludedIds
            )

            decision.selectedId != null && excludedIds.isNotEmpty() -> SelectionNotice(
                type = SelectionNoticeType.EXCLUDED_UNAVAILABLE,
                selectedDisplayId = decision.selectedId,
                excludedDisplayIds = excludedIds
            )

            else -> null
        }
        val signature = notice?.let {
            "${it.type.name}:${it.excludedDisplayIds.joinToString()}"
        }
        val updateAction = selectionNoticeState.update(signature, notice)
        when (updateAction) {
            OneShotNoticeState.UpdateAction.UNCHANGED -> Unit
            OneShotNoticeState.UpdateAction.QUEUED,
            OneShotNoticeState.UpdateAction.SUPERSEDED -> {
                val event = if (updateAction == OneShotNoticeState.UpdateAction.SUPERSEDED) {
                    "SUPERSEDED"
                } else {
                    "QUEUED"
                }
                DiagnosticsLog.add(
                    "DisplayNotice: event=$event type=${notice?.type?.name ?: "none"} " +
                        "selected=${notice?.selectedDisplayId ?: "none"} " +
                        "excluded=[${notice?.excludedDisplayIds?.joinToString().orEmpty()}]"
                )
            }
            OneShotNoticeState.UpdateAction.CLEARED ->
                DiagnosticsLog.add("DisplayNotice: event=CLEARED")
        }
    }

    private fun policyProbes(
        displays: List<Display>,
        trigger: String
    ): PolicyProbeSnapshot {
        val signature = displays.joinToString("|") {
            "${it.displayId}:${it.isValid}:${it.state}:${it.flags}"
        }
        val externalChanged = trigger.startsWith("added:") ||
            trigger.startsWith("removed:") ||
            trigger.substringAfter("changed:", "").toIntOrNull()?.let { changedId ->
                changedId != Display.DEFAULT_DISPLAY && displays.any { it.displayId == changedId }
            } == true
        val shouldRefresh = signature != lastPolicyCandidateSignature || externalChanged
        if (shouldRefresh) {
            lastPolicyCandidateSignature = signature
            val context = appContext
            cachedPolicyProbes = if (context == null) {
                emptyMap()
            } else {
                displays.associate { display ->
                    display.displayId to DisplayDiagnostics.activityPolicyProbe(
                        context,
                        display.displayId
                    )
                }
            }
        }
        return PolicyProbeSnapshot(cachedPolicyProbes, shouldRefresh)
    }

    @Suppress("DEPRECATION")
    private fun selectedMediaRouteDisplayId(): Int? = runCatching {
        appContext
            ?.getSystemService(MediaRouter::class.java)
            ?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
            ?.presentationDisplay
            ?.displayId
    }.getOrNull()

    private fun formatDisplays(displays: List<Display>): String {
        if (displays.isEmpty()) return "[]"
        return displays.joinToString(prefix = "[", postfix = "]") {
            DisplayDiagnostics.compact(it)
        }
    }
}
