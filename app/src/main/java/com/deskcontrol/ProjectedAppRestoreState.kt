package com.deskcontrol

/**
 * Process-local state for offering to restore an app after an external display wakes.
 *
 * A display id can survive a sleep/wake cycle, so a restore request is created only for an
 * observed ACTIVE -> SUSPENDED -> ACTIVE transition on that same id. Physical removal, changing
 * the selected display, and process restart intentionally discard the candidate.
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
        val flowId: String,
        val verifiedSessionGeneration: Long
    )

    data class Request(
        val key: Long,
        val candidate: Candidate,
        val resumedSessionGeneration: Long? = null
    )

    private var lastDisplayState: DisplayState? = null
    private var trackedDisplayId: Int? = null
    private var suspendedFromActive = false
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
            clearTrackedValues()
            lastDisplayState = DisplayState.NONE
            return null
        }

        val previousState = lastDisplayState
        val previousDisplayId = trackedDisplayId
        if (previousDisplayId != null && previousDisplayId != displayId) {
            verifiedCandidate = null
            pendingRequest = null
            suspendedFromActive = false
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
                lastDisplayState = DisplayState.ACTIVE
                trackedDisplayId = displayId
                suspendedFromActive = false

                if (!isSameDisplayWake) {
                    null
                } else {
                    verifiedCandidate
                        ?.takeIf { it.displayId == displayId }
                        ?.let { candidate ->
                            Request(
                                key = nextRequestKey(),
                                candidate = candidate
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
        return true
    }

    /** A new explicit user launch supersedes both the old candidate and any pending prompt. */
    fun onUserLaunchRequested() {
        verifiedCandidate = null
        pendingRequest = null
        suspendedFromActive = false
    }

    fun currentRequest(): Request? = pendingRequest

    /**
     * Binds a wake request to the newly connected accessibility-session generation.
     *
     * Binding the same generation is idempotent. A different generation cannot replace an
     * existing binding; the owner must let the display lifecycle create a new request instead.
     */
    fun bindSession(key: Long, generation: Long): Request? {
        val current = pendingRequest?.takeIf { it.key == key } ?: return null
        val boundGeneration = current.resumedSessionGeneration
        if (boundGeneration != null && boundGeneration != generation) return null
        if (boundGeneration == generation) return current
        return current.copy(resumedSessionGeneration = generation).also { pendingRequest = it }
    }

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
        verifiedCandidate = null
        pendingRequest = null
    }

    private fun nextRequestKey(): Long {
        requestSequence += 1L
        return requestSequence
    }
}
