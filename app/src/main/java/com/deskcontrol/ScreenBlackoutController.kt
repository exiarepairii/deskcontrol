package com.deskcontrol

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import java.util.Locale
import kotlin.math.abs

/** Shared full-screen blackout and swipe-to-unlock behavior for control surfaces. */
class ScreenBlackoutController(
    private val overlay: View,
    private val hint: View,
    private val logName: String,
    private val onBeforeShow: () -> Unit = {},
    private val onUserInteraction: () -> Unit = {},
    private val onUnlocked: () -> Unit = {},
    private val onVisibilityChanged: (Boolean) -> Unit = {}
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
        if (overlay.isVisible) {
            DiagnosticsLog.add("$logName: blackout show skipped already_visible=true")
            return
        }
        onBeforeShow()
        resetOverlayPosition()
        hideHint()
        overlay.isVisible = true
        onVisibilityChanged(true)
        DiagnosticsLog.add(
            "$logName: blackout=true overlay=${System.identityHashCode(overlay)} " +
                "hint=${System.identityHashCode(hint)} size=${overlay.width}x${overlay.height} " +
                "density=${format(overlay.resources.displayMetrics.density)}"
        )
    }

    fun hide(reason: String) {
        if (!overlay.isVisible) {
            DiagnosticsLog.add("$logName: blackout hide skipped reason=$reason already_visible=false")
            return
        }
        overlay.animate().cancel()
        resetOverlayPosition()
        hideHint()
        overlay.isVisible = false
        onVisibilityChanged(false)
        DiagnosticsLog.add("$logName: blackout=false reason=$reason")
    }

    fun destroy() {
        DiagnosticsLog.add(
            "$logName: blackout destroy visible=${overlay.isVisible} " +
                "translationY=${format(overlay.translationY)}"
        )
        if (overlay.isVisible) {
            onVisibilityChanged(false)
        }
        overlay.animate().cancel()
        hint.animate().cancel()
        handler.removeCallbacksAndMessages(null)
        overlay.setOnTouchListener(null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTouchListener() {
        overlay.setOnTouchListener { view, event ->
            if (!overlay.isVisible) return@setOnTouchListener false
            if (event.pointerCount > 1) {
                if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                    DiagnosticsLog.add(
                        "$logName: blackout touch action=${actionName(event.actionMasked)} " +
                            "pointers=${event.pointerCount} actionIndex=${event.actionIndex} " +
                            "ids=${pointerIds(event)} eventTime=${event.eventTime}"
                    )
                }
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    resetOverlayPosition()
                    showHintImmediate()
                    onUserInteraction()
                    DiagnosticsLog.add(
                        "$logName: blackout touch DOWN id=${event.getPointerId(0)} " +
                            "x=${format(event.x)} y=${format(event.y)} " +
                            "rawX=${format(event.rawX)} rawY=${format(event.rawY)} " +
                            "downTime=${event.downTime} eventTime=${event.eventTime}"
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                        moved = true
                        DiagnosticsLog.add(
                            "$logName: blackout touch MOVE_THRESHOLD " +
                                "dx=${format(dx)} dy=${format(dy)} slop=${format(touchSlopPx)}"
                        )
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
                    DiagnosticsLog.add(
                        "$logName: blackout touch UP id=${event.getPointerId(0)} " +
                            "x=${format(event.x)} y=${format(event.y)} " +
                            "dx=${format(dx)} dy=${format(dy)} moved=$moved " +
                            "swipeMin=${format(swipeMinPx)} isSwipeUp=$isSwipeUp " +
                            "translationY=${format(view.translationY)} " +
                            "duration=${event.eventTime - event.downTime}"
                    )
                    if (isSwipeUp) {
                        DiagnosticsLog.add("$logName: blackout unlock animation start")
                        view.animate()
                            .translationY(-view.height.toFloat().coerceAtLeast(1f))
                            .setDuration(SWIPE_ANIMATION_MS)
                            .withEndAction {
                                DiagnosticsLog.add(
                                    "$logName: blackout unlock animation end " +
                                        "visible=${overlay.isVisible}"
                                )
                                hide("swipe_unlock")
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
                    DiagnosticsLog.add(
                        "$logName: blackout touch CANCEL moved=$moved " +
                            "translationY=${format(view.translationY)}"
                    )
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
        DiagnosticsLog.add("$logName: blackout hint fade scheduled delay=$HINT_VISIBLE_MS")
        hintFadeRunnable = Runnable {
            if (!overlay.isVisible) {
                DiagnosticsLog.add("$logName: blackout hint fade skipped overlay_visible=false")
                return@Runnable
            }
            DiagnosticsLog.add("$logName: blackout hint fade start")
            hint.animate()
                .alpha(0f)
                .setDuration(HINT_FADE_MS)
                .withEndAction {
                    hint.isVisible = false
                    DiagnosticsLog.add(
                        "$logName: blackout hint fade end overlay_visible=${overlay.isVisible} " +
                            "hint_visible=${hint.isVisible}"
                    )
                }
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

    private fun pointerIds(event: MotionEvent): String {
        return (0 until event.pointerCount).joinToString(prefix = "[", postfix = "]") {
            event.getPointerId(it).toString()
        }
    }

    private fun actionName(action: Int): String {
        return when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> action.toString()
        }
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

    private companion object {
        const val SWIPE_MIN_DP = 120f
        const val HINT_VISIBLE_MS = 2_000L
        const val HINT_FADE_MS = 400L
        const val SWIPE_ANIMATION_MS = 180L
        const val SWIPE_SMOOTHING = 0.25f
    }
}
