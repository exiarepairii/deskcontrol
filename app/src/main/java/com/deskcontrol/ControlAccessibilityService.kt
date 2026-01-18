package com.deskcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.min

class ControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val WARMUP_MIN_INTERVAL_MS = 15_000L
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
    private val dragStartDurationMs = 8L
    private val dragSegmentDurationMs = 16L
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var cursorVisible = true
    private var forceCursorVisible = false
    private var lastMoveTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        val maxX = info.width.toFloat()
        val maxY = info.height.toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        notifyCursorActivity()
        notifyCursorSpeed(dx, dy)
        updateOverlayPosition()
        switchBarController?.onCursorMoved(cursorX, cursorY)
    }

    fun wakeCursor() {
        notifyCursorActivity()
    }

    fun tapAtCursor() {
        val info = displayInfo ?: return
        val mapped = CoordinateMapper.mapForRotation(cursorX, cursorY, info)
        notifyCursorActivity()
        dispatchTap(mapped.x, mapped.y, info.displayId)
    }

    fun startDragAtCursor() {
        val info = displayInfo ?: return
        val mapped = CoordinateMapper.mapForRotation(cursorX, cursorY, info)
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
        val mapped = CoordinateMapper.mapForRotation(cursorX, cursorY, info)
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
        val mapped = CoordinateMapper.mapForRotation(cursorX, cursorY, info)
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
        val margin = 24f * resources.displayMetrics.density
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
        val activeStroke = scrollStroke ?: return
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

    fun endScrollGesture() {
        val info = displayInfo ?: return
        val activeStroke = scrollStroke ?: return
        val mapped = CoordinateMapper.mapForRotation(scrollPointX, scrollPointY, info)
        val path = Path().apply {
            moveTo(mapped.x, mapped.y)
            lineTo(mapped.x, mapped.y)
        }
        val stroke = activeStroke.continueStroke(path, 0, dragSegmentDurationMs, false)
        scrollStroke = null
        notifyCursorActivity()
        dispatchScrollStroke(stroke, info.displayId)
    }

    fun cancelScrollGesture() {
        scrollStroke = null
    }

    private fun resolveScrollAnchor(
        info: DisplaySessionManager.ExternalDisplayInfo,
        x: Float,
        y: Float
    ): Pair<Float, Float> {
        val margin = 24f * resources.displayMetrics.density
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

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun warmUpBackPipeline() {
        val info = displayInfo ?: return
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

    private fun attachToDisplay(info: DisplaySessionManager.ExternalDisplayInfo?) {
        if (info == null) {
            detachOverlay()
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
            ?: return
        val windowContext = createWindowContext(
            display,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
        overlayWindowContext = windowContext
        val wm = windowContext.getSystemService(WindowManager::class.java)
        windowManager = wm

        if (SettingsStore.switchBarEnabled) {
            switchBarController = SwitchBarController(this, windowContext, wm, info)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = cursorX.toInt()
        params.y = cursorY.toInt()
        runCatching { wm.addView(view, params) }.onFailure {
            detachOverlay()
        }
        scheduleCursorHide()
    }

    private fun detachOverlay() {
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
            switchBarController = SwitchBarController(this, context, wm, info)
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

    private fun updateOverlayPosition() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = cursorX.toInt()
        params.y = cursorY.toInt()
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
            params.x = cursorX.toInt()
            params.y = cursorY.toInt()
            wm.updateViewLayout(view, params)
        }
    }

    private fun dispatchTap(x: Float, y: Float, displayId: Int) {
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_tap_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_tap_cancelled))
                }
            },
            null
        )
    }

    private fun dispatchDragStroke(
        stroke: GestureDescription.StrokeDescription,
        displayId: Int
    ) {
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(stroke)
        dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_drag_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_drag_cancelled))
                }
            },
            null
        )
    }

    private fun dispatchScrollStroke(
        stroke: GestureDescription.StrokeDescription,
        displayId: Int
    ) {
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(stroke)
        dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_scroll_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_scroll_failed))
                }
            },
            null
        )
    }

    private fun dispatchScrollGesture(
        steps: Int,
        stepSizePx: Float,
        info: DisplaySessionManager.ExternalDisplayInfo
    ) {
        val absSteps = abs(steps)
        if (absSteps == 0) return
        val density = resources.displayMetrics.density
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
        dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, getString(R.string.injection_scroll_injected))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, getString(R.string.injection_scroll_failed))
                }
            },
            null
        )
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
