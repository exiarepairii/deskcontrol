package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDisplaySessionLifecycleTest {
    @Test
    fun failedInitialAttachCanRetryAndConnect() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 3)

        lifecycle.begin("display-1")
        val firstAttempt = requireNotNull(lifecycle.beginAttempt())
        assertEquals("display-1", firstAttempt.target)
        assertTrue(lifecycle.markAttemptFailed(firstAttempt))
        assertTrue(lifecycle.canRetry())

        val secondAttempt = requireNotNull(lifecycle.beginAttempt())
        assertEquals("display-1", secondAttempt.target)
        assertTrue(lifecycle.markConnected(secondAttempt))

        assertEquals(ExternalDisplaySessionLifecycle.State.CONNECTED, lifecycle.state)
        assertFalse(lifecycle.canRetry())
        assertEquals(2, lifecycle.attemptCount)
    }

    @Test
    fun disconnectCancelsTargetAndRejectsStaleCompletion() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 3)

        lifecycle.begin("display-1")
        val staleAttempt = requireNotNull(lifecycle.beginAttempt())
        lifecycle.disconnect()

        assertFalse(lifecycle.markConnected(staleAttempt))
        assertNull(lifecycle.beginAttempt())
        assertNull(lifecycle.target)
        assertEquals(ExternalDisplaySessionLifecycle.State.DISCONNECTED, lifecycle.state)
    }

    @Test
    fun changingTargetRejectsOldDisplayResult() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 3)

        lifecycle.begin("display-1")
        val oldAttempt = requireNotNull(lifecycle.beginAttempt())
        lifecycle.begin("display-2")

        assertFalse(lifecycle.markConnected(oldAttempt))
        val newAttempt = requireNotNull(lifecycle.beginAttempt())
        assertEquals("display-2", newAttempt.target)
        assertTrue(lifecycle.markConnected(newAttempt))
    }

    @Test
    fun beginningSameTargetAgainSupersedesPreviousGeneration() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 3)

        lifecycle.begin("display-30")
        val oldAttempt = requireNotNull(lifecycle.beginAttempt())
        val oldGeneration = oldAttempt.generation

        val newGeneration = lifecycle.begin("display-30")
        assertTrue(newGeneration > oldGeneration)
        assertFalse(lifecycle.markConnected(oldAttempt))

        val newAttempt = requireNotNull(lifecycle.beginAttempt())
        assertEquals(newGeneration, newAttempt.generation)
        assertTrue(lifecycle.markConnected(newAttempt))
    }

    @Test
    fun retriesStopAtConfiguredLimit() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 2)

        lifecycle.begin("display-1")
        repeat(2) {
            val attempt = requireNotNull(lifecycle.beginAttempt())
            assertEquals("display-1", attempt.target)
            assertTrue(lifecycle.markAttemptFailed(attempt))
        }

        assertFalse(lifecycle.canRetry())
        assertNull(lifecycle.beginAttempt())
        assertEquals(ExternalDisplaySessionLifecycle.State.EXHAUSTED, lifecycle.state)
    }

    @Test
    fun suspendedDisplayReconnectsWithSameTargetInNewGeneration() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 3)

        val firstGeneration = lifecycle.begin("display-30")
        val oldAttempt = requireNotNull(lifecycle.beginAttempt())
        assertTrue(lifecycle.markConnected(oldAttempt))

        // Real device sequence: ON -> OFF -> DOZE -> DOZE_SUSPEND -> DOZE -> OFF. The owner
        // normalizes every inactive callback to suspend(), which must request teardown only once.
        val teardownRequests = listOf("OFF", "DOZE", "DOZE_SUSPEND", "DOZE", "OFF")
            .map { lifecycle.suspend() }
        assertEquals(listOf(true, false, false, false, false), teardownRequests)
        val suspendedGeneration = lifecycle.generation
        assertEquals(ExternalDisplaySessionLifecycle.State.SUSPENDED, lifecycle.state)
        assertEquals("display-30", lifecycle.target)
        assertEquals(suspendedGeneration, lifecycle.generation)
        assertFalse(lifecycle.markConnected(oldAttempt))
        assertFalse(lifecycle.canRetry())
        assertNull(lifecycle.beginAttempt())

        // The display id remains 30 when it returns to ON, but this is a wholly new session.
        val reconnectedGeneration = lifecycle.begin("display-30")
        assertTrue(reconnectedGeneration > suspendedGeneration)
        assertTrue(reconnectedGeneration > firstGeneration)
        val reconnectAttempt = requireNotNull(lifecycle.beginAttempt())
        assertEquals(reconnectedGeneration, reconnectAttempt.generation)
        assertFalse(lifecycle.markAttemptFailed(oldAttempt))
        assertTrue(lifecycle.markConnected(reconnectAttempt))
        assertEquals(ExternalDisplaySessionLifecycle.State.CONNECTED, lifecycle.state)
    }

    @Test
    fun suspendInvalidatesPendingRetryAndDisconnectInvalidatesReconnectTarget() {
        val lifecycle = ExternalDisplaySessionLifecycle<String>(maxAttempts = 3)

        lifecycle.begin("display-30")
        val failedAttempt = requireNotNull(lifecycle.beginAttempt())
        assertTrue(lifecycle.markAttemptFailed(failedAttempt))
        assertTrue(lifecycle.canRetry())

        assertTrue(lifecycle.suspend())
        assertFalse(lifecycle.canRetry())
        assertNull(lifecycle.beginAttempt())
        assertFalse(lifecycle.markConnected(failedAttempt))

        assertTrue(lifecycle.disconnect())
        assertNull(lifecycle.target)
        assertEquals(ExternalDisplaySessionLifecycle.State.DISCONNECTED, lifecycle.state)
        assertFalse(lifecycle.disconnect())
    }
}
