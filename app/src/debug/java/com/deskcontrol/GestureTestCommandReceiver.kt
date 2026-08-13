package com.deskcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
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
                resultCode = if (
                    service.updateContinuousGestureTo(
                        intent.getFloatExtra(EXTRA_X, 0f),
                        intent.getFloatExtra(EXTRA_Y, 0f)
                    )
                ) {
                    RESULT_OK
                } else {
                    RESULT_REJECTED
                }
            }

            ACTION_RUN_PAUSED_REVERSAL -> runPausedReversal(
                service,
                intent.getLongExtra(EXTRA_PAUSE_MS, PAUSED_REVERSAL_PAUSE_MS)
            )

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

    private fun runPausedReversal(
        service: ControlAccessibilityService?,
        pauseMs: Long
    ) {
        if (service == null) {
            resultCode = RESULT_UNAVAILABLE
            return
        }
        val pendingResult = goAsync()
        service.moveCursorTo(640f, 468f)
        val started = service.startContinuousGestureAtCursor()
        Log.i(COMMAND_LOG_TAG, "start accepted=$started pauseMs=$pauseMs")
        if (!started) {
            pendingResult.resultCode = RESULT_REJECTED
            pendingResult.finish()
            return
        }
        var allUpdatesAccepted = service.updateContinuousGestureTo(640f, 288f)
        Log.i(
            COMMAND_LOG_TAG,
            "update index=initial accepted=$allUpdatesAccepted"
        )
        val handler = Handler(Looper.getMainLooper())
        val reversePoints = floatArrayOf(308f, 328f, 348f, 368f, 388f)
        reversePoints.forEachIndexed { index, y ->
            handler.postDelayed(
                {
                    val accepted = service.updateContinuousGestureTo(640f, y)
                    allUpdatesAccepted = accepted && allUpdatesAccepted
                    Log.i(
                        COMMAND_LOG_TAG,
                        "update index=$index y=${y.toInt()} " +
                            "accepted=$accepted"
                    )
                },
                pauseMs + index * PAUSED_REVERSAL_STEP_MS
            )
        }
        handler.postDelayed(
            {
                service.endContinuousGesture()
                service.whenContinuousGestureIdle {
                    Log.i(
                        COMMAND_LOG_TAG,
                        "idle allUpdatesAccepted=$allUpdatesAccepted"
                    )
                    pendingResult.resultCode =
                        if (allUpdatesAccepted) RESULT_OK else RESULT_REJECTED
                    pendingResult.finish()
                }
            },
            pauseMs + reversePoints.size * PAUSED_REVERSAL_STEP_MS
        )
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
        const val ACTION_RUN_PAUSED_REVERSAL =
            "com.deskcontrol.test.RUN_PAUSED_REVERSAL"
        const val ACTION_END = "com.deskcontrol.test.END"
        const val ACTION_WAIT_IDLE = "com.deskcontrol.test.WAIT_IDLE"
        const val ACTION_SHOW_VOLUME_HUD = "com.deskcontrol.test.SHOW_VOLUME_HUD"
        const val ACTION_SHOW_HOLD_HUD = "com.deskcontrol.test.SHOW_HOLD_HUD"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_HOLD_ACTION = "hold_action"
        const val EXTRA_PAUSE_MS = "pause_ms"
        const val RESULT_OK = 1
        const val RESULT_REJECTED = 0
        const val RESULT_UNAVAILABLE = -1
        private const val HUD_PREVIEW_DURATION_MS = 60_000L
        private const val HUD_PREVIEW_ELAPSED_MS = 36_000L
        private const val PAUSED_REVERSAL_PAUSE_MS = 500L
        private const val PAUSED_REVERSAL_STEP_MS = 120L
        private const val COMMAND_LOG_TAG = "GestureDeviceCommand"
    }
}
