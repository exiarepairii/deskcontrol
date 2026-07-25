package com.deskcontrol

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager

/**
 * Applies the phone-window behavior shared by control surfaces such as Touchpad and Motion Mouse.
 *
 * A dim session captures the original window brightness exactly once. Repeated state updates do
 * not restart the session, which prevents an already-dimmed value from becoming the new restore
 * target. External-display input focus must not stop dimming while the phone control page remains
 * resumed; deactivation or leaving the page ends the session and restores the captured value.
 */
class ControlSurfaceWindowPolicy(
    private val activity: Activity,
    private val logName: String
) {
    private val handler = Handler(Looper.getMainLooper())

    private var resumed = false
    private var windowFocused = false
    private var interactionActive = false
    private var keepScreenOnApplied = false

    private var dimSessionActive = false
    private var dimGeneration = 0L
    private var dimRunnable: Runnable? = null
    private var dimAnimator: ValueAnimator? = null
    private var originalWindowBrightness = 0f
    private var originalSystemBrightness = 1f
    private var hasOriginalWindowBrightness = false
    private var dimmedThisSession = false

    fun onResume() {
        resumed = true
        windowFocused = activity.hasWindowFocus()
        updateKeepScreenOn()
        reconcileDimSession("resume")
    }

    fun onPause() {
        resumed = false
        windowFocused = false
        updateKeepScreenOn()
        stopDimSession("pause")
    }

    fun onStop() {
        resumed = false
        updateKeepScreenOn()
        stopDimSession("stop")
    }

    fun onDestroy() {
        resumed = false
        windowFocused = false
        interactionActive = false
        updateKeepScreenOn()
        stopDimSession("destroy")
        handler.removeCallbacksAndMessages(null)
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (windowFocused == hasFocus) return
        windowFocused = hasFocus
        DiagnosticsLog.add("$logName: phone window focused=$hasFocus")
        if (hasFocus) {
            reconcileDimSession("focus_gained")
        }
    }

    fun setInteractionActive(active: Boolean) {
        if (interactionActive == active) {
            reconcileDimSession("state_refresh")
            return
        }
        interactionActive = active
        DiagnosticsLog.add("$logName: window interaction active=$active")
        reconcileDimSession("interaction_changed")
    }

    /** Restores brightness first, then starts a fresh countdown if the surface is still active. */
    fun restartAutoDimCountdown() {
        stopDimSession("countdown_restart")
        reconcileDimSession("countdown_restart")
    }

    private fun updateKeepScreenOn() {
        val shouldKeepScreenOn = resumed && SettingsStore.keepScreenOn
        if (keepScreenOnApplied == shouldKeepScreenOn) return
        keepScreenOnApplied = shouldKeepScreenOn
        if (shouldKeepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        DiagnosticsLog.add("$logName: keep screen on=$shouldKeepScreenOn")
    }

    private fun reconcileDimSession(reason: String) {
        val shouldRun = resumed &&
            interactionActive &&
            SettingsStore.touchpadAutoDimEnabled
        if (shouldRun) {
            ensureDimSession(reason)
        } else {
            stopDimSession(reason)
        }
    }

    private fun ensureDimSession(reason: String) {
        if (dimSessionActive) return
        dimSessionActive = true
        dimGeneration += 1
        dimmedThisSession = false
        captureOriginalBrightness()

        val generation = dimGeneration
        dimRunnable = Runnable {
            dimRunnable = null
            if (!dimSessionActive || generation != dimGeneration || dimmedThisSession) {
                return@Runnable
            }
            dimWindowBrightness()
        }
        handler.postDelayed(dimRunnable!!, AUTO_DIM_DELAY_MS)
        DiagnosticsLog.add("$logName: dim timer started reason=$reason")
    }

    private fun stopDimSession(reason: String) {
        val hadSession = dimSessionActive || hasOriginalWindowBrightness
        dimSessionActive = false
        dimGeneration += 1
        cancelDimTimer()
        cancelDimAnimator()
        restoreOriginalBrightness()
        if (hadSession) {
            DiagnosticsLog.add("$logName: dim session stopped reason=$reason")
        }
    }

    private fun captureOriginalBrightness() {
        if (hasOriginalWindowBrightness) return
        originalWindowBrightness = activity.window.attributes.screenBrightness
        originalSystemBrightness = readSystemBrightness()
        hasOriginalWindowBrightness = true
    }

    private fun restoreOriginalBrightness() {
        if (!hasOriginalWindowBrightness) return
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = originalWindowBrightness
        }
        hasOriginalWindowBrightness = false
        dimmedThisSession = false
        DiagnosticsLog.add("$logName: brightness restored")
    }

    private fun dimWindowBrightness() {
        val target = computeDimTarget() ?: run {
            DiagnosticsLog.add("$logName: dim skipped (avoid brightening)")
            return
        }
        val start = getEstimatedCurrentBrightness().coerceAtLeast(target)
        if (start <= target) {
            applyWindowBrightness(target)
            dimmedThisSession = true
            DiagnosticsLog.add("$logName: dimmed target=$target")
            return
        }
        dimAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = DIM_ANIMATION_DURATION_MS
            addUpdateListener { animator ->
                applyWindowBrightness(animator.animatedValue as Float)
            }
            start()
        }
        dimmedThisSession = true
        DiagnosticsLog.add("$logName: dimmed target=$target")
    }

    private fun applyWindowBrightness(value: Float) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = value.coerceIn(0f, 1f)
        }
    }

    private fun getEstimatedCurrentBrightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        return if (windowValue >= 0f) {
            windowValue.coerceIn(0f, 1f)
        } else {
            readSystemBrightness()
        }
    }

    private fun computeDimTarget(): Float? {
        val preferred = SettingsStore.touchpadDimLevel.coerceIn(0f, 1f)
        if (originalWindowBrightness < 0f) {
            val systemBrightness = originalSystemBrightness.coerceIn(0f, 1f)
            if (preferred >= systemBrightness) return null
            return preferred.coerceAtMost(systemBrightness)
        }
        return minOf(preferred, getEstimatedCurrentBrightness())
    }

    private fun readSystemBrightness(): Float {
        return try {
            val systemValue = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (systemValue / 255f).coerceIn(0f, 1f)
        } catch (_: Exception) {
            SettingsStore.touchpadDimLevel.coerceIn(0f, 1f)
        }
    }

    private fun cancelDimTimer() {
        dimRunnable?.let(handler::removeCallbacks)
        dimRunnable = null
    }

    private fun cancelDimAnimator() {
        dimAnimator?.cancel()
        dimAnimator = null
    }

    private companion object {
        const val AUTO_DIM_DELAY_MS = 10_000L
        const val DIM_ANIMATION_DURATION_MS = 400L
    }
}
