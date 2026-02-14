package com.deskcontrol

import android.content.Context
import android.view.MotionEvent

class DirectScrollController(
    private val context: Context,
    private val touchpadSizeProvider: () -> Pair<Int, Int>,
    private val serviceProvider: () -> ControlAccessibilityService?
) : ScrollController {
    private var active = false
    private var lastScrollMidX = 0f
    private var lastScrollMidY = 0f
    private var scrollMapScaleX = 1f
    private var scrollMapScaleY = 1f
    private var directScrollGain = 1f
    private var directPendingDx = 0f
    private var directPendingDy = 0f
    private var directAnchorX = 0f
    private var directAnchorY = 0f

    override fun enter(service: ControlAccessibilityService, event: MotionEvent): Boolean {
        if (event.pointerCount < 2) return false
        val info = DisplaySessionManager.getExternalDisplayInfo() ?: return false
        val (touchpadWidthPx, touchpadHeightPx) = touchpadSizeProvider()
        val touchpadWidth = touchpadWidthPx.toFloat().coerceAtLeast(1f)
        val touchpadHeight = touchpadHeightPx.toFloat().coerceAtLeast(1f)
        scrollMapScaleX = info.width.toFloat() / touchpadWidth
        scrollMapScaleY = info.height.toFloat() / touchpadHeight
        directScrollGain = SettingsStore.touchpadDirectScrollGain
        val mid = scrollMidpoint(event)
        lastScrollMidX = mid.first * scrollMapScaleX
        lastScrollMidY = mid.second * scrollMapScaleY
        val cursor = service.getCursorPosition()
        directAnchorX = cursor.x
        directAnchorY = cursor.y
        directPendingDx = 0f
        directPendingDy = 0f
        active = true
        DiagnosticsLog.add(
            "Touchpad: direct scroll enter mid=(${lastScrollMidX.toInt()},${lastScrollMidY.toInt()}) " +
                "scale=(${String.format("%.2f", scrollMapScaleX)},${String.format("%.2f", scrollMapScaleY)}) " +
                "gain=${String.format("%.2f", directScrollGain)} " +
                "step=${SettingsStore.touchpadDirectScrollStepDp.toInt()}dp " +
                "anchor=(${directAnchorX.toInt()},${directAnchorY.toInt()})"
        )
        return true
    }

    override fun update(event: MotionEvent) {
        if (!active || event.pointerCount < 2) return
        val mid = scrollMidpoint(event)
        val mappedX = mid.first * scrollMapScaleX
        val mappedY = mid.second * scrollMapScaleY
        val scale = directScrollGain * DIRECT_SCROLL_BASE_AMPLIFY
        val dx = (mappedX - lastScrollMidX) * scale
        val dy = (mappedY - lastScrollMidY) * scale
        directPendingDx += dx
        directPendingDy += dy
        directPendingDx = directPendingDx.coerceIn(-DIRECT_SCROLL_PENDING_MAX_PX, DIRECT_SCROLL_PENDING_MAX_PX)
        directPendingDy = directPendingDy.coerceIn(-DIRECT_SCROLL_PENDING_MAX_PX, DIRECT_SCROLL_PENDING_MAX_PX)
        val service = serviceProvider()
        if (service != null && !service.isGestureBusy()) {
            val distance = kotlin.math.hypot(directPendingDx.toDouble(), directPendingDy.toDouble())
            if (distance >= DIRECT_SCROLL_MIN_SWIPE_PX) {
                val stepPx = SettingsStore.touchpadDirectScrollStepDp *
                    context.resources.displayMetrics.density
                val chunkLen = distance.coerceAtMost(stepPx.coerceIn(48f, DIRECT_SCROLL_PENDING_MAX_PX).toDouble())
                val ratio = (chunkLen / distance).toFloat()
                val stepDx = directPendingDx * ratio
                val stepDy = directPendingDy * ratio
                val injected = service.performDirectScrollGesture(
                    directAnchorX,
                    directAnchorY,
                    stepDx,
                    stepDy
                )
                if (injected) {
                    directAnchorX += stepDx
                    directAnchorY += stepDy
                    directPendingDx -= stepDx
                    directPendingDy -= stepDy
                }
            }
        }
        lastScrollMidX = mappedX
        lastScrollMidY = mappedY
    }

    override fun exit() {
        if (!active) return
        active = false
        directPendingDx = 0f
        directPendingDy = 0f
        DiagnosticsLog.add("Touchpad: direct scroll exit")
    }

    private fun scrollMidpoint(event: MotionEvent): Pair<Float, Float> {
        if (event.pointerCount < 2) return 0f to 0f
        var sumX = 0f
        var sumY = 0f
        val count = minOf(2, event.pointerCount)
        repeat(count) { index ->
            sumX += event.getX(index)
            sumY += event.getY(index)
        }
        return (sumX / count) to (sumY / count)
    }

    companion object {
        private const val DIRECT_SCROLL_BASE_AMPLIFY = 1.0f
        private const val DIRECT_SCROLL_MIN_SWIPE_PX = 26.0
        private const val DIRECT_SCROLL_PENDING_MAX_PX = 320f
    }
}
