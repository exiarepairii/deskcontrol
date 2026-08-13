package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayGestureDispatchTrackerTest {
    @Test
    fun staleCallbackCannotMutateNewSessionCount() {
        val tracker = DisplayGestureDispatchTracker()

        val oldToken = tracker.begin()
        tracker.invalidate()
        val newToken = tracker.begin()

        assertFalse(tracker.finish(oldToken))
        assertEquals(1, tracker.inFlightCount)
        assertTrue(tracker.finish(newToken))
        assertEquals(0, tracker.inFlightCount)
    }

    @Test
    fun multipleCallbacksDrainOnlyTheirCurrentGeneration() {
        val tracker = DisplayGestureDispatchTracker()

        val first = tracker.begin()
        val second = tracker.begin()

        assertEquals(first.generation, second.generation)
        assertFalse(first.dispatchId == second.dispatchId)
        assertTrue(tracker.finish(first))
        assertEquals(1, tracker.inFlightCount)
        assertTrue(tracker.finish(second))
        assertEquals(0, tracker.inFlightCount)
    }

    @Test
    fun duplicateCallbackCannotDrainAnotherDispatch() {
        val tracker = DisplayGestureDispatchTracker()

        val first = tracker.begin()
        tracker.begin()

        assertTrue(tracker.finish(first))
        assertFalse(tracker.finish(first))
        assertEquals(1, tracker.inFlightCount)
    }
}
