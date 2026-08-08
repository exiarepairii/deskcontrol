package com.deskcontrol

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGestureStateTest {

    @Test
    fun sharedVolumeControllerRecognizesBothVolumeKeysOnly() {
        assertTrue(isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(isVolumeKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun sharedVolumeShortcutRequiresTheMoreConservativeHoldDuration() {
        assertEquals(660L, CONTROL_SURFACE_VOLUME_HOLD_MS)
        assertFalse(shouldTriggerVolumeHold(659L))
        assertTrue(shouldTriggerVolumeHold(660L))
    }

    @Test
    fun circularHoldHudIgnoresQuickVolumeTaps() {
        assertEquals(120L, CONTROL_SURFACE_HOLD_HUD_REVEAL_MS)
        assertFalse(shouldRevealVolumeHoldHud(119L))
        assertTrue(shouldRevealVolumeHoldHud(120L))
    }

    @Test
    fun blackoutTutorialActionMatchesTheResultingTransition() {
        assertEquals(
            ControlTutorialAction.BLACKOUT_LOCK,
            blackoutTutorialAction(wasBlackoutVisible = false)
        )
        assertEquals(
            ControlTutorialAction.BLACKOUT_UNLOCK,
            blackoutTutorialAction(wasBlackoutVisible = true)
        )
    }

    @Test
    fun movementBeforeStartMustCrossSlop() {
        assertFalse(
            shouldForwardDirectGestureMove(
                movementStarted = false,
                dxFromDown = 3f,
                dyFromDown = 4f,
                touchSlopPx = 8f
            )
        )
        assertTrue(
            shouldForwardDirectGestureMove(
                movementStarted = false,
                dxFromDown = 0f,
                dyFromDown = -9f,
                touchSlopPx = 8f
            )
        )
    }

    @Test
    fun activeGestureKeepsForwardingWhenItReturnsToDownPoint() {
        assertTrue(
            shouldForwardDirectGestureMove(
                movementStarted = true,
                dxFromDown = 0f,
                dyFromDown = 0f,
                touchSlopPx = 8f
            )
        )
    }

    @Test
    fun activeGestureKeepsForwardingAfterDirectionReversal() {
        assertTrue(
            shouldForwardDirectGestureMove(
                movementStarted = true,
                dxFromDown = 0f,
                dyFromDown = 4f,
                touchSlopPx = 8f
            )
        )
        assertTrue(
            shouldForwardDirectGestureMove(
                movementStarted = true,
                dxFromDown = -4f,
                dyFromDown = 0f,
                touchSlopPx = 8f
            )
        )
    }

    @Test
    fun idleCallbacksAreOneShotAndMayQueueAnotherGeneration() {
        val callbacks = ContinuousGestureIdleCallbacks()
        val deliveries = mutableListOf<Int>()

        callbacks.add {
            deliveries += 1
            callbacks.add { deliveries += 2 }
        }

        callbacks.dispatch()
        assertEquals(listOf(1), deliveries)
        assertFalse(callbacks.isEmpty)

        callbacks.dispatch()
        assertEquals(listOf(1, 2), deliveries)
        assertTrue(callbacks.isEmpty)
    }

    @Test
    fun activeDirectGestureAcceptsCancellationForCurrentTouchGeneration() {
        assertTrue(
            shouldRecoverDirectGestureCancellation(
                callbackGeneration = 7L,
                currentGeneration = 7L,
                isTouchActive = true,
                isDirectGestureState = true
            )
        )
    }

    @Test
    fun staleCancellationCannotRecoverAReplacementTouch() {
        assertFalse(
            shouldRecoverDirectGestureCancellation(
                callbackGeneration = 7L,
                currentGeneration = 8L,
                isTouchActive = true,
                isDirectGestureState = true
            )
        )
    }

    @Test
    fun cancellationAfterFingerUpCannotRestartGesture() {
        assertFalse(
            shouldRecoverDirectGestureCancellation(
                callbackGeneration = 7L,
                currentGeneration = 7L,
                isTouchActive = false,
                isDirectGestureState = true
            )
        )
    }

    @Test
    fun cancellationOutsideDirectGestureStateIsIgnored() {
        assertFalse(
            shouldRecoverDirectGestureCancellation(
                callbackGeneration = 7L,
                currentGeneration = 7L,
                isTouchActive = true,
                isDirectGestureState = false
            )
        )
    }

    @Test
    fun stationaryActiveGestureSchedulesKeepAlive() {
        assertTrue(
            shouldScheduleContinuousGestureKeepAlive(
                callbackGeneration = 4L,
                currentGeneration = 4L,
                hasActiveStroke = true,
                dispatchInFlight = false,
                endRequested = false,
                hasPendingPoint = false
            )
        )
    }

    @Test
    fun keepAliveIsSuppressedByMovementEndOrStaleCallback() {
        assertFalse(
            shouldScheduleContinuousGestureKeepAlive(
                callbackGeneration = 3L,
                currentGeneration = 4L,
                hasActiveStroke = true,
                dispatchInFlight = false,
                endRequested = false,
                hasPendingPoint = false
            )
        )
        assertFalse(
            shouldScheduleContinuousGestureKeepAlive(
                callbackGeneration = 4L,
                currentGeneration = 4L,
                hasActiveStroke = true,
                dispatchInFlight = false,
                endRequested = false,
                hasPendingPoint = true
            )
        )
        assertFalse(
            shouldScheduleContinuousGestureKeepAlive(
                callbackGeneration = 4L,
                currentGeneration = 4L,
                hasActiveStroke = true,
                dispatchInFlight = false,
                endRequested = true,
                hasPendingPoint = false
            )
        )
    }
}
