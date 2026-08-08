package com.deskcontrol

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.view.KeyEvent

/** Shared Volume Up/Down handling for Touchpad and Motion Mouse control surfaces. */
class ControlSurfaceVolumeKeyController(
    context: Context,
    private val handler: Handler,
    private val logName: String,
    private val isBlackoutVisible: () -> Boolean,
    private val onInteraction: () -> Unit,
    private val onCalibrationRequested: () -> CalibrationResult,
    private val onBlackoutToggleRequested: (wasBlackoutVisible: Boolean) -> Unit
) {
    data class CalibrationResult(
        val success: Boolean,
        val failureReason: String? = null
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var volumeDownHeld = false
    private var volumeDownLongPressTriggered = false
    private var volumeUpHeld = false
    private var volumeUpLongPressTriggered = false
    private val volumeDownLongPressRunnable = Runnable {
        triggerVolumeDownAction("timeout")
    }
    private val volumeUpLongPressRunnable = Runnable {
        triggerVolumeUpAction("timeout")
    }
    private val volumeDownHudRunnable = Runnable {
        if (volumeDownHeld && !volumeDownLongPressTriggered) {
            ControlAccessibilityService.beginExternalHoldHud(
                ExternalControlHudView.HoldAction.CALIBRATE,
                HOLD_DURATION_MS,
                HOLD_HUD_REVEAL_MS
            )
        }
    }
    private val volumeUpHudRunnable = Runnable {
        if (volumeUpHeld && !volumeUpLongPressTriggered) {
            ControlAccessibilityService.beginExternalHoldHud(
                currentVolumeUpHoldAction(),
                HOLD_DURATION_MS,
                HOLD_HUD_REVEAL_MS
            )
        }
    }

    fun handle(event: KeyEvent): Boolean {
        if (!isVolumeKey(event.keyCode)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(event)
            KeyEvent.ACTION_UP -> handleKeyUp(event)
        }
        return true
    }

    fun cancel() {
        volumeDownHeld = false
        volumeDownLongPressTriggered = false
        volumeUpHeld = false
        volumeUpLongPressTriggered = false
        handler.removeCallbacks(volumeDownLongPressRunnable)
        handler.removeCallbacks(volumeUpLongPressRunnable)
        handler.removeCallbacks(volumeDownHudRunnable)
        handler.removeCallbacks(volumeUpHudRunnable)
        ControlAccessibilityService.cancelExternalHoldHud()
    }

    private fun handleKeyDown(event: KeyEvent) {
        val heldDurationMs = (event.eventTime - event.downTime).coerceAtLeast(0L)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!volumeDownHeld) beginVolumeDownHold(event, heldDurationMs)
            if (!volumeDownLongPressTriggered && shouldTriggerVolumeHold(heldDurationMs)) {
                triggerVolumeDownAction("key_repeat")
            }
        } else {
            if (!volumeUpHeld) beginVolumeUpHold(event, heldDurationMs)
            if (!volumeUpLongPressTriggered && shouldTriggerVolumeHold(heldDurationMs)) {
                triggerVolumeUpAction("key_repeat")
            }
        }
    }

    private fun handleKeyUp(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val wasHeld = volumeDownHeld
            volumeDownHeld = false
            handler.removeCallbacks(volumeDownLongPressRunnable)
            handler.removeCallbacks(volumeDownHudRunnable)
            if (wasHeld && !volumeDownLongPressTriggered) {
                ControlAccessibilityService.cancelExternalHoldHud()
                if (!event.isCanceled) adjustMediaVolume(AudioManager.ADJUST_LOWER)
            }
            volumeDownLongPressTriggered = false
        } else {
            val wasHeld = volumeUpHeld
            volumeUpHeld = false
            handler.removeCallbacks(volumeUpLongPressRunnable)
            handler.removeCallbacks(volumeUpHudRunnable)
            if (wasHeld && !volumeUpLongPressTriggered) {
                ControlAccessibilityService.cancelExternalHoldHud()
                if (!event.isCanceled) adjustMediaVolume(AudioManager.ADJUST_RAISE)
            }
            volumeUpLongPressTriggered = false
        }
    }

    private fun beginVolumeDownHold(event: KeyEvent, heldDurationMs: Long) {
        volumeDownHeld = true
        volumeDownLongPressTriggered = false
        handler.removeCallbacks(volumeDownLongPressRunnable)
        val remainingMs = (HOLD_DURATION_MS - heldDurationMs).coerceAtLeast(0L)
        DiagnosticsLog.add(
            "$logName: volume-down hold start repeat=${event.repeatCount} " +
                "heldMs=$heldDurationMs remainingMs=$remainingMs"
        )
        scheduleVolumeDownHud(heldDurationMs)
        if (remainingMs == 0L) {
            triggerVolumeDownAction("initial_long_press")
        } else {
            handler.postDelayed(volumeDownLongPressRunnable, remainingMs)
        }
    }

    private fun beginVolumeUpHold(event: KeyEvent, heldDurationMs: Long) {
        volumeUpHeld = true
        volumeUpLongPressTriggered = false
        handler.removeCallbacks(volumeUpLongPressRunnable)
        val remainingMs = (HOLD_DURATION_MS - heldDurationMs).coerceAtLeast(0L)
        val action = currentVolumeUpHoldAction()
        DiagnosticsLog.add(
            "$logName: volume-up hold start repeat=${event.repeatCount} " +
                "heldMs=$heldDurationMs remainingMs=$remainingMs action=$action"
        )
        scheduleVolumeUpHud(heldDurationMs)
        if (remainingMs == 0L) {
            triggerVolumeUpAction("initial_long_press")
        } else {
            handler.postDelayed(volumeUpLongPressRunnable, remainingMs)
        }
    }

    private fun triggerVolumeDownAction(source: String) {
        if (!volumeDownHeld || volumeDownLongPressTriggered) return
        volumeDownLongPressTriggered = true
        handler.removeCallbacks(volumeDownLongPressRunnable)
        handler.removeCallbacks(volumeDownHudRunnable)
        onInteraction()
        val result = onCalibrationRequested()
        ControlAccessibilityService.completeExternalHoldHud(result.success)
        if (result.success) {
            ControlAccessibilityService.notifyControlTutorialAction(
                ControlTutorialAction.VOLUME_DOWN_HOLD
            )
        }
        DiagnosticsLog.add(
            "$logName: volume-down calibration source=$source success=${result.success} " +
                "failure=${result.failureReason ?: "none"}"
        )
    }

    private fun triggerVolumeUpAction(source: String) {
        if (!volumeUpHeld || volumeUpLongPressTriggered) return
        volumeUpLongPressTriggered = true
        handler.removeCallbacks(volumeUpLongPressRunnable)
        handler.removeCallbacks(volumeUpHudRunnable)
        onInteraction()
        val wasBlackoutVisible = isBlackoutVisible()
        onBlackoutToggleRequested(wasBlackoutVisible)
        ControlAccessibilityService.completeExternalHoldHud(success = true)
        ControlAccessibilityService.notifyControlTutorialAction(
            blackoutTutorialAction(wasBlackoutVisible)
        )
        DiagnosticsLog.add(
            "$logName: volume-up blackout toggle source=$source " +
                "action=${if (wasBlackoutVisible) "unlock" else "lock"}"
        )
    }

    private fun adjustMediaVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        ControlAccessibilityService.showExternalVolumeHud(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        )
    }

    private fun scheduleVolumeDownHud(heldDurationMs: Long) {
        handler.removeCallbacks(volumeDownHudRunnable)
        if (shouldRevealVolumeHoldHud(heldDurationMs)) {
            ControlAccessibilityService.beginExternalHoldHud(
                ExternalControlHudView.HoldAction.CALIBRATE,
                HOLD_DURATION_MS,
                heldDurationMs
            )
        } else {
            handler.postDelayed(
                volumeDownHudRunnable,
                HOLD_HUD_REVEAL_MS - heldDurationMs
            )
        }
    }

    private fun scheduleVolumeUpHud(heldDurationMs: Long) {
        handler.removeCallbacks(volumeUpHudRunnable)
        if (shouldRevealVolumeHoldHud(heldDurationMs)) {
            ControlAccessibilityService.beginExternalHoldHud(
                currentVolumeUpHoldAction(),
                HOLD_DURATION_MS,
                heldDurationMs
            )
        } else {
            handler.postDelayed(
                volumeUpHudRunnable,
                HOLD_HUD_REVEAL_MS - heldDurationMs
            )
        }
    }

    private fun currentVolumeUpHoldAction(): ExternalControlHudView.HoldAction =
        if (isBlackoutVisible()) {
            ExternalControlHudView.HoldAction.UNLOCK
        } else {
            ExternalControlHudView.HoldAction.LOCK
        }

}

internal const val CONTROL_SURFACE_VOLUME_HOLD_MS = 660L
internal const val CONTROL_SURFACE_HOLD_HUD_REVEAL_MS = 120L

private const val HOLD_DURATION_MS = CONTROL_SURFACE_VOLUME_HOLD_MS
private const val HOLD_HUD_REVEAL_MS = CONTROL_SURFACE_HOLD_HUD_REVEAL_MS

internal fun shouldTriggerVolumeHold(heldDurationMs: Long): Boolean =
    heldDurationMs >= CONTROL_SURFACE_VOLUME_HOLD_MS

internal fun shouldRevealVolumeHoldHud(heldDurationMs: Long): Boolean =
    heldDurationMs >= CONTROL_SURFACE_HOLD_HUD_REVEAL_MS

internal fun isVolumeKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

internal fun blackoutTutorialAction(wasBlackoutVisible: Boolean): ControlTutorialAction =
    if (wasBlackoutVisible) {
        ControlTutorialAction.BLACKOUT_UNLOCK
    } else {
        ControlTutorialAction.BLACKOUT_LOCK
    }
