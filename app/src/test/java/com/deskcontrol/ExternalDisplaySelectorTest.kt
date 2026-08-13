package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalDisplaySelectorTest {
    @Test
    fun offUntrustedDeniedRecordingIsRejected() {
        val decision = ExternalDisplaySelector.decide(
            listOf(candidate(563, stateOff = true, allowed = false)),
            currentSelectedId = 563
        )

        assertNull(decision.selectedId)
        assertEquals(emptyList<Int>(), decision.selectableIds)
        assertEquals(
            ExternalDisplaySelector.RejectionReason.SUSPENDED_UNTRUSTED_POLICY_DENIED,
            decision.rejected[563]
        )
    }

    @Test
    fun offTrustedAllowedHdmiRemainsSelectable() {
        val decision = ExternalDisplaySelector.decide(
            listOf(candidate(15, stateOff = true, trusted = true, allowed = true)),
            currentSelectedId = 15
        )

        assertEquals(15, decision.selectedId)
        assertEquals(listOf(15), decision.selectableIds)
        assertEquals(
            ExternalDisplaySelector.SelectionReason.KEEP_CURRENT_DISPLAY,
            decision.reason
        )
        assertEquals(emptyMap<Int, ExternalDisplaySelector.RejectionReason>(), decision.rejected)
    }

    @Test
    fun dozingTrustedAllowedHdmiRemainsSelectable() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(
                    15,
                    stateOff = false,
                    stateOn = false,
                    trusted = true,
                    allowed = true
                )
            ),
            currentSelectedId = 15
        )

        assertEquals(15, decision.selectedId)
        assertEquals(listOf(15), decision.selectableIds)
        assertEquals(emptyMap<Int, ExternalDisplaySelector.RejectionReason>(), decision.rejected)
    }

    @Test
    fun trustedHdmiReplacesUnavailableRecordingDisplay() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(563, stateOff = true, allowed = false),
                candidate(15, trusted = true, allowed = true, mediaRouteMatch = true)
            ),
            currentSelectedId = 563
        )

        assertEquals(15, decision.selectedId)
        assertEquals(listOf(15), decision.selectableIds)
        assertEquals(
            ExternalDisplaySelector.SelectionReason.CURRENT_DISPLAY_UNAVAILABLE,
            decision.reason
        )
    }

    @Test
    fun deniedDisplayIsExcludedWhenAllowedDisplayExists() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(59, allowed = false),
                candidate(15, trusted = true, allowed = true)
            ),
            currentSelectedId = 59
        )

        assertEquals(15, decision.selectedId)
        assertEquals(listOf(15), decision.selectableIds)
        assertEquals(
            ExternalDisplaySelector.RejectionReason.POLICY_DENIED_WHEN_ALLOWED_DISPLAY_EXISTS,
            decision.rejected[59]
        )
    }

    @Test
    fun loneLegacyMiPlayRemainsAsFallback() {
        val decision = ExternalDisplaySelector.decide(
            listOf(candidate(59, trusted = false, allowed = false)),
            currentSelectedId = null
        )

        assertEquals(59, decision.selectedId)
        assertEquals(listOf(59), decision.selectableIds)
        assertEquals(emptyMap<Int, ExternalDisplaySelector.RejectionReason>(), decision.rejected)
    }

    @Test
    fun removingTrustedDisplayFallsBackToActiveLegacyDisplay() {
        val decision = ExternalDisplaySelector.decide(
            listOf(candidate(59, trusted = false, allowed = false)),
            currentSelectedId = 15
        )

        assertEquals(59, decision.selectedId)
        assertEquals(
            ExternalDisplaySelector.SelectionReason.CURRENT_DISPLAY_REMOVED,
            decision.reason
        )
    }

    @Test
    fun currentAllowedDisplayStaysSelectedWhenPeerIsAdded() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(15, trusted = true, allowed = true),
                candidate(16, trusted = true, allowed = true, mediaRouteMatch = true)
            ),
            currentSelectedId = 15
        )

        assertEquals(15, decision.selectedId)
        assertEquals(
            ExternalDisplaySelector.SelectionReason.KEEP_CURRENT_DISPLAY,
            decision.reason
        )
    }

    @Test
    fun unavailableProbeDoesNotBecomeDeniedFallback() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(59, allowed = false),
                candidate(60, allowed = null)
            ),
            currentSelectedId = 59
        )

        assertEquals(60, decision.selectedId)
        assertEquals(listOf(60, 59), decision.selectableIds)
    }

    @Test
    fun unavailableProbeIsNotReportedAsDeniedWhenAllowedDisplayExists() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(15, trusted = true, allowed = true),
                candidate(60, allowed = null)
            ),
            currentSelectedId = 60
        )

        assertEquals(15, decision.selectedId)
        assertEquals(listOf(15, 60), decision.selectableIds)
        assertEquals(null, decision.rejected[60])
    }

    @Test
    fun manualFallbackSelectionDoesNotBounceBack() {
        val decision = ExternalDisplaySelector.decide(
            candidates = listOf(
                candidate(59, allowed = false),
                candidate(60, allowed = null)
            ),
            currentSelectedId = 59,
            preferCurrentSelection = true
        )

        assertEquals(59, decision.selectedId)
        assertEquals(listOf(60, 59), decision.selectableIds)
    }

    @Test
    fun activeAllowedPeerReplacesAutomaticallySelectedSuspendedAllowedDisplay() {
        val decision = ExternalDisplaySelector.decide(
            candidates = listOf(
                candidate(
                    15,
                    stateOff = true,
                    stateOn = false,
                    trusted = true,
                    allowed = true
                ),
                candidate(16, stateOn = true, trusted = true, allowed = true)
            ),
            currentSelectedId = 15
        )

        assertEquals(16, decision.selectedId)
    }

    @Test
    fun trustedDisplayRetainsSelectionAcrossSuspendedToActiveTransition() {
        val suspended = ExternalDisplaySelector.decide(
            candidates = listOf(
                candidate(15, stateOff = true, trusted = true, allowed = true)
            ),
            currentSelectedId = 15
        )
        val active = ExternalDisplaySelector.decide(
            candidates = listOf(
                candidate(15, stateOn = true, trusted = true, allowed = true)
            ),
            currentSelectedId = suspended.selectedId
        )

        assertEquals(15, suspended.selectedId)
        assertEquals(15, active.selectedId)
        assertEquals(
            ExternalDisplaySelector.SelectionReason.KEEP_CURRENT_DISPLAY,
            active.reason
        )
    }

    @Test
    fun invalidDisplayIsAlwaysRejected() {
        val decision = ExternalDisplaySelector.decide(
            listOf(
                candidate(15, valid = false, trusted = true, allowed = true),
                candidate(59, allowed = false)
            ),
            currentSelectedId = 15
        )

        assertEquals(59, decision.selectedId)
        assertEquals(
            ExternalDisplaySelector.RejectionReason.INVALID,
            decision.rejected[15]
        )
    }

    @Test
    fun initialSelectionPrefersOnAllowedDisplayOverInactiveTrustedPresentation() {
        val decision = ExternalDisplaySelector.decide(
            candidates = listOf(
                candidate(
                    15,
                    stateOn = false,
                    trusted = true,
                    allowed = true,
                    presentation = true
                ),
                candidate(
                    16,
                    stateOn = true,
                    trusted = false,
                    allowed = true,
                    presentation = false
                )
            ),
            currentSelectedId = null
        )

        assertEquals(16, decision.selectedId)
    }

    @Test
    fun manualDeniedFallbackCannotOverrideNewAllowedDisplay() {
        val decision = ExternalDisplaySelector.decide(
            candidates = listOf(
                candidate(59, allowed = false, presentation = true),
                candidate(15, trusted = true, allowed = true, presentation = false)
            ),
            currentSelectedId = 59,
            preferCurrentSelection = true
        )

        assertEquals(15, decision.selectedId)
        assertEquals(listOf(15), decision.selectableIds)
    }

    private fun candidate(
        displayId: Int,
        valid: Boolean = true,
        stateOff: Boolean = false,
        stateOn: Boolean = !stateOff,
        trusted: Boolean = false,
        allowed: Boolean? = null,
        embeddedAllowed: Boolean? = allowed,
        mediaRouteMatch: Boolean = false,
        presentation: Boolean = true
    ) = ExternalDisplaySelector.Candidate(
        displayId = displayId,
        valid = valid,
        stateOff = stateOff,
        stateOn = stateOn,
        trusted = trusted,
        standardActivityAllowed = allowed,
        allowEmbeddedActivityAllowed = embeddedAllowed,
        mediaRouteMatch = mediaRouteMatch,
        presentation = presentation
    )
}
