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
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

class ControlAccessibilityService : AccessibilityService() {

    companion object {
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
    }

    private var overlayView: CursorOverlayView? = null
    private var windowManager: WindowManager? = null
    private var displayInfo: DisplaySessionManager.ExternalDisplayInfo? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorSizePx = 24

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cursorSizePx = (resources.displayMetrics.density * 18f).toInt().coerceAtLeast(12)
        attachToDisplay(pendingDisplayInfo)
    }

    override fun onDestroy() {
        detachOverlay()
        instance = null
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
        updateOverlayPosition()
    }

    fun tapAtCursor() {
        val info = displayInfo ?: return
        val mapped = CoordinateMapper.mapForRotation(cursorX, cursorY, info)
        dispatchTap(mapped.x, mapped.y, info.displayId)
    }

    fun dragCursor(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val info = displayInfo ?: return
        val start = CoordinateMapper.mapForRotation(fromX, fromY, info)
        val end = CoordinateMapper.mapForRotation(toX, toY, info)
        dispatchDrag(start.x, start.y, end.x, end.y, info.displayId)
    }

    fun setTextOnFocused(text: String): Boolean {
        val info = displayInfo ?: return recordInjection(false, "No external display")
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
                    return recordInjection(false, "Focused field does not support ACTION_SET_TEXT")
                }
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return recordInjection(success, if (success) "ACTION_SET_TEXT success" else "ACTION_SET_TEXT failed")
            }
        }
        return recordInjection(false, "No editable field on external display")
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
        cursorX = (info.width / 2f)
        cursorY = (info.height / 2f)

        val display = getSystemService(DisplayManager::class.java).getDisplay(info.displayId)
            ?: return
        val windowContext = createWindowContext(
            display,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
        val wm = windowContext.getSystemService(WindowManager::class.java)
        windowManager = wm

        val view = CursorOverlayView(windowContext)
        overlayView = view

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
        params.x = (cursorX - cursorSizePx / 2f).toInt()
        params.y = (cursorY - cursorSizePx / 2f).toInt()
        runCatching { wm.addView(view, params) }.onFailure {
            detachOverlay()
        }
    }

    private fun detachOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        windowManager = null
        displayInfo = null
    }

    private fun updateOverlayPosition() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = (cursorX - cursorSizePx / 2f).toInt()
        params.y = (cursorY - cursorSizePx / 2f).toInt()
        wm.updateViewLayout(view, params)
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
                    recordInjection(true, "Tap injected")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, "Tap cancelled")
                }
            },
            null
        )
    }

    private fun dispatchDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        displayId: Int
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val builder = GestureDescription.Builder()
        trySetDisplayId(builder, displayId)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 120))
        dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    recordInjection(true, "Drag injected")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    recordInjection(false, "Drag cancelled")
                }
            },
            null
        )
    }

    private fun recordInjection(success: Boolean, message: String): Boolean {
        SessionStore.lastInjectionResult = if (success) message else "Failed: $message"
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
