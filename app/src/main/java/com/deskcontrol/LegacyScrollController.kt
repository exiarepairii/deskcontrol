package com.deskcontrol

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs

class LegacyScrollController(
    private val context: Context,
    private val handler: Handler,
    private val serviceProvider: () -> ControlAccessibilityService?
) : ScrollController {
    private var lastTwoFingerX = 0f
    private var lastTwoFingerY = 0f
    private var scrollAccumDx = 0f
    private var scrollAccumDy = 0f
    private var scrollSpeedMultiplier = 1f
    private var lastScrollEventTime = 0L
    private var scrollTickerRunning = false
    private var injectAnchorX = 0f
    private var injectAnchorY = 0f
    private var scrollDistanceAccum = 0f

    override fun enter(service: ControlAccessibilityService, event: MotionEvent): Boolean {
        lastTwoFingerX = averageX(event)
        lastTwoFingerY = averageY(event)
        lastScrollEventTime = event.eventTime
        scrollAccumDx = 0f
        scrollAccumDy = 0f
        scrollDistanceAccum = 0f
        scrollSpeedMultiplier = 1f
        val cursor = service.getCursorPosition()
        val injectAnchor = service.prepareScrollMode(cursor.x, cursor.y)
        injectAnchorX = injectAnchor.x
        injectAnchorY = injectAnchor.y
        DiagnosticsLog.add(
            "Touchpad: scroll mode enter anchor=(${cursor.x.toInt()},${cursor.y.toInt()})"
        )
        startTicker()
        return true
    }

    override fun update(event: MotionEvent) {
        val currentX = averageX(event)
        val currentY = averageY(event)
        val deltaX = currentX - lastTwoFingerX
        val deltaY = currentY - lastTwoFingerY
        lastTwoFingerX = currentX
        lastTwoFingerY = currentY
        if ((deltaX > 0f && scrollAccumDx < 0f) || (deltaX < 0f && scrollAccumDx > 0f)) {
            scrollAccumDx = 0f
            scrollDistanceAccum = 0f
        }
        if ((deltaY > 0f && scrollAccumDy < 0f) || (deltaY < 0f && scrollAccumDy > 0f)) {
            scrollAccumDy = 0f
            scrollDistanceAccum = 0f
        }
        scrollAccumDx += deltaX
        scrollAccumDy += deltaY
        scrollDistanceAccum += kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
        val dt = (event.eventTime - lastScrollEventTime).coerceAtLeast(1L)
        val velocity = kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat() / dt.toFloat()
        scrollSpeedMultiplier = computeScrollSpeedMultiplier(velocity)
        lastScrollEventTime = event.eventTime
        if (velocity < SCROLL_STOP_VELOCITY &&
            abs(scrollAccumDx) < scrollStopThreshold() &&
            abs(scrollAccumDy) < scrollStopThreshold()
        ) {
            scrollAccumDx = 0f
            scrollAccumDy = 0f
            if (scrollDistanceAccum < scrollStopThreshold() * SCROLL_STOP_DISTANCE_WINDOW) {
                scrollDistanceAccum = 0f
            }
            stopTicker()
        }
        if (velocity < SCROLL_DISTANCE_DECAY_VELOCITY) {
            scrollDistanceAccum *= SCROLL_DISTANCE_DECAY_FACTOR
        }
        if (!scrollTickerRunning) {
            startTicker()
        }
        emitScrollSteps()
    }

    override fun exit() {
        stopTicker()
        scrollAccumDx = 0f
        scrollAccumDy = 0f
        scrollDistanceAccum = 0f
        lastScrollEventTime = 0L
        DiagnosticsLog.add("Touchpad: scroll mode exit")
    }

    private fun startTicker() {
        if (scrollTickerRunning) return
        scrollTickerRunning = true
        handler.post(scrollTicker)
    }

    private fun stopTicker() {
        if (!scrollTickerRunning) return
        handler.removeCallbacks(scrollTicker)
        scrollTickerRunning = false
    }

    private val scrollTicker = object : Runnable {
        override fun run() {
            if (!scrollTickerRunning) return
            val idleFor = SystemClock.uptimeMillis() - lastScrollEventTime
            if (idleFor > SCROLL_IDLE_TIMEOUT_MS) {
                scrollAccumDx = 0f
                scrollAccumDy = 0f
                scrollTickerRunning = false
                return
            }
            emitScrollSteps()
            handler.postDelayed(this, SCROLL_TICK_MS)
        }
    }

    private fun emitScrollSteps() {
        val service = serviceProvider() ?: return
        val userScale = SettingsStore.touchpadScrollSpeed.coerceIn(
            SCROLL_SPEED_SETTING_MIN,
            SCROLL_SPEED_SETTING_MAX
        )
        val baseSpeed = SettingsStore.getTouchpadScrollBaseSpeed()
        val effectiveSpeed = baseSpeed * userScale
        val velocityMultiplier = scrollSpeedMultiplier.coerceIn(SCROLL_SPEED_MIN, SCROLL_SPEED_MAX)
        val adaptiveMultiplier = 1f + (velocityMultiplier - 1f) * SCROLL_SPEED_ADAPTIVITY
        val adaptiveStepMultiplier = 1f + (velocityMultiplier - 1f) * SCROLL_STEP_ADAPTIVITY
        val distanceFactor = computeDistanceFactor(scrollDistanceAccum)
        val adaptiveSpeed = (effectiveSpeed * adaptiveMultiplier * distanceFactor)
            .coerceIn(effectiveSpeed * 0.35f, effectiveSpeed * 3.0f)
        val adaptiveStepSpeed = (effectiveSpeed * adaptiveStepMultiplier * distanceFactor)
            .coerceIn(effectiveSpeed * 0.5f, effectiveSpeed * 2.2f)
        val gestureSpeed = adaptiveSpeed.coerceIn(SCROLL_GESTURE_SPEED_MIN, SCROLL_SPEED_MAX)
        val stepThreshold =
            (context.resources.displayMetrics.density * SettingsStore.touchpadScrollStepDp) / adaptiveStepSpeed
        if (stepThreshold <= 0f) return
        val absAccumX = abs(scrollAccumDx)
        val absAccumY = abs(scrollAccumDy)
        if (absAccumX < stepThreshold && absAccumY < stepThreshold) return
        val axis = if (absAccumX > absAccumY) {
            ControlAccessibilityService.ScrollAxis.HORIZONTAL
        } else {
            ControlAccessibilityService.ScrollAxis.VERTICAL
        }
        val accum = if (axis == ControlAccessibilityService.ScrollAxis.HORIZONTAL) scrollAccumDx else scrollAccumDy
        var direction = if (accum > 0f) 1 else -1
        if (SettingsStore.touchpadScrollInverted) {
            direction *= -1
        }
        val maxSteps = if (distanceFactor >= SCROLL_MAX_STEP_BOOST_THRESHOLD) {
            SCROLL_MAX_STEPS_FAST
        } else {
            SCROLL_MAX_STEPS_PER_TICK
        }
        var stepsToEmit =
            ((if (axis == ControlAccessibilityService.ScrollAxis.HORIZONTAL) absAccumX else absAccumY) / stepThreshold).toInt()
        if (stepsToEmit > maxSteps) stepsToEmit = maxSteps
        var emitted = 0
        while (emitted < stepsToEmit) {
            val success = service.performScrollStep(
                direction = direction,
                injectAnchorX = injectAnchorX,
                injectAnchorY = injectAnchorY,
                speedMultiplier = gestureSpeed,
                preferGesture = true,
                axis = axis
            )
            if (!success) break
            if (axis == ControlAccessibilityService.ScrollAxis.HORIZONTAL) {
                scrollAccumDx -= direction * stepThreshold
            } else {
                scrollAccumDy -= direction * stepThreshold
            }
            emitted += 1
        }
    }

    private fun computeScrollSpeedMultiplier(velocityPxPerMs: Float): Float {
        if (velocityPxPerMs <= 0f) return 1f
        val scaled = velocityPxPerMs / SCROLL_SPEED_BASE_PX_PER_MS
        return scaled.coerceIn(SCROLL_SPEED_MIN, SCROLL_SPEED_MAX)
    }

    private fun computeDistanceFactor(distancePx: Float): Float {
        if (distancePx <= 0f) return 1f
        val threshold = context.resources.displayMetrics.density * SCROLL_DISTANCE_ACCEL_DP
        if (threshold <= 0f) return 1f
        val normalized = (distancePx / threshold).coerceAtLeast(0f)
        val factor = 1f + kotlin.math.sqrt(normalized) * SCROLL_DISTANCE_ACCEL_GAIN
        return factor.coerceIn(1f, SCROLL_DISTANCE_ACCEL_MAX)
    }

    private fun scrollStopThreshold(): Float {
        val userScale = SettingsStore.touchpadScrollSpeed.coerceIn(
            SCROLL_SPEED_SETTING_MIN,
            SCROLL_SPEED_SETTING_MAX
        )
        val baseSpeed = SettingsStore.getTouchpadScrollBaseSpeed()
        val effectiveSpeed = baseSpeed * userScale
        val stepThreshold =
            (context.resources.displayMetrics.density * SettingsStore.touchpadScrollStepDp) / effectiveSpeed
        return stepThreshold * SCROLL_STOP_WINDOW_STEPS
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 0) return 0f
        return (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount
    }

    private fun averageX(event: MotionEvent): Float {
        if (event.pointerCount == 0) return 0f
        return (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount
    }

    companion object {
        private const val SCROLL_TICK_MS = 16L
        private const val SCROLL_MAX_STEPS_PER_TICK = 3
        private const val SCROLL_SPEED_BASE_PX_PER_MS = 0.35f
        private const val SCROLL_SPEED_MIN = 0.35f
        private const val SCROLL_SPEED_MAX = 3.0f
        private const val SCROLL_GESTURE_SPEED_MIN = 0.2f
        private const val SCROLL_SPEED_SETTING_MIN = 0.5f
        private const val SCROLL_SPEED_SETTING_MAX = 3.0f
        private const val SCROLL_SPEED_ADAPTIVITY = 1.05f
        private const val SCROLL_STEP_ADAPTIVITY = 0.85f
        private const val SCROLL_DISTANCE_ACCEL_DP = 120f
        private const val SCROLL_DISTANCE_ACCEL_GAIN = 0.35f
        private const val SCROLL_DISTANCE_ACCEL_MAX = 1.8f
        private const val SCROLL_DISTANCE_DECAY_VELOCITY = 0.35f
        private const val SCROLL_DISTANCE_DECAY_FACTOR = 0.85f
        private const val SCROLL_STOP_VELOCITY = 0.22f
        private const val SCROLL_STOP_WINDOW_STEPS = 0.8f
        private const val SCROLL_STOP_DISTANCE_WINDOW = 2.0f
        private const val SCROLL_MAX_STEPS_FAST = 6
        private const val SCROLL_MAX_STEP_BOOST_THRESHOLD = 1.3f
        private const val SCROLL_IDLE_TIMEOUT_MS = 50L
    }
}

