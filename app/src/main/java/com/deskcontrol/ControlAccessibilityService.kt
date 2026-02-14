package com.deskcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

class ControlAccessibilityService : AccessibilityService() {
    enum class ScrollAxis { VERTICAL, HORIZONTAL }


    companion object {
        private const val WARMUP_MIN_INTERVAL_MS = 15_000L
        private const val BACK_FOCUS_DELAY_MS = 40L
        private const val ATTACH_RETRY_DELAY_MS = 250L
        private const val ATTACH_RETRY_MAX = 8
        private const val SCROLL_SAFE_PAD_X_DP = 24f
        private const val SCROLL_SAFE_PAD_TOP_DP = 24f
        private const val SCROLL_SAFE_PAD_BOTTOM_DP = 32f
        private const val SCROLL_SWIPE_BASE_DP = 48f
        private const val SCROLL_SWIPE_MIN_DP = 36f
        private const val SCROLL_SWIPE_MAX_DP = 60f
        private const val SCROLL_SWIPE_MIN_DP_PRECISION = 8f
        private const val SCROLL_SWIPE_BASE_DP_PRECISION = 24f
        private const val SCROLL_SWIPE_MAX_DP_PRECISION = 36f
        private const val SCROLL_PULL_MIN_DP_PRECISION = 14f
        private const val SCROLL_PULL_BASE_DP_PRECISION = 52f
        private const val SCROLL_PULL_MAX_DP_PRECISION = 96f
        private const val SCROLL_PULL_DURATION_SCALE = 1.35f
        private const val SCROLL_PULL_MIN_DURATION_MS = 48L
        private const val SCROLL_PULL_MAX_DURATION_MS = 96L
        private const val SCROLL_PUSH_MIN_DP_PRECISION = 10f
        private const val SCROLL_PUSH_BASE_DP_PRECISION = 34f
        private const val SCROLL_PUSH_MAX_DP_PRECISION = 52f
        private const val SCROLL_PUSH_DURATION_SCALE = 1.15f
        private const val SCROLL_PUSH_MIN_DURATION_MS = 40L
        private const val SCROLL_PUSH_MAX_DURATION_MS = 84L
        private const val SCROLL_SWIPE_MIN_DP_MICRO = 6f
        private const val SCROLL_SWIPE_BASE_DP_MICRO = 18f
        private const val SCROLL_SWIPE_MAX_DP_MICRO = 28f
        private const val SCROLL_SWIPE_MICRO_SPEED_MAX = 0.55f
        private const val SCROLL_SWIPE_BASE_DURATION_MS = 45L
        private const val SCROLL_SWIPE_MIN_DURATION_MS = 35L
        private const val SCROLL_SWIPE_MAX_DURATION_MS = 60L
        private const val MIN_SCROLL_DENSITY = 2.6f
        private const val CURSOR_TIP_FRACTION_X = 1f / 48f
        private const val CURSOR_TIP_FRACTION_Y = 1f / 48f
        private const val DIRECT_SCROLL_DURATION_MS = 48L
        private const val DIRECT_SCROLL_EDGE_EPSILON_PX = 2f
        private const val DIRECT_SCROLL_MIN_PATH_PX = 12f
        private const val DIRECT_SCROLL_MIN_PRIMARY_PX = 8f
        @Volatile
        private var instance: ControlAccessibilityService? = null
        @Volatile
        private var pendingDisplayInfo: DisplaySessionManager.ExternalDisplayInfo? = null

        fun current(): ControlAccessibilityService? = instance

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            if (enabled != 1) return false
            val component = ComponentName(context, ControlAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(component.flattenToString()) == true
        }

        fun requestAttachToDisplay(info: DisplaySessionManager.ExternalDisplayInfo?) {
            pendingDisplayInfo = info
            instance?.attachToDisplay(info)
        }

        fun requestDetachOverlay() {
            instance?.detachOverlay()
        }

        fun requestCursorAppearanceRefresh() {
            instance?.refreshCursorAppearance()
        }

        fun requestCursorForceVisible(enabled: Boolean) {
            instance?.setCursorForceVisible(enabled)
        }

        fun requestSwitchBarRefresh() {
            instance?.refreshSwitchBarSettings()
        }

        fun requestSwitchBarForceVisible(enabled: Boolean) {
            instance?.setSwitchBarForceVisible(enabled)
        }
    }

    private var overlayView: CursorOverlayView? = null
    private var switchBarController: SwitchBarController? = null
    private var windowManager: WindowManager? = null
    private var overlayWindowContext: Context? = null
    private var displayInfo: DisplaySessionManager.ExternalDisplayInfo? = null
    private var attachRetryInfo: DisplaySessionManager.ExternalDisplayInfo? = null
    private var attachRetryCount = 0
    private var attachRetryRunnable: Runnable? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorSizePx = 24
    private var cursorBaseSizePx = 16
    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragPointX = 0f
    private var dragPointY = 0f
    private var scrollStroke: GestureDescription.StrokeDescription? = null
    private var scrollPointX = 0f
    private var scrollPointY = 0f
    private var pendingScrollEnd = false
    private var pendingScrollEndX = 0f
    private var pendingScrollEndY = 0f
    private var lastScrollDiagMs = 0L
    private val dragStartDurationMs = 8L
    private val dragSegmentDurationMs = 16L
    @Volatile
    private var gesturesInFlight = 0
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var cursorVisible = true
    private var forceCursorVisible = false
    private var lastMoveTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val currentInfo = serviceInfo
        if (currentInfo != null) {
            currentInfo.flags = currentInfo.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = currentInfo
            DiagnosticsLog.add("Accessibility: flags=${currentInfo.flags}")
        }
        cursorSizePx = (resources.displayMetrics.density * 14f).toInt().coerceAtLeast(10)
        attachToDisplay(pendingDisplayInfo)
        DiagnosticsLog.add("Accessibility: connected")
    }

    override fun onDestroy() {
        detachOverlay()
        instance = null
        DiagnosticsLog.add("Accessibility: destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No-op for MVP.
    }

    override fun onInterrupt() {
        // No-op for MVP.
    }

    fun getCursorPosition(): PointF = PointF(cursorX, cursorY)

    fun moveCursorBy(dx: Float, dy: Float) {
        val info = displayInfo ?: return
        val maxX = info.width + (cursorSizePx / 4f)
        val maxY = info.height + (cursorSizePx / 4f)
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        notifyCursorActivity()
        notifyCursorSpeed(dx, dy)
        updateOverlayPosition()
        switchBarController?.onCursorMoved(cursorX, cursorY, cursorSizePx)
    }

    fun wakeCursor() {
        notifyCursorActivity()
    }

    fun tapAtCursor() {
        val info = displayInfo ?: return
        val clamped = clampToDisplay(cursorX, cursorY, info)
        val mapped = CoordinateMapper.mapForRotation(clamped.x, clamped.y, info)
        notifyCursorActivity()
        dispatchTap(mapped.x, mapped.y, info.displayId)
    }

    fun startDragAtCursor() {
        val info = displayInfo ?: return
        val clamped = clampToDisplay(cursorX, cursorY, info)
        val mapped = CoordinateMapper.mapForRotation(clamped.x, clamped.y, info)
        dragPointX = mapped.x
        dragPointY = mapped.y
        val path = Path().apply { moveTo(dragPointX, dragPointY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, dragStartDurationMs, true)
        dragStroke = stroke
        notifyCursorActivity()
        dispatchDragStroke(stroke, info.displayId)
    }

    fun updateDragToCursor() {
        val info = displayInfo ?: return
        val activeStroke = dragStroke ?: return
        val clamped = clampToDisplay(cursorX, cursorY, info)
        val mapped = CoordinateMapper.mapForRotation(clamped.x, clamped.y, info)
        if (abs(mapped.x - dragPointX) < 0.5f && abs(mapped.y - dragPointY) < 0.5f) return
        val path = Path().apply {
            moveTo(dragPointX, dragPointY)
            lineTo(mapped.x, mapped.y)
        }
        val stroke = activeStroke.continueStroke(path, 0, dragSegmentDurationMs, true)
        dragStroke = stroke
        dragPointX = mapped.x
        dragPointY = mapped.y
        notifyCursorActivity()
        dispatchDragStroke(stroke, info.displayId)
    }

    fun endDragAtCursor() {
        val info = displayInfo ?: return
        val activeStroke = dragStroke ?: return
        val clamped = clampToDisplay(cursorX, cursorY, info)
        val mapped = CoordinateMapper.mapForRotation(clamped.x, clamped.y, info)
        val path = Path().apply {
            moveTo(dragPointX, dragPointY)
            lineTo(mapped.x, mapped.y)
        }
        val stroke = activeStroke.continueStroke(path, 0, dragSegmentDurationMs, false)
        dragStroke = null
        dragPointX = mapped.x
        dragPointY = mapped.y
        notifyCursorActivity()
        dispatchDragStroke(stroke, info.displayId)
    }

    fun cancelDrag() {
        dragStroke = null
    }

    fun startScrollGestureAtCursor() {
        val info = displayInfo ?: return
        val anchor = resolveScrollAnchor(info, cursorX, cursorY)
        startScrollGestureAtPoint(info, anchor.first, anchor.second)
    }

    fun startScrollGestureAtPoint(x: Float, y: Float) {
        val info = displayInfo ?: return
        val anchor = resolveScrollAnchor(info, x, y)
        startScrollGestureAtPoint(info, anchor.first, anchor.second)
    }

    private fun startScrollGestureAtPoint(
        info: DisplaySessionManager.ExternalDisplayInfo,
        x: Float,
        y: Float
    ) {
        val mapped = CoordinateMapper.mapForRotation(x, y, info)
        scrollPointX = x
        scrollPointY = y
        val path = Path().apply { moveTo(mapped.x, mapped.y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, dragStartDurationMs, true)
        scrollStroke = stroke
        notifyCursorActivity()
        dispatchScrollStroke(stroke, info.displayId)
    }

    fun updateScrollGestureBy(dx: Float, dy: Float) {
        val info = displayInfo ?: return
        if (gesturesInFlight > 0) {
            maybeLogScroll("ScrollGesture: update skipped (busy)")
            return
        }
        val margin = 24f * densityFor(info)
        val minX = margin
        val maxX = info.width - margin
        val minY = margin
        val maxY = info.height - margin
        var prevX = scrollPointX
        var prevY = scrollPointY
        var nextX = scrollPointX + dx
        var nextY = scrollPointY + dy
        if (nextX < minX || nextX > maxX || nextY < minY || nextY > maxY) {
            endScrollGesture()
            val anchor = resolveScrollAnchor(info, scrollPointX, scrollPointY)
            startScrollGestureAtPoint(info, anchor.first, anchor.second)
            return
        }
        scrollPointX = nextX
        scrollPointY = nextY
        val activeStroke = scrollStroke ?: run {
            maybeLogScroll("ScrollGesture: update skipped (no active stroke)")
            return
        }
        val mappedStart = CoordinateMapper.mapForRotation(prevX, prevY, info)
        val mappedEnd = CoordinateMapper.mapForRotation(scrollPointX, scrollPointY, info)
        if (abs(mappedEnd.x - mappedStart.x) < 0.5f && abs(mappedEnd.y - mappedStart.y) < 0.5f) return
        val path = Path().apply {
            moveTo(mappedStart.x, mappedStart.y)
            lineTo(mappedEnd.x, mappedEnd.y)
        }
        val stroke = activeStroke.continueStroke(path, 0, dragSegmentDurationMs, true)
        scrollStroke = stroke
        notifyCursorActivity()
        dispatchScrollStroke(stroke, info.displayId)
    }

    fun performDirectScrollGesture(anchorX: Float, anchorY: Float, dx: Float, dy: Float): Boolean {
        val info = displayInfo ?: return false
        if (gesturesInFlight > 0) {
            return false
        }
        val safeRect = computeSafeRect(info)
        val startX = anchorX.coerceIn(safeRect.left, safeRect.right)
        val startY = anchorY.coerceIn(safeRect.top, safeRect.bottom)
        if ((dx < 0f && startX <= safeRect.left + DIRECT_SCROLL_EDGE_EPSILON_PX) ||
            (dx > 0f && startX >= safeRect.right - DIRECT_SCROLL_EDGE_EPSILON_PX) ||
            (dy < 0f && startY <= safeRect.top + DIRECT_SCROLL_EDGE_EPSILON_PX) ||
            (dy > 0f && startY >= safeRect.bottom - DIRECT_SCROLL_EDGE_EPSILON_PX)
        ) {
            return false
        }
        val endX = (startX + dx).coerceIn(safeRect.left, safeRect.right)
        val endY = (startY + dy).coerceIn(safeRect.top, safeRect.bottom)
        val actualDx = endX - startX
        val actualDy = endY - startY
        val pathLen = kotlin.math.hypot(actualDx.toDouble(), actualDy.toDouble()).toFloat()
        if (pathLen < DIRECT_SCROLL_MIN_PATH_PX) return false
        val horizontalDominant = abs(dx) >= abs(dy)
        if (horizontalDominant && abs(actualDx) < DIRECT_SCROLL_MIN_PRIMARY_PX) return false
        if (!horizontalDominant && abs(actualDy) < DIRECT_SCROLL_MIN_PRIMARY_PX) return false
        val start = CoordinateMapper.mapForRotation(startX, startY, info)
        val end = CoordinateMapper.mapForRotation(endX, endY, info)
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, info.displayId)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, DIRECT_SCROLL_DURATION_MS))
        dispatchGestureTracked(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_scroll_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_scroll_failed))
                }
            }
        )
        return true
    }

    fun endScrollGesture() {
        val info = displayInfo ?: return
        val activeStroke = scrollStroke ?: run {
            maybeLogScroll("ScrollGesture: end skipped (no active stroke)")
            return
        }
        if (gesturesInFlight > 0) {
            pendingScrollEnd = true
            pendingScrollEndX = scrollPointX
            pendingScrollEndY = scrollPointY
            maybeLogScroll("ScrollGesture: end queued (busy)")
            return
        }
        endScrollGestureInternal(info, activeStroke, scrollPointX, scrollPointY)
    }

    private fun endScrollGestureInternal(
        info: DisplaySessionManager.ExternalDisplayInfo,
        activeStroke: GestureDescription.StrokeDescription,
        x: Float,
        y: Float
    ) {
        val mapped = CoordinateMapper.mapForRotation(x, y, info)
        val path = Path().apply {
            moveTo(mapped.x, mapped.y)
            lineTo(mapped.x, mapped.y)
        }
        val stroke = activeStroke.continueStroke(path, 0, dragSegmentDurationMs, false)
        scrollStroke = null
        notifyCursorActivity()
        dispatchScrollStroke(stroke, info.displayId)
    }

    private fun maybeLogScroll(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollDiagMs < 250L) return
        lastScrollDiagMs = now
        DiagnosticsLog.add(message)
    }

    fun cancelScrollGesture() {
        scrollStroke = null
        pendingScrollEnd = false
    }

    fun hasActiveScrollGesture(): Boolean = scrollStroke != null

    fun isGestureBusy(): Boolean = gesturesInFlight > 0

    private fun resolveScrollAnchor(
        info: DisplaySessionManager.ExternalDisplayInfo,
        x: Float,
        y: Float
    ): Pair<Float, Float> {
        val margin = 24f * densityFor(info)
        val clampedX = x.coerceIn(margin, info.width - margin)
        val clampedY = y.coerceIn(margin, info.height - margin)
        if (clampedX.isNaN() || clampedY.isNaN()) {
            return Pair(info.width / 2f, info.height / 2f)
        }
        return Pair(clampedX, clampedY)
    }

    fun scrollVertical(steps: Int, stepSizePx: Float) {
        val info = displayInfo ?: run {
            recordInjection(false, getString(R.string.injection_no_external_display))
            return
        }
        notifyCursorActivity()
        DiagnosticsLog.add("Scroll: gesture steps=$steps")
        dispatchScrollGesture(steps, stepSizePx, info)
    }

    fun prepareScrollMode(anchorX: Float, anchorY: Float): PointF {
        val info = displayInfo ?: return PointF(anchorX, anchorY)
        val safeRect = computeSafeRect(info)
        val clampedX = anchorX.coerceIn(safeRect.left, safeRect.right)
        val clampedY = anchorY.coerceIn(safeRect.top, safeRect.bottom)
        DiagnosticsLog.add(
            "ScrollMode: enter anchor=(${anchorX.toInt()},${anchorY.toInt()}) " +
                "inject=(${clampedX.toInt()},${clampedY.toInt()}) " +
                "insets=(${safeRect.insetsLeft},${safeRect.insetsTop}," +
                "${safeRect.insetsRight},${safeRect.insetsBottom})"
        )
        return PointF(clampedX, clampedY)
    }

    fun performScrollStep(
        direction: Int,
        injectAnchorX: Float,
        injectAnchorY: Float,
        speedMultiplier: Float,
        preferGesture: Boolean = false,
        axis: ScrollAxis = ScrollAxis.VERTICAL
    ): Boolean {
        val info = displayInfo ?: return false
        val mapped = CoordinateMapper.mapForRotation(injectAnchorX, injectAnchorY, info)
        val action = if (direction >= 0) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (!preferGesture && axis == ScrollAxis.VERTICAL) {
            val actionTarget = findScrollableTargetAtPoint(info, mapped.x, mapped.y)
            if (actionTarget != null) {
                val success = actionTarget.performAction(action)
                val actionName = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    "forward"
                } else {
                    "backward"
                }
                DiagnosticsLog.add("Scroll: action=$actionName axis=$axis success=$success")
                if (success) return true
            } else {
                DiagnosticsLog.add("Scroll: action target missing at (${mapped.x.toInt()},${mapped.y.toInt()})")
            }
        } else {
            DiagnosticsLog.add("Scroll: prefer gesture injection")
        }
        if (gesturesInFlight > 0) {
            DiagnosticsLog.add("Scroll: swipe skipped (gesture busy)")
            return false
        }
        val safeRect = computeSafeRect(info)
        val clampedX = injectAnchorX.coerceIn(safeRect.left, safeRect.right)
        val clampedY = injectAnchorY.coerceIn(safeRect.top, safeRect.bottom)
        val useMicro = preferGesture && speedMultiplier < SCROLL_SWIPE_MICRO_SPEED_MAX
        val isPullDown = preferGesture && direction < 0 && axis == ScrollAxis.VERTICAL
        val isPushUp = preferGesture && direction >= 0 && axis == ScrollAxis.VERTICAL
        val swipeDistance = computeSwipeDistancePx(
            speedMultiplier,
            safeRect,
            axis = axis,
            minDpOverride = if (isPullDown) {
                SCROLL_PULL_MIN_DP_PRECISION
            } else if (isPushUp) {
                SCROLL_PUSH_MIN_DP_PRECISION
            } else if (useMicro) {
                SCROLL_SWIPE_MIN_DP_MICRO
            } else if (preferGesture) {
                SCROLL_SWIPE_MIN_DP_PRECISION
            } else {
                null
            },
            maxDpOverride = if (isPullDown) {
                SCROLL_PULL_MAX_DP_PRECISION
            } else if (isPushUp) {
                SCROLL_PUSH_MAX_DP_PRECISION
            } else if (useMicro) {
                SCROLL_SWIPE_MAX_DP_MICRO
            } else if (preferGesture) {
                SCROLL_SWIPE_MAX_DP_PRECISION
            } else {
                null
            },
            minSpeedMultiplier = if (preferGesture) 0.2f else 0.6f,
            baseDpOverride = if (isPullDown) {
                SCROLL_PULL_BASE_DP_PRECISION
            } else if (isPushUp) {
                SCROLL_PUSH_BASE_DP_PRECISION
            } else if (useMicro) {
                SCROLL_SWIPE_BASE_DP_MICRO
            } else if (preferGesture) {
                SCROLL_SWIPE_BASE_DP_PRECISION
            } else {
                null
            }
        )
        val half = swipeDistance / 2f
        val startX: Float
        val endX: Float
        val startY: Float
        val endY: Float
        if (axis == ScrollAxis.HORIZONTAL) {
            startY = clampedY
            endY = clampedY
            if (direction >= 0) {
                startX = (clampedX + half).coerceIn(safeRect.left, safeRect.right)
                endX = (clampedX - half).coerceIn(safeRect.left, safeRect.right)
            } else {
                startX = (clampedX - half).coerceIn(safeRect.left, safeRect.right)
                endX = (clampedX + half).coerceIn(safeRect.left, safeRect.right)
            }
        } else {
            startX = clampedX
            endX = clampedX
            if (direction >= 0) {
                startY = (clampedY + half).coerceIn(safeRect.top, safeRect.bottom)
                endY = (clampedY - half).coerceIn(safeRect.top, safeRect.bottom)
            } else {
                startY = (clampedY - half).coerceIn(safeRect.top, safeRect.bottom)
                endY = (clampedY + half).coerceIn(safeRect.top, safeRect.bottom)
            }
        }
        val start = CoordinateMapper.mapForRotation(startX, startY, info)
        val end = CoordinateMapper.mapForRotation(endX, endY, info)
        val duration = if (isPullDown) {
            (computeSwipeDurationMs(speedMultiplier) * SCROLL_PULL_DURATION_SCALE)
                .toLong()
                .coerceIn(SCROLL_PULL_MIN_DURATION_MS, SCROLL_PULL_MAX_DURATION_MS)
        } else if (isPushUp) {
            (computeSwipeDurationMs(speedMultiplier) * SCROLL_PUSH_DURATION_SCALE)
                .toLong()
                .coerceIn(SCROLL_PUSH_MIN_DURATION_MS, SCROLL_PUSH_MAX_DURATION_MS)
        } else {
            computeSwipeDurationMs(speedMultiplier)
        }
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, info.displayId)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGestureTracked(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    DiagnosticsLog.add("Scroll: swipe injected")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    DiagnosticsLog.add("Scroll: swipe cancelled")
                }
            }
        )
        return true
    }

    fun performBack(): Boolean {
        SessionStore.lastBackFailure = null
        val now = SystemClock.uptimeMillis()
        DiagnosticsLog.add("Back: request t=$now")
        DiagnosticsLog.add(
            "Back: gestureInFlight=$gesturesInFlight dragActive=${dragStroke != null} " +
                "scrollActive=${scrollStroke != null}"
        )
        val info = displayInfo
        if (info == null) {
            DiagnosticsLog.add("Back: blocked (no external display)")
            SessionStore.lastBackFailure = "no_display"
            return false
        }
        val snapshot = snapshotWindows()
        if (snapshot.none { it.displayId == info.displayId }) {
            // Some OEM ROMs do not expose external-display windows via Accessibility APIs.
            // Fall back to direct BACK dispatch instead of hard-failing on "no focus".
            DiagnosticsLog.add(
                "Back: no window for external displayId=${info.displayId}, " +
                    "fallback to direct global back"
            )
            return executeBackWithLogging(
                reason = "windowless fallback",
                allowFocusRetry = false,
                bypassExternalFocusCheck = true
            )
        }
        val externalState = resolveExternalWindowState(info, snapshot)
        if (externalState == null || (!externalState.isActive && !externalState.isFocused)) {
            DiagnosticsLog.add(
                "Back: external display not focused before back " +
                    "active=${externalState?.isActive ?: false} " +
                    "focused=${externalState?.isFocused ?: false}"
            )
            cancelDrag()
            cancelScrollGesture()
            if (dispatchFocusActivationGesture(info)) {
                handler.postDelayed({
                    executeBackWithLogging("after focus activation", allowFocusRetry = false)
                }, BACK_FOCUS_DELAY_MS)
                return true
            }
        }
        return executeBackWithLogging("immediate", allowFocusRetry = true)
    }

    private fun executeBackWithLogging(
        reason: String,
        allowFocusRetry: Boolean,
        bypassExternalFocusCheck: Boolean = false
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        DiagnosticsLog.add("Back: execute $reason t=$now")
        DiagnosticsLog.add(
            "Back: gestureInFlight=$gesturesInFlight dragActive=${dragStroke != null} " +
                "scrollActive=${scrollStroke != null}"
        )
        val info = displayInfo
        if (info == null) {
            DiagnosticsLog.add("Back: blocked (no external display)")
            return false
        }
        val snapshot = snapshotWindows()
        dumpWindows("Back: window", snapshot)
        val externalState = resolveExternalWindowState(info, snapshot)
        if (!bypassExternalFocusCheck &&
            (externalState == null || (!externalState.isActive && !externalState.isFocused))
        ) {
            DiagnosticsLog.add(
                "Back: external display not focused at action " +
                    "active=${externalState?.isActive ?: false} " +
                    "focused=${externalState?.isFocused ?: false}"
            )
            if (allowFocusRetry && dispatchFocusActivationGesture(info)) {
                handler.postDelayed({
                    executeBackWithLogging("after focus activation", allowFocusRetry = false)
                }, BACK_FOCUS_DELAY_MS)
                return true
            }
            SessionStore.lastBackFailure = "external_not_focused"
            DiagnosticsLog.add("Back: skipped (external display not focused)")
            return false
        }
        if (bypassExternalFocusCheck) {
            DiagnosticsLog.add("Back: focus check bypassed (windowless external display fallback)")
        }
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        if (!success) {
            SessionStore.lastBackFailure = "dispatch_failed"
        }
        DiagnosticsLog.add("Back: dispatched success=$success")
        return success
    }

    fun showToastOnExternalDisplay(message: String, long: Boolean = false): Boolean {
        val displayContext = overlayWindowContext ?: run {
            val info = displayInfo ?: return false
            val display = getSystemService(DisplayManager::class.java).getDisplay(info.displayId)
                ?: return false
            createDisplayContext(display)
        }
        Toast.makeText(
            displayContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
        return true
    }

    private data class ExternalWindowState(
        val displayId: Int,
        val type: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val packageName: String?
    )

    private fun snapshotWindows(): List<AccessibilityWindowInfo> {
        return windows?.toList().orEmpty()
    }

    private fun resolveExternalWindowState(
        info: DisplaySessionManager.ExternalDisplayInfo,
        windows: List<AccessibilityWindowInfo>
    ): ExternalWindowState? {
        val matches = windows.filter { it.displayId == info.displayId }
        if (matches.isEmpty()) return null
        val preferred = matches.firstOrNull { it.isFocused || it.isActive } ?: matches.first()
        val packageName = preferred.root?.packageName?.toString()
        return ExternalWindowState(
            displayId = preferred.displayId,
            type = preferred.type,
            isActive = matches.any { it.isActive },
            isFocused = matches.any { it.isFocused },
            packageName = packageName
        )
    }

    private fun dumpWindows(tag: String, windows: List<AccessibilityWindowInfo>) {
        if (windows.isEmpty()) {
            DiagnosticsLog.add("$tag: none")
            return
        }
        windows.forEach { window ->
            val packageName = window.root?.packageName?.toString() ?: "none"
            DiagnosticsLog.add(
                "$tag displayId=${window.displayId} type=${window.type} " +
                    "active=${window.isActive} focused=${window.isFocused} root=$packageName"
            )
        }
    }

    private fun dispatchFocusActivationGesture(
        info: DisplaySessionManager.ExternalDisplayInfo
    ): Boolean {
        if (tryTaskFocus(info.displayId)) {
            DiagnosticsLog.add("Back: focus activation via task manager")
            return true
        }
        val targetWindow = pickTopAppWindow(info.displayId)
            ?: windows?.firstOrNull { it.displayId == info.displayId }
        val root = targetWindow?.root ?: run {
            DiagnosticsLog.add("Back: focus activation skipped (no window root)")
            return false
        }
        if (tryFocusAtCursor(root, info)) {
            DiagnosticsLog.add("Back: focus activation via cursor hit")
            return true
        }
        val candidates = collectFocusableNodes(root, maxCount = 3)
        if (candidates.isEmpty()) {
            DiagnosticsLog.add("Back: focus activation skipped (no focusable node)")
            return false
        }
        for (node in candidates) {
            val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (focused) {
                DiagnosticsLog.add("Back: focus activation via node success=true")
                return true
            }
        }
        val rootFocused = root.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            root.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        DiagnosticsLog.add("Back: focus activation via node success=$rootFocused")
        return rootFocused
    }

    private fun tryFocusAtCursor(
        root: AccessibilityNodeInfo,
        info: DisplaySessionManager.ExternalDisplayInfo
    ): Boolean {
        val clamped = clampToDisplay(cursorX, cursorY, info)
        val mapped = CoordinateMapper.mapForRotation(clamped.x, clamped.y, info)
        val hitNode = findNodeAtPoint(root, mapped.x.toInt(), mapped.y.toInt())
        val focusTarget = when {
            hitNode == null -> if (root.isFocusable) root else findFocusableNode(root)
            hitNode.isFocusable -> hitNode
            else -> findFocusableAncestor(hitNode) ?: findFocusableNode(root)
        } ?: return false
        return focusTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            focusTarget.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    private fun tryTaskFocus(displayId: Int): Boolean {
        return try {
            val cls = Class.forName("android.app.ActivityTaskManager")
            val getService = cls.getDeclaredMethod("getService")
            val service = getService.invoke(null) ?: return false
            if (Build.VERSION.SDK_INT >= 34) {
                val focusTopTask = service.javaClass.getMethod("focusTopTask", Int::class.java)
                focusTopTask.invoke(service, displayId)
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val getRoots = service.javaClass.getMethod(
                    "getAllRootTaskInfosOnDisplay",
                    Int::class.java
                )
                val roots = getRoots.invoke(service, displayId) as? List<*> ?: return false
                val first = roots.firstOrNull() ?: return false
                val idField = first.javaClass.getDeclaredField("taskId")
                idField.isAccessible = true
                val taskId = idField.getInt(first)
                val setFocused = service.javaClass.getMethod("setFocusedRootTask", Int::class.java)
                setFocused.invoke(service, taskId)
                return true
            }
            val stackField = service.javaClass.declaredFields.firstOrNull { it.name.contains("stack") }
            if (stackField != null) {
                stackField.isAccessible = true
                val stackId = (stackField.get(service) as? Int) ?: return false
                val setFocused = service.javaClass.getMethod("setFocusedStack", Int::class.java)
                setFocused.invoke(service, stackId)
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun pickTopAppWindow(displayId: Int): AccessibilityWindowInfo? {
        val matches = windows?.filter { it.displayId == displayId }.orEmpty()
        if (matches.isEmpty()) return null
        val appWindows = matches.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val candidates = if (appWindows.isNotEmpty()) appWindows else matches
        return candidates.maxByOrNull { it.layer }
    }

    private fun collectFocusableNodes(
        root: AccessibilityNodeInfo,
        maxCount: Int
    ): List<AccessibilityNodeInfo> {
        val results = ArrayList<AccessibilityNodeInfo>(maxCount)
        if (root.isFocusable && root.isVisibleToUser) {
            results.add(root)
            if (results.size >= maxCount) return results
        }
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && results.size < maxCount && visited < 200) {
            val node = queue.removeFirst()
            visited += 1
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                if (child.isFocusable && child.isVisibleToUser) {
                    results.add(child)
                    if (results.size >= maxCount) return results
                }
                queue.add(child)
            }
        }
        return results
    }

    fun warmUpBackPipeline() {
        if (displayInfo == null) return
        val now = SystemClock.uptimeMillis()
        if (now - SessionStore.lastBackWarmupUptime < WARMUP_MIN_INTERVAL_MS) return
        SessionStore.lastBackWarmupUptime = now
        handler.post {
            if (displayInfo == null) return@post
            // Warm-up input/overlay pipeline to mitigate first-back delay without clicks.
            val originalX = cursorX
            val originalY = cursorY
            moveCursorBy(1f, 0f)
            cursorX = originalX
            cursorY = originalY
            updateOverlayPosition()
        }
    }

    fun hasExternalDisplaySession(): Boolean = displayInfo != null

    fun setTextOnFocused(text: String): Boolean {
        val info = displayInfo ?: return recordInjection(
            false,
            getString(R.string.injection_no_external_display)
        )
        val targetWindows = windows?.filter { it.displayId == info.displayId }.orEmpty()
        val roots = if (targetWindows.isNotEmpty()) {
            targetWindows.mapNotNull { it.root }
        } else {
            listOfNotNull(rootInActiveWindow)
        }
        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val target = focused ?: findEditableNode(root)
            if (target != null) {
                if (target.isFocusable && !target.isFocused) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                if (!target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                    return recordInjection(
                        false,
                        getString(R.string.injection_action_set_text_not_supported)
                    )
                }
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return recordInjection(
                    success,
                    if (success) {
                        getString(R.string.injection_action_set_text_success)
                    } else {
                        getString(R.string.injection_action_set_text_failed)
                    }
                )
            }
        }
        return recordInjection(false, getString(R.string.injection_no_editable_field))
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun attachToDisplay(
        info: DisplaySessionManager.ExternalDisplayInfo?,
        allowRetry: Boolean = true
    ) {
        if (info == null) {
            detachOverlay()
            cancelAttachRetry()
            return
        }
        if (displayInfo?.displayId == info.displayId && overlayView != null) {
            return
        }
        detachOverlay()
        displayInfo = info
        DiagnosticsLog.add("Accessibility: attach displayId=${info.displayId}")
        cursorBaseSizePx = cursorBaseSizeForDisplay(info)
        cursorSizePx = cursorMaxSizeForDisplay(cursorBaseSizePx)
        cursorX = (info.width / 2f)
        cursorY = (info.height / 2f)

        val display = getSystemService(DisplayManager::class.java).getDisplay(info.displayId)
        if (display == null) {
            DiagnosticsLog.add("Accessibility: attach deferred (display missing) id=${info.displayId}")
            if (allowRetry) {
                scheduleAttachRetry(info)
            }
            return
        }
        val windowContext = if (Build.VERSION.SDK_INT >= 30) {
            try {
                createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    null
                )
            } catch (e: NoSuchMethodError) {
                createDisplayContext(display)
            }
        } else {
            createDisplayContext(display)
        }
        overlayWindowContext = windowContext
        val wm = windowContext.getSystemService(WindowManager::class.java)
        windowManager = wm

        if (SettingsStore.switchBarEnabled) {
            switchBarController = SwitchBarController(
                this,
                windowContext,
                wm,
                info
            )
        }

        val view = CursorOverlayView(windowContext)
        overlayView = view
        cursorVisible = true
        view.alpha = SettingsStore.cursorAlpha
        view.setBaseSizePx(cursorBaseSizePx)
        view.setArrowColor(SettingsStore.cursorColor)

        val params = WindowManager.LayoutParams(
            cursorSizePx,
            cursorSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val tipOffset = cursorTipOffsetPx()
        params.x = (cursorX - tipOffset.x).toInt()
        params.y = (cursorY - tipOffset.y).toInt()
        runCatching { wm.addView(view, params) }.onFailure {
            detachOverlay()
            if (allowRetry) {
                DiagnosticsLog.add("Accessibility: attach failed, retrying id=${info.displayId}")
                scheduleAttachRetry(info)
            }
        }
        scheduleCursorHide()
        cancelAttachRetry()
    }

    private fun scheduleAttachRetry(info: DisplaySessionManager.ExternalDisplayInfo) {
        if (attachRetryInfo?.displayId == info.displayId && attachRetryRunnable != null) return
        attachRetryInfo = info
        attachRetryCount = 0
        attachRetryRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val currentInfo = attachRetryInfo ?: return
                attachRetryCount += 1
                attachToDisplay(currentInfo, allowRetry = false)
                if (overlayView == null && attachRetryCount < ATTACH_RETRY_MAX) {
                    handler.postDelayed(this, ATTACH_RETRY_DELAY_MS)
                } else {
                    if (overlayView == null) {
                        DiagnosticsLog.add(
                            "Accessibility: attach retry exhausted id=${currentInfo.displayId}"
                        )
                    }
                    cancelAttachRetry()
                }
            }
        }
        attachRetryRunnable = runnable
        handler.postDelayed(runnable, ATTACH_RETRY_DELAY_MS)
    }

    private fun cancelAttachRetry() {
        attachRetryRunnable?.let { handler.removeCallbacks(it) }
        attachRetryRunnable = null
        attachRetryInfo = null
        attachRetryCount = 0
    }

    private fun detachOverlay() {
        cancelAttachRetry()
        switchBarController?.teardown()
        switchBarController = null
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        windowManager = null
        overlayWindowContext = null
        displayInfo = null
        cancelDrag()
        cancelCursorHide()
        DiagnosticsLog.add("Accessibility: overlay detached")
    }

    private fun refreshSwitchBarSettings() {
        val info = displayInfo ?: return
        val wm = windowManager ?: return
        val context = overlayWindowContext ?: return
        if (!SettingsStore.switchBarEnabled) {
            switchBarController?.teardown()
            switchBarController = null
            return
        }
        if (switchBarController == null) {
            switchBarController = SwitchBarController(
                this,
                context,
                wm,
                info
            )
        } else {
            switchBarController?.refreshScale()
            switchBarController?.refreshItems()
        }
    }

    private fun setSwitchBarForceVisible(enabled: Boolean) {
        switchBarController?.setForceVisible(enabled)
    }

    private fun cursorBaseSizeForDisplay(info: DisplaySessionManager.ExternalDisplayInfo): Int {
        val minDim = min(info.width, info.height).toFloat()
        val size = (minDim * 0.012f * SettingsStore.cursorScale).toInt()
        return size.coerceIn(10, 26)
    }

    private fun cursorMaxSizeForDisplay(baseSize: Int): Int {
        return (baseSize * CursorOverlayView.MAX_SCALE).toInt().coerceAtLeast(baseSize)
    }

    private fun clampToDisplay(
        x: Float,
        y: Float,
        info: DisplaySessionManager.ExternalDisplayInfo
    ): PointF {
        val clampedX = x.coerceIn(0f, info.width.toFloat())
        val clampedY = y.coerceIn(0f, info.height.toFloat())
        return PointF(clampedX, clampedY)
    }

    private fun cursorTipOffsetPx(): PointF {
        val offsetX = cursorSizePx * CURSOR_TIP_FRACTION_X
        val offsetY = cursorSizePx * CURSOR_TIP_FRACTION_Y
        return PointF(offsetX, offsetY)
    }
    private fun updateOverlayPosition() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        val tipOffset = cursorTipOffsetPx()
        params.x = (cursorX - tipOffset.x).toInt()
        params.y = (cursorY - tipOffset.y).toInt()
        wm.updateViewLayout(view, params)
    }

    private fun notifyCursorSpeed(dx: Float, dy: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMoveTime == 0L) 0L else now - lastMoveTime
        lastMoveTime = now
        overlayView?.onCursorMoved(dx, dy, dt)
    }

    private fun notifyCursorActivity() {
        showCursor()
        scheduleCursorHide()
    }

    private fun scheduleCursorHide() {
        val delay = SettingsStore.cursorHideDelayMs
        cancelCursorHide()
        if (forceCursorVisible) return
        if (delay <= 0L) return
        hideRunnable = Runnable { hideCursor() }
        handler.postDelayed(hideRunnable!!, delay)
    }

    private fun cancelCursorHide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun showCursor() {
        val view = overlayView ?: return
        if (!cursorVisible) {
            cursorVisible = true
            view.alpha = SettingsStore.cursorAlpha
        }
    }

    private fun hideCursor() {
        val view = overlayView ?: return
        cursorVisible = false
        view.alpha = 0f
    }

    private fun setCursorForceVisible(enabled: Boolean) {
        forceCursorVisible = enabled
        if (enabled) {
            cancelCursorHide()
            showCursor()
        } else {
            scheduleCursorHide()
        }
    }

    private fun refreshCursorAppearance() {
        val info = displayInfo ?: return
        cursorBaseSizePx = cursorBaseSizeForDisplay(info)
        cursorSizePx = cursorMaxSizeForDisplay(cursorBaseSizePx)
        overlayView?.alpha = if (cursorVisible) SettingsStore.cursorAlpha else 0f
        overlayView?.let { view ->
            view.setBaseSizePx(cursorBaseSizePx)
            view.setArrowColor(SettingsStore.cursorColor)
            val wm = windowManager ?: return
            val params = view.layoutParams as WindowManager.LayoutParams
            params.width = cursorSizePx
            params.height = cursorSizePx
            val tipOffset = cursorTipOffsetPx()
            params.x = (cursorX - tipOffset.x).toInt()
            params.y = (cursorY - tipOffset.y).toInt()
            wm.updateViewLayout(view, params)
        }
    }

    private fun dispatchTap(x: Float, y: Float, displayId: Int) {
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGestureTracked(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_tap_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_tap_cancelled))
                }
            }
        )
    }

    private fun dispatchDragStroke(
        stroke: GestureDescription.StrokeDescription,
        displayId: Int
    ) {
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(stroke)
        dispatchGestureTracked(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_drag_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_drag_cancelled))
                }
            }
        )
    }

    private fun dispatchScrollStroke(
        stroke: GestureDescription.StrokeDescription,
        displayId: Int
    ) {
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(stroke)
        dispatchGestureTracked(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_scroll_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_scroll_failed))
                }
            }
        )
    }

    private fun dispatchGestureTracked(
        description: GestureDescription,
        callback: GestureResultCallback
    ) {
        gesturesInFlight += 1
        val accepted = dispatchGesture(
            description,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gesturesInFlight = (gesturesInFlight - 1).coerceAtLeast(0)
                    if (gesturesInFlight == 0 && pendingScrollEnd) {
                        val info = displayInfo
                        val stroke = scrollStroke
                        if (info != null && stroke != null) {
                            pendingScrollEnd = false
                            endScrollGestureInternal(info, stroke, pendingScrollEndX, pendingScrollEndY)
                        } else {
                            pendingScrollEnd = false
                        }
                    }
                    callback.onCompleted(gestureDescription)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesInFlight = (gesturesInFlight - 1).coerceAtLeast(0)
                    if (gesturesInFlight == 0 && pendingScrollEnd) {
                        val info = displayInfo
                        val stroke = scrollStroke
                        if (info != null && stroke != null) {
                            pendingScrollEnd = false
                            endScrollGestureInternal(info, stroke, pendingScrollEndX, pendingScrollEndY)
                        } else {
                            pendingScrollEnd = false
                        }
                    }
                    callback.onCancelled(gestureDescription)
                }
            },
            null
        )
        if (!accepted) {
            gesturesInFlight = (gesturesInFlight - 1).coerceAtLeast(0)
            callback.onCancelled(null)
        }
    }

    private fun dispatchScrollGesture(
        steps: Int,
        stepSizePx: Float,
        info: DisplaySessionManager.ExternalDisplayInfo
    ) {
        val absSteps = abs(steps)
        if (absSteps == 0) return
        val density = densityFor(info)
        val minDistance = 8f * density
        val distancePerStep = stepSizePx.coerceAtLeast(minDistance)
        val maxDistance = 320f * density
        val margin = 24f * density
        val distance = (distancePerStep * absSteps).coerceAtMost(maxDistance)
        val startX = cursorX.coerceIn(margin, info.width - margin)
        val startY = cursorY.coerceIn(margin, info.height - margin)
        val endY = if (steps >= 0) {
            (startY - distance).coerceAtLeast(margin)
        } else {
            (startY + distance).coerceAtMost(info.height - margin)
        }
        if (abs(endY - startY) < 1f) {
            recordInjection(false, getString(R.string.injection_scroll_failed))
            return
        }
        val start = CoordinateMapper.mapForRotation(startX, startY, info)
        val end = CoordinateMapper.mapForRotation(startX, endY, info)
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, info.displayId)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 180))
        dispatchGestureTracked(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_scroll_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_scroll_failed))
                }
            }
        )
    }

    private data class SafeRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val insetsLeft: Int,
        val insetsTop: Int,
        val insetsRight: Int,
        val insetsBottom: Int
    )

    private data class Insets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun computeSafeRect(info: DisplaySessionManager.ExternalDisplayInfo): SafeRect {
        val insets = resolveDisplayInsets()
        val density = densityFor(info)
        val left = insets.left + (SCROLL_SAFE_PAD_X_DP * density)
        val right = info.width - insets.right - (SCROLL_SAFE_PAD_X_DP * density)
        val top = insets.top + (SCROLL_SAFE_PAD_TOP_DP * density)
        val bottom = info.height - insets.bottom - (SCROLL_SAFE_PAD_BOTTOM_DP * density)
        var safeLeft = left
        var safeRight = right
        if (safeRight < safeLeft) {
            val mid = info.width / 2f
            safeLeft = mid
            safeRight = mid
        }
        var safeTop = top
        var safeBottom = bottom
        if (safeBottom < safeTop) {
            val mid = info.height / 2f
            safeTop = mid
            safeBottom = mid
        }
        return SafeRect(
            left = safeLeft,
            top = safeTop,
            right = safeRight,
            bottom = safeBottom,
            insetsLeft = insets.left,
            insetsTop = insets.top,
            insetsRight = insets.right,
            insetsBottom = insets.bottom
        )
    }

    private fun resolveDisplayInsets(): Insets {
        val windowInsets = overlayView?.rootWindowInsets ?: return Insets(0, 0, 0, 0)
        return if (Build.VERSION.SDK_INT >= 30) {
            val sys = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            Insets(sys.left, sys.top, sys.right, sys.bottom)
        } else {
            @Suppress("DEPRECATION")
            Insets(
                windowInsets.systemWindowInsetLeft,
                windowInsets.systemWindowInsetTop,
                windowInsets.systemWindowInsetRight,
                windowInsets.systemWindowInsetBottom
            )
        }
    }

    private fun computeSwipeDistancePx(
        speedMultiplier: Float,
        safeRect: SafeRect,
        axis: ScrollAxis = ScrollAxis.VERTICAL,
        minDpOverride: Float? = null,
        maxDpOverride: Float? = null,
        minSpeedMultiplier: Float = 0.6f,
        baseDpOverride: Float? = null
    ): Float {
        val density = resources.displayMetrics.density
        val baseDp = baseDpOverride ?: SCROLL_SWIPE_BASE_DP
        val base = baseDp * density * speedMultiplier.coerceIn(minSpeedMultiplier, 2.0f)
        val minDp = minDpOverride ?: SCROLL_SWIPE_MIN_DP
        val min = minDp * density
        val maxDp = maxDpOverride ?: SCROLL_SWIPE_MAX_DP
        val max = maxDp * density
        val clamped = base.coerceIn(min, max)
        val maxAllowed = if (axis == ScrollAxis.HORIZONTAL) {
            (safeRect.right - safeRect.left) * 0.8f
        } else {
            (safeRect.bottom - safeRect.top) * 0.8f
        }
        return clamped.coerceAtMost(maxAllowed)
    }

    private fun densityFor(info: DisplaySessionManager.ExternalDisplayInfo): Float {
        val density = info.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
        return density.coerceIn(MIN_SCROLL_DENSITY, 4.0f)
    }

    private fun computeSwipeDurationMs(speedMultiplier: Float): Long {
        val scaled = (SCROLL_SWIPE_BASE_DURATION_MS / speedMultiplier.coerceIn(0.6f, 2.0f))
        return scaled.toLong().coerceIn(SCROLL_SWIPE_MIN_DURATION_MS, SCROLL_SWIPE_MAX_DURATION_MS)
    }

    private fun findScrollableTargetAtPoint(
        info: DisplaySessionManager.ExternalDisplayInfo,
        x: Float,
        y: Float
    ): AccessibilityNodeInfo? {
        val targetWindows = windows?.filter { it.displayId == info.displayId }.orEmpty()
        val window = targetWindows.firstOrNull { it.isFocused || it.isActive }
            ?: targetWindows.firstOrNull()
        val root = window?.root ?: return null
        val hitNode = findNodeAtPoint(root, x.toInt(), y.toInt())
        var node: AccessibilityNodeInfo? = hitNode ?: root
        while (node != null) {
            if (node.isScrollable && node.isVisibleToUser) {
                return node
            }
            node = node.parent
        }
        return findScrollableNode(root)
    }

    private fun findNodeAtPoint(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var best: AccessibilityNodeInfo? = null
        var bestDepth = -1
        val rect = Rect()
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y)) {
                if (depth >= bestDepth) {
                    best = node
                    bestDepth = depth
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return best
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.isVisibleToUser) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findFocusableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isFocusable && node.isVisibleToUser) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findFocusableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isFocusable && current.isVisibleToUser) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun recordInjection(success: Boolean, message: String): Boolean {
        SessionStore.lastInjectionResult = if (success) {
            message
        } else {
            getString(R.string.injection_failed_with_message, message)
        }
        return success
    }

    private fun trySetDisplayId(builder: GestureDescription.Builder, displayId: Int) {
        try {
            val method = builder.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            method.invoke(builder, displayId)
        } catch (ignored: Exception) {
            // Not supported on this API level.
        }
    }
}
