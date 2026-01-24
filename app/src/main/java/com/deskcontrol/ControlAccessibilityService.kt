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
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

class ControlAccessibilityService : AccessibilityService() {

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
        private const val SCROLL_SWIPE_BASE_DURATION_MS = 45L
        private const val SCROLL_SWIPE_MIN_DURATION_MS = 35L
        private const val SCROLL_SWIPE_MAX_DURATION_MS = 60L
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
        speedMultiplier: Float
    ): Boolean {
        val info = displayInfo ?: return false
        val mapped = CoordinateMapper.mapForRotation(injectAnchorX, injectAnchorY, info)
        val action = if (direction >= 0) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val actionTarget = findScrollableTargetAtPoint(info, mapped.x, mapped.y)
        if (actionTarget != null) {
            val success = actionTarget.performAction(action)
            DiagnosticsLog.add("Scroll: action=${if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "forward" else "back"} success=$success")
            if (success) return true
        } else {
            DiagnosticsLog.add("Scroll: action target missing at (${mapped.x.toInt()},${mapped.y.toInt()})")
        }
        if (gesturesInFlight > 0) {
            DiagnosticsLog.add("Scroll: swipe skipped (gesture busy)")
            return false
        }
        val safeRect = computeSafeRect(info)
        val clampedX = injectAnchorX.coerceIn(safeRect.left, safeRect.right)
        val clampedY = injectAnchorY.coerceIn(safeRect.top, safeRect.bottom)
        val swipeDistance = computeSwipeDistancePx(speedMultiplier, safeRect)
        val half = swipeDistance / 2f
        val startY: Float
        val endY: Float
        if (direction >= 0) {
            startY = (clampedY + half).coerceIn(safeRect.top, safeRect.bottom)
            endY = (clampedY - half).coerceIn(safeRect.top, safeRect.bottom)
        } else {
            startY = (clampedY - half).coerceIn(safeRect.top, safeRect.bottom)
            endY = (clampedY + half).coerceIn(safeRect.top, safeRect.bottom)
        }
        val start = CoordinateMapper.mapForRotation(clampedX, startY, info)
        val end = CoordinateMapper.mapForRotation(clampedX, endY, info)
        val duration = computeSwipeDurationMs(speedMultiplier)
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
            DiagnosticsLog.add("Back: no window for external displayId=${info.displayId}")
            SessionStore.lastBackFailure = "external_window_missing"
            return false
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

    private fun executeBackWithLogging(reason: String, allowFocusRetry: Boolean): Boolean {
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
        if (externalState == null || (!externalState.isActive && !externalState.isFocused)) {
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
        val mapped = CoordinateMapper.mapForRotation(cursorX, cursorY, info)
        val targetWindow = windows
            ?.filter { it.displayId == info.displayId }
            ?.firstOrNull { it.isFocused || it.isActive }
            ?: windows?.firstOrNull { it.displayId == info.displayId }
        val root = targetWindow?.root ?: run {
            DiagnosticsLog.add("Back: focus activation skipped (no window root)")
            return false
        }
        val hitNode = findNodeAtPoint(root, mapped.x.toInt(), mapped.y.toInt())
        val focusTarget = when {
            hitNode == null -> if (root.isFocusable) root else findFocusableNode(root)
            hitNode.isFocusable -> hitNode
            else -> findFocusableAncestor(hitNode) ?: findFocusableNode(root)
        }
        if (focusTarget == null) {
            DiagnosticsLog.add("Back: focus activation skipped (no focusable node)")
            return false
        }
        val focused = focusTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            focusTarget.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        DiagnosticsLog.add(
            "Back: focus activation via node success=$focused at=(${mapped.x.toInt()},${mapped.y.toInt()})"
        )
        return focused
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
                    callback.onCompleted(gestureDescription)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesInFlight = (gesturesInFlight - 1).coerceAtLeast(0)
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
        val density = resources.displayMetrics.density
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
            Insets(
                windowInsets.systemWindowInsetLeft,
                windowInsets.systemWindowInsetTop,
                windowInsets.systemWindowInsetRight,
                windowInsets.systemWindowInsetBottom
            )
        }
    }

    private fun computeSwipeDistancePx(speedMultiplier: Float, safeRect: SafeRect): Float {
        val density = resources.displayMetrics.density
        val base = SCROLL_SWIPE_BASE_DP * density * speedMultiplier.coerceIn(0.6f, 2.0f)
        val min = SCROLL_SWIPE_MIN_DP * density
        val max = SCROLL_SWIPE_MAX_DP * density
        val clamped = base.coerceIn(min, max)
        val maxAllowed = (safeRect.bottom - safeRect.top) * 0.8f
        return clamped.coerceAtMost(maxAllowed)
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
        var node = hitNode ?: root
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
