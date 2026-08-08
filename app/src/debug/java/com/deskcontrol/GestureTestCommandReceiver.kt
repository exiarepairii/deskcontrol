package com.deskcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

class GestureTestCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = ControlAccessibilityService.current()
        when (intent.action) {
            ACTION_STATUS -> {
                if (service != null && !service.hasExternalDisplaySession()) {
                    attachToExternalDisplay(context)
                }
                resultCode =
                    if (service?.hasExternalDisplaySession() == true) RESULT_OK else RESULT_UNAVAILABLE
            }

            ACTION_START -> {
                if (service == null) {
                    resultCode = RESULT_UNAVAILABLE
                    return
                }
                service.moveCursorTo(
                    intent.getFloatExtra(EXTRA_X, 0f),
                    intent.getFloatExtra(EXTRA_Y, 0f)
                )
                resultCode =
                    if (service.startContinuousGestureAtCursor()) RESULT_OK else RESULT_REJECTED
            }

            ACTION_UPDATE -> {
                if (service == null) {
                    resultCode = RESULT_UNAVAILABLE
                    return
                }
                service.updateContinuousGestureTo(
                    intent.getFloatExtra(EXTRA_X, 0f),
                    intent.getFloatExtra(EXTRA_Y, 0f)
                )
                resultCode = RESULT_OK
            }

            ACTION_END -> {
                if (service == null) {
                    resultCode = RESULT_UNAVAILABLE
                    return
                }
                val pendingResult = goAsync()
                service.endContinuousGesture()
                service.whenContinuousGestureIdle {
                    pendingResult.resultCode = RESULT_OK
                    pendingResult.finish()
                }
            }

            ACTION_WAIT_IDLE -> {
                if (service == null) {
                    resultCode = RESULT_UNAVAILABLE
                    return
                }
                val pendingResult = goAsync()
                service.whenContinuousGestureIdle {
                    pendingResult.resultCode = RESULT_OK
                    pendingResult.finish()
                }
            }

            ACTION_SHOW_VOLUME_HUD -> {
                ControlAccessibilityService.showExternalVolumeHud(level = 9, maxLevel = 15)
                resultCode = if (service != null) RESULT_OK else RESULT_UNAVAILABLE
            }

            ACTION_SHOW_HOLD_HUD -> {
                val action = runCatching {
                    ExternalControlHudView.HoldAction.valueOf(
                        intent.getStringExtra(EXTRA_HOLD_ACTION).orEmpty()
                    )
                }.getOrDefault(ExternalControlHudView.HoldAction.CALIBRATE)
                ControlAccessibilityService.beginExternalHoldHud(
                    action = action,
                    durationMs = HUD_PREVIEW_DURATION_MS,
                    elapsedMs = HUD_PREVIEW_ELAPSED_MS
                )
                resultCode = if (service != null) RESULT_OK else RESULT_UNAVAILABLE
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun attachToExternalDisplay(context: Context) {
        val display = context.getSystemService(DisplayManager::class.java)
            .displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: return
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        ControlAccessibilityService.requestAttachToDisplay(
            DisplaySessionManager.ExternalDisplayInfo(
                displayId = display.displayId,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                rotation = display.rotation
            )
        )
    }

    companion object {
        const val ACTION_STATUS = "com.deskcontrol.test.STATUS"
        const val ACTION_START = "com.deskcontrol.test.START"
        const val ACTION_UPDATE = "com.deskcontrol.test.UPDATE"
        const val ACTION_END = "com.deskcontrol.test.END"
        const val ACTION_WAIT_IDLE = "com.deskcontrol.test.WAIT_IDLE"
        const val ACTION_SHOW_VOLUME_HUD = "com.deskcontrol.test.SHOW_VOLUME_HUD"
        const val ACTION_SHOW_HOLD_HUD = "com.deskcontrol.test.SHOW_HOLD_HUD"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_HOLD_ACTION = "hold_action"
        const val RESULT_OK = 1
        const val RESULT_REJECTED = 0
        const val RESULT_UNAVAILABLE = -1
        private const val HUD_PREVIEW_DURATION_MS = 60_000L
        private const val HUD_PREVIEW_ELAPSED_MS = 36_000L
    }
}
