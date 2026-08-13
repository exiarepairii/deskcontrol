package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectedAppRestoreStateTest {
    @Test
    fun firstNoneOrActiveSnapshotDoesNotCreateRequest() {
        val state = ProjectedAppRestoreState()

        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.NONE, null))
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
        assertNull(state.currentRequest())
    }

    @Test
    fun sameDisplayActiveSuspendActiveCreatesOneRequest() {
        val state = activeStateWithCandidate(displayId = 30)

        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30))
        val request = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )

        assertEquals(candidate(displayId = 30), request.candidate)
        assertEquals(request, state.currentRequest())
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
        assertEquals(request, state.currentRequest())
    }

    @Test
    fun repeatedSuspendedCallbacksRemainArmedForOneWake() {
        val state = activeStateWithCandidate(displayId = 30)

        repeat(4) {
            assertNull(
                state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
            )
        }
        val request = state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)

        assertTrue(request != null)
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
    }

    @Test
    fun initialSuspendedSnapshotDoesNotArmARequest() {
        val state = ProjectedAppRestoreState()

        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30))
        assertFalse(state.recordVerified(candidate(displayId = 30)))
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
    }

    @Test
    fun physicalRemovalClearsCandidateAndWakeHistory() {
        val state = activeStateWithCandidate(displayId = 30)

        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.NONE, null)
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)

        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
        assertNull(state.currentRequest())
    }

    @Test
    fun changingDisplayIdClearsCandidateAndDoesNotLookLikeWake() {
        val state = activeStateWithCandidate(displayId = 30)

        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 31))
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 31)

        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 31))
    }

    @Test
    fun verifiedCandidateRequiresMatchingCurrentlyActiveDisplay() {
        val state = ProjectedAppRestoreState()

        assertFalse(state.recordVerified(candidate(displayId = 30)))
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        assertFalse(state.recordVerified(candidate(displayId = 31)))
        assertTrue(state.recordVerified(candidate(displayId = 30)))
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        assertFalse(state.recordVerified(candidate(displayId = 30, flowId = "late")))
    }

    @Test
    fun explicitUserLaunchClearsOldCandidateButAllowsNewVerification() {
        val state = activeStateWithCandidate(displayId = 30)

        state.onUserLaunchRequested()
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))

        val replacement = candidate(displayId = 30, packageName = "example.new", flowId = "new")
        assertTrue(state.recordVerified(replacement))
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val request = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )
        assertEquals(replacement, request.candidate)
    }

    @Test
    fun requestCanBindOnlyOneConnectedSessionGeneration() {
        val state = activeStateWithCandidate(displayId = 30)
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val request = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )

        val bound = requireNotNull(state.bindSession(request.key, generation = 22L))
        assertEquals(22L, bound.resumedSessionGeneration)
        assertSame(bound, state.bindSession(request.key, generation = 22L))
        assertNull(state.bindSession(request.key, generation = 23L))
        assertEquals(bound, state.currentRequest())
    }

    @Test
    fun consumingRequestIsSingleUseAndKeepsCandidateForNextSleep() {
        val state = activeStateWithCandidate(displayId = 30)
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val first = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )
        val claimed = requireNotNull(state.consumeRequest(first.key))

        assertEquals(first, claimed)
        assertNull(state.consumeRequest(first.key))
        assertNull(state.currentRequest())

        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val second = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )
        assertNotEquals(first.key, second.key)
        assertEquals(first.candidate, second.candidate)
    }

    @Test
    fun staleRequestKeyCannotBindOrConsumeNewRequest() {
        val state = activeStateWithCandidate(displayId = 30)
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val old = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )
        state.consumeRequest(old.key)
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val current = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )

        assertNull(state.bindSession(old.key, generation = 50L))
        assertNull(state.consumeRequest(old.key))
        assertEquals(current, state.currentRequest())
    }

    @Test
    fun clearResetsEverythingWithoutReusingRequestKeys() {
        val state = activeStateWithCandidate(displayId = 30)
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val old = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )

        state.clear()
        assertNull(state.currentRequest())
        assertNull(state.bindSession(old.key, generation = 7L))
        assertNull(state.consumeRequest(old.key))
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))

        assertTrue(state.recordVerified(candidate(displayId = 30, flowId = "after-clear")))
        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        val next = requireNotNull(
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30)
        )
        assertTrue(next.key > old.key)
    }

    @Test
    fun nullDisplayIdIsTreatedAsNoDisplayAndClearsState() {
        val state = activeStateWithCandidate(displayId = 30)

        state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.SUSPENDED, 30)
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, null))
        assertNull(state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, 30))
        assertNull(state.currentRequest())
    }

    private fun activeStateWithCandidate(displayId: Int): ProjectedAppRestoreState {
        return ProjectedAppRestoreState().also { state ->
            state.onDisplaySnapshot(ProjectedAppRestoreState.DisplayState.ACTIVE, displayId)
            assertTrue(state.recordVerified(candidate(displayId = displayId)))
        }
    }

    private fun candidate(
        displayId: Int,
        packageName: String = "example.video",
        flowId: String = "flow-1"
    ): ProjectedAppRestoreState.Candidate {
        return ProjectedAppRestoreState.Candidate(
            packageName = packageName,
            className = "$packageName.MainActivity",
            displayId = displayId,
            flowId = flowId,
            verifiedSessionGeneration = 11L
        )
    }
}
