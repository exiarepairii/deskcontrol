package com.deskcontrol

/**
 * Process-local state for offering to restore an app after an external display wakes or reconnects.
 *
 * A display id can survive a sleep/wake cycle and can also be reassigned after a physical
 * reconnect. A restore request is created for an observed ACTIVE -> SUSPENDED -> ACTIVE
 * transition on the same id, or for a process-local disconnect -> reconnect after a verified
 * projection. Changing the selected display without a disconnect and process restart discard the
 * candidate.
 *
 * This class contains no Android dependencies so the lifecycle and single-consumption rules can
 * be tested without a device.
 */
class ProjectedAppRestoreState {
    enum class DisplayState {
        NONE,
        SUSPENDED,
        ACTIVE
    }

    data class Candidate(
        val packageName: String,
        val className: String,
        val displayId: Int,
        val flowId: String
    )

    enum class Trigger {
        WAKE,
        RECONNECT
    }

    data class Request(
        val key: Long,
        val candidate: Candidate,
        val trigger: Trigger
    )

    private var lastDisplayState: DisplayState? = null
    private var trackedDisplayId: Int? = null
    private var suspendedFromActive = false
    private var disconnectedWithCandidate = false
    private var verifiedCandidate: Candidate? = null
    private var pendingRequest: Request? = null
    private var requestSequence = 0L

    /**
     * Records one normalized display snapshot.
     *
     * The returned request is always newly created. Repeated ACTIVE callbacks keep the same
     * pending request but return null, preventing callers from showing duplicate prompts.
     */
    fun onDisplaySnapshot(state: DisplayState, displayId: Int?): Request? {
        if (state == DisplayState.NONE || displayId == null) {
            if (lastDisplayState != null && lastDisplayState != DisplayState.NONE) {
                disconnectedWithCandidate = verifiedCandidate != null
            }
            trackedDisplayId = null
            suspendedFromActive = false
            pendingRequest = null
            lastDisplayState = DisplayState.NONE
            return null
        }

        val previousState = lastDisplayState
        val previousDisplayId = trackedDisplayId
        if (previousDisplayId != null && previousDisplayId != displayId) {
            verifiedCandidate = null
            pendingRequest = null
            suspendedFromActive = false
            disconnectedWithCandidate = false
        }

        return when (state) {
            DisplayState.NONE -> error("NONE is handled before display-specific states")

            DisplayState.SUSPENDED -> {
                if (previousState == DisplayState.ACTIVE && previousDisplayId == displayId) {
                    suspendedFromActive = true
                    // A second sleep supersedes any unconsumed request from an earlier wake.
                    pendingRequest = null
                } else if (
                    previousState != DisplayState.SUSPENDED || previousDisplayId != displayId
                ) {
                    suspendedFromActive = false
                    pendingRequest = null
                }
                lastDisplayState = DisplayState.SUSPENDED
                trackedDisplayId = displayId
                null
            }

            DisplayState.ACTIVE -> {
                val isSameDisplayWake =
                    previousState == DisplayState.SUSPENDED &&
                        previousDisplayId == displayId &&
                        suspendedFromActive
                val isReconnect =
                    disconnectedWithCandidate &&
                        (previousState == DisplayState.NONE ||
                            previousState == DisplayState.SUSPENDED)
                lastDisplayState = DisplayState.ACTIVE
                trackedDisplayId = displayId
                suspendedFromActive = false
                disconnectedWithCandidate = false

                if (!isSameDisplayWake && !isReconnect) {
                    null
                } else {
                    verifiedCandidate
                        ?.let { candidate ->
                            when {
                                isSameDisplayWake && candidate.displayId == displayId -> candidate
                                isReconnect -> candidate.copy(displayId = displayId).also {
                                    verifiedCandidate = it
                                }
                                else -> null
                            }
                        }
                        ?.let { candidate ->
                            Request(
                                key = nextRequestKey(),
                                candidate = candidate,
                                trigger = if (isSameDisplayWake) Trigger.WAKE else Trigger.RECONNECT
                            ).also { pendingRequest = it }
                        }
                }
            }
        }
    }

    /** Accepts a launch observation only while its target display is currently active. */
    fun recordVerified(candidate: Candidate): Boolean {
        if (lastDisplayState != DisplayState.ACTIVE || trackedDisplayId != candidate.displayId) {
            return false
        }
        verifiedCandidate = candidate
        pendingRequest = null
        suspendedFromActive = false
        disconnectedWithCandidate = false
        return true
    }

    /** A new explicit user launch supersedes both the old candidate and any pending prompt. */
    fun onUserLaunchRequested() {
        verifiedCandidate = null
        pendingRequest = null
        suspendedFromActive = false
        disconnectedWithCandidate = false
    }

    fun currentRequest(): Request? = pendingRequest

    fun hasVerifiedCandidate(): Boolean = verifiedCandidate != null

    /** Claims a request once while retaining its candidate for a future sleep/wake cycle. */
    fun consumeRequest(key: Long): Request? {
        val current = pendingRequest?.takeIf { it.key == key } ?: return null
        pendingRequest = null
        return current
    }

    /** Clears the candidate, request, and observed lifecycle. Request keys are never reused. */
    fun clear() {
        clearTrackedValues()
        lastDisplayState = null
    }

    private fun clearTrackedValues() {
        trackedDisplayId = null
        suspendedFromActive = false
        disconnectedWithCandidate = false
        verifiedCandidate = null
        pendingRequest = null
    }

    private fun nextRequestKey(): Long {
        requestSequence += 1L
        return requestSequence
    }
}
