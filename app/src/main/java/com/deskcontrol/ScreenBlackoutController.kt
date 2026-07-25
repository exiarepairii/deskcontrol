package com.deskcontrol

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import kotlin.math.abs

/** Shared full-screen blackout and swipe-to-unlock behavior for control surfaces. */
class ScreenBlackoutController(
    private val overlay: View,
    private val hint: View,
    private val logName: String,
    private val onBeforeShow: () -> Unit = {},
    private val onUserInteraction: () -> Unit = {},
    private val onUnlocked: () -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlopPx = ViewConfiguration.get(overlay.context).scaledTouchSlop.toFloat()
    private val swipeMinPx = overlay.resources.displayMetrics.density * SWIPE_MIN_DP

    private var hintFadeRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var swipeOffset = 0f

    val isVisible: Boolean
        get() = overlay.isVisible

    init {
        bindTouchListener()
    }

    fun show() {
        if (overlay.isVisible) return
        onBeforeShow()
        resetOverlayPosition()
        hideHint()
        overlay.isVisible = true
        DiagnosticsLog.add("$logName: blackout=true")
    }

    fun hide() {
        if (!overlay.isVisible) return
        overlay.animate().cancel()
        resetOverlayPosition()
        hideHint()
        overlay.isVisible = false
        DiagnosticsLog.add("$logName: blackout=false")
    }

    fun destroy() {
        overlay.animate().cancel()
        hint.animate().cancel()
        handler.removeCallbacksAndMessages(null)
        overlay.setOnTouchListener(null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTouchListener() {
        overlay.setOnTouchListener { view, event ->
            if (!overlay.isVisible) return@setOnTouchListener false
            if (event.pointerCount > 1) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    resetOverlayPosition()
                    showHintImmediate()
                    onUserInteraction()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                        moved = true
                    }
                    showHintImmediate()
                    if (moved) {
                        val targetOffset = minOf(0f, dy)
                        swipeOffset += (targetOffset - swipeOffset) * SWIPE_SMOOTHING
                        view.translationY = swipeOffset
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val isSwipeUp = dy <= -swipeMinPx && abs(dy) > abs(dx)
                    if (isSwipeUp) {
                        view.animate()
                            .translationY(-view.height.toFloat().coerceAtLeast(1f))
                            .setDuration(SWIPE_ANIMATION_MS)
                            .withEndAction {
                                hide()
                                onUnlocked()
                            }
                            .start()
                    } else {
                        if (!moved) view.performClick()
                        animateBackToLockedPosition()
                        scheduleHintFade()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    animateBackToLockedPosition()
                    scheduleHintFade()
                    true
                }

                else -> false
            }
        }
    }

    private fun animateBackToLockedPosition() {
        overlay.animate()
            .translationY(0f)
            .setDuration(SWIPE_ANIMATION_MS)
            .withEndAction { swipeOffset = 0f }
            .start()
    }

    private fun resetOverlayPosition() {
        swipeOffset = 0f
        overlay.translationY = 0f
    }

    private fun showHintImmediate() {
        hint.animate().cancel()
        hintFadeRunnable?.let(handler::removeCallbacks)
        hint.alpha = 1f
        hint.isVisible = true
    }

    private fun scheduleHintFade() {
        hintFadeRunnable?.let(handler::removeCallbacks)
        hintFadeRunnable = Runnable {
            if (!overlay.isVisible) return@Runnable
            hint.animate()
                .alpha(0f)
                .setDuration(HINT_FADE_MS)
                .withEndAction { hint.isVisible = false }
                .start()
        }
        handler.postDelayed(hintFadeRunnable!!, HINT_VISIBLE_MS)
    }

    private fun hideHint() {
        hintFadeRunnable?.let(handler::removeCallbacks)
        hintFadeRunnable = null
        hint.animate().cancel()
        hint.alpha = 0f
        hint.isVisible = false
    }

    private companion object {
        const val SWIPE_MIN_DP = 120f
        const val HINT_VISIBLE_MS = 2_000L
        const val HINT_FADE_MS = 400L
        const val SWIPE_ANIMATION_MS = 180L
        const val SWIPE_SMOOTHING = 0.25f
    }
}
