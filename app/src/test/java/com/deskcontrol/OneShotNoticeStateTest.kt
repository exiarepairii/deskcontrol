package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneShotNoticeStateTest {
    @Test
    fun pendingNoticeIsDeliveredOnlyOnceAcrossDuplicateCallbacks() {
        val state = OneShotNoticeState<String>()

        assertEquals(
            OneShotNoticeState.UpdateAction.QUEUED,
            state.update("unavailable:563", "unavailable")
        )
        assertEquals(
            OneShotNoticeState.UpdateAction.UNCHANGED,
            state.update("unavailable:563", "unavailable")
        )
        assertEquals("unavailable", state.consume())
        assertNull(state.consume())
        assertEquals(
            OneShotNoticeState.UpdateAction.UNCHANGED,
            state.update("unavailable:563", "unavailable")
        )
        assertNull(state.consume())
    }

    @Test
    fun resolvedStateRearmsTheSameNotice() {
        val state = OneShotNoticeState<String>()

        state.update("unavailable:563", "unavailable")
        assertEquals("unavailable", state.consume())
        assertEquals(OneShotNoticeState.UpdateAction.CLEARED, state.update(null, null))
        assertEquals(
            OneShotNoticeState.UpdateAction.QUEUED,
            state.update("unavailable:563", "unavailable")
        )
        assertEquals("unavailable", state.consume())
    }

    @Test
    fun newerStateSupersedesUnconsumedNotice() {
        val state = OneShotNoticeState<String>()

        state.update("unavailable:563", "unavailable")
        assertEquals(
            OneShotNoticeState.UpdateAction.SUPERSEDED,
            state.update("excluded:563", "excluded")
        )
        assertEquals("excluded", state.consume())
        assertNull(state.consume())
    }

    @Test
    fun consumedUnavailableThenExcludedQueuesSecondNotice() {
        val state = OneShotNoticeState<String>()

        state.update("DETECTED_UNAVAILABLE:563", "unavailable")
        assertEquals("unavailable", state.consume())
        assertEquals(
            OneShotNoticeState.UpdateAction.QUEUED,
            state.update("EXCLUDED_UNAVAILABLE:563", "excluded")
        )
        assertEquals("excluded", state.consume())
    }

    @Test
    fun selectedDisplayDoesNotAffectExclusionSignature() {
        val state = OneShotNoticeState<String>()

        state.update("excluded:563", "using-display-15")
        assertEquals(
            OneShotNoticeState.UpdateAction.UNCHANGED,
            state.update("excluded:563", "using-display-16")
        )
        assertEquals("using-display-16", state.consume())
        assertNull(state.consume())
    }
}
