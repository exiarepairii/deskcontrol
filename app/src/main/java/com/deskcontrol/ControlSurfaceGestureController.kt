package com.deskcontrol

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shared touch interpreter for every control surface.
 *
 * Touchpad uses [SinglePointerMode.POINTER] so one finger moves the cursor and a
 * long press starts a drag. Motion Mouse uses [SinglePointerMode.GESTURE_ONLY]: the
 * ray owns the cursor position, while a tap or swipe is injected relative to the
 * current cursor instead of mapping the phone touch position to the display.
 */
class ControlSurfaceGestureController(
    context: Context,
    private val handler: Handler,
    private val controlArea: View,
    private val touchpadSizeProvider: () -> Pair<Int, Int>,
    private val serviceProvider: () -> ControlAccessibilityService?,
    private val singlePointerMode: SinglePointerMode,
    private val onServiceUnavailable: () -> Unit = {},
    private val onTap: () -> Unit = {},
    private val onDirectGestureStarted: () -> Unit = {},
    private val onTouchActiveChanged: (Boolean) -> Unit = {}
) {
    enum class SinglePointerMode {
        POINTER,
        GESTURE_ONLY
    }

    private enum class TouchState {
        IDLE,
        ONE_FINGER_DOWN,
        MOVING_CURSOR,
        DRAGGING,
        DIRECT_GESTURE,
        SCROLL_MODE
    }

    private enum class ActiveScrollController {
        NONE,
        LEGACY,
        DIRECT
    }

    private val processor = TouchpadProcessor(TouchpadTuning)
    private val touchSlopPx = context.resources.displayMetrics.density * TOUCH_SLOP_DP
    private val longPressCancelSlopPx =
        context.resources.displayMetrics.density * LONG_PRESS_CANCEL_DP
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()
    private val directTouchStartDelay = ViewConfiguration.getTapTimeout().toLong()
    private val legacyScrollController = LegacyScrollController(
        context = context,
        handler = handler,
        serviceProvider = serviceProvider
    )
    private val directScrollController = DirectScrollController(
        context = context,
        touchpadSizeProvider = touchpadSizeProvider,
        serviceProvider = serviceProvider
    )

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var directStartCursor = PointF()
    private var longPressRunnable: Runnable? = null
    private var directTouchStartRunnable: Runnable? = null
    private var activeScrollController = ActiveScrollController.NONE
    private var touchState = TouchState.IDLE
    private var suppressSingleUntilUp = false
    private var directGestureMoved = false
    private var directLongPressTriggered = false
    private var directGestureFeedbackSent = false

    var isTouchActive: Boolean = false
        private set

    fun handle(event: MotionEvent): Boolean {
        val service = serviceProvider()
        if (service == null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onServiceUnavailable()
            }
            cancel()
            return true
        }
        if (suppressSingleUntilUp) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    suppressSingleUntilUp = false
                    touchState = TouchState.IDLE
                    setTouchActive(false)
                    service.reportControlTutorialAction(ControlTutorialAction.GESTURE_END)
                    return true
                }
                MotionEvent.ACTION_DOWN -> suppressSingleUntilUp = false
                else -> return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(service, event)
            MotionEvent.ACTION_POINTER_DOWN -> beginScroll(service, event)
            MotionEvent.ACTION_MOVE -> updateTouch(service, event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchState == TouchState.SCROLL_MODE && event.pointerCount <= 2) {
                    exitScrollMode()
                    suppressSingleUntilUp = true
                }
            }
            MotionEvent.ACTION_UP -> endTouch(service, event)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    fun finishActiveGesture() {
        val service = serviceProvider()
        cancelDirectTouchStart()
        cancelLongPress()
        when (touchState) {
            TouchState.DRAGGING -> service?.endDragAtCursor()
            TouchState.DIRECT_GESTURE -> service?.endContinuousGesture()
            TouchState.SCROLL_MODE -> exitScrollMode()
            else -> Unit
        }
        service?.reportControlTutorialAction(ControlTutorialAction.GESTURE_END)
        resetState()
    }

    fun cancel() {
        val service = serviceProvider()
        cancelDirectTouchStart()
        cancelLongPress()
        when (touchState) {
            TouchState.DRAGGING -> service?.cancelDrag()
            TouchState.DIRECT_GESTURE -> service?.cancelContinuousGesture()
            TouchState.SCROLL_MODE -> exitScrollMode()
            else -> Unit
        }
        service?.reportControlTutorialAction(ControlTutorialAction.GESTURE_END)
        resetState()
    }

    private fun beginTouch(service: ControlAccessibilityService, event: MotionEvent) {
        processor.reset()
        downX = event.x
        downY = event.y
        lastTouchX = event.x
        lastTouchY = event.y
        directStartCursor = service.getCursorPosition()
        touchState = TouchState.ONE_FINGER_DOWN
        directGestureMoved = false
        directLongPressTriggered = false
        directGestureFeedbackSent = false
        setTouchActive(true)
        when (singlePointerMode) {
            SinglePointerMode.POINTER -> scheduleLongPress(service)
            SinglePointerMode.GESTURE_ONLY -> scheduleDirectTouchStart(service)
        }
        service.wakeCursor()
    }

    private fun beginScroll(service: ControlAccessibilityService, event: MotionEvent) {
        if (event.pointerCount < 2) return
        cancelDirectTouchStart()
        cancelLongPress()
        when (touchState) {
            TouchState.DRAGGING -> service.endDragAtCursor()
            TouchState.DIRECT_GESTURE -> service.endContinuousGesture()
            else -> Unit
        }
        touchState = TouchState.SCROLL_MODE
        val useDirect = SettingsStore.touchpadDirectScrollGestureEnabled &&
            directScrollController.enter(service, event)
        activeScrollController = if (useDirect) {
            ActiveScrollController.DIRECT
        } else {
            legacyScrollController.enter(service, event)
            ActiveScrollController.LEGACY
        }
    }

    private fun updateTouch(service: ControlAccessibilityService, event: MotionEvent) {
        if (touchState == TouchState.SCROLL_MODE && event.pointerCount >= 2) {
            when (activeScrollController) {
                ActiveScrollController.DIRECT -> directScrollController.update(event)
                ActiveScrollController.LEGACY -> legacyScrollController.update(event)
                ActiveScrollController.NONE -> Unit
            }
            service.reportControlTutorialAction(ControlTutorialAction.SCROLL)
            return
        }
        if (event.pointerCount != 1) return
        when (singlePointerMode) {
            SinglePointerMode.POINTER -> updatePointerTouch(service, event)
            SinglePointerMode.GESTURE_ONLY -> updateDirectGesture(service, event)
        }
        lastTouchX = event.x
        lastTouchY = event.y
    }

    private fun updatePointerTouch(
        service: ControlAccessibilityService,
        event: MotionEvent
    ) {
        val dx = event.x - lastTouchX
        val dy = event.y - lastTouchY
        val output = processor.process(dx, dy, event.eventTime)
        if (output.dx != 0f || output.dy != 0f) {
            val boost = if (touchState == TouchState.DRAGGING) {
                TouchpadTuning.dragBoost
            } else {
                1f
            }
            service.moveCursorBy(output.dx * boost, output.dy * boost)
            if (touchState == TouchState.DRAGGING) {
                service.updateDragToCursor()
                service.reportControlTutorialAction(ControlTutorialAction.DRAG)
            }
        }
        if (touchState != TouchState.ONE_FINGER_DOWN) return
        val movedForLongPress = abs(event.x - downX) > longPressCancelSlopPx ||
            abs(event.y - downY) > longPressCancelSlopPx
        if (movedForLongPress) {
            cancelLongPress()
        }
        if (hasMovedPastSlop(event)) {
            cancelLongPress()
            touchState = TouchState.MOVING_CURSOR
        }
    }

    private fun updateDirectGesture(
        service: ControlAccessibilityService,
        event: MotionEvent
    ) {
        if (touchState == TouchState.DIRECT_GESTURE) {
            if (!hasMovedPastSlop(event)) {
                if (hasMovedPastLongPressSlop(event)) {
                    cancelLongPress()
                }
                return
            }
            if (!directGestureMoved) {
                directGestureMoved = true
                cancelLongPress()
                service.reportControlTutorialAction(
                    if (directLongPressTriggered) {
                        ControlTutorialAction.DRAG
                    } else {
                        ControlTutorialAction.SWIPE
                    }
                )
                notifyDirectGestureFeedback()
            }
            val target = relativeGestureTarget(event)
            service.updateContinuousGestureTo(target.x, target.y)
            return
        }
        if (touchState == TouchState.ONE_FINGER_DOWN &&
            hasMovedPastLongPressSlop(event)
        ) {
            cancelDirectTouchStart()
        }
        val moved = hasMovedPastSlop(event)
        if (touchState == TouchState.ONE_FINGER_DOWN && moved) {
            touchState = if (service.startContinuousGestureAtCursor()) {
                directGestureMoved = true
                service.reportControlTutorialAction(ControlTutorialAction.SWIPE)
                notifyDirectGestureFeedback()
                TouchState.DIRECT_GESTURE
            } else {
                TouchState.MOVING_CURSOR
            }
        }
        if (touchState != TouchState.DIRECT_GESTURE) return
        val target = relativeGestureTarget(event)
        service.updateContinuousGestureTo(target.x, target.y)
    }

    private fun endTouch(service: ControlAccessibilityService, event: MotionEvent) {
        cancelDirectTouchStart()
        cancelLongPress()
        when (touchState) {
            TouchState.SCROLL_MODE -> exitScrollMode()
            TouchState.DRAGGING -> service.endDragAtCursor()
            TouchState.DIRECT_GESTURE -> {
                if (directGestureMoved) {
                    val target = relativeGestureTarget(event)
                    service.updateContinuousGestureTo(target.x, target.y)
                }
                service.endContinuousGesture()
                if (!directGestureMoved && !directLongPressTriggered) {
                    onTap()
                }
            }
            TouchState.ONE_FINGER_DOWN -> {
                if (!hasMovedPastSlop(event)) {
                    service.tapAtCursor()
                    onTap()
                }
            }
            else -> Unit
        }
        service.reportControlTutorialAction(ControlTutorialAction.GESTURE_END)
        resetState()
    }

    private fun relativeGestureTarget(event: MotionEvent): PointF {
        return PointF(
            directStartCursor.x + event.x - downX,
            directStartCursor.y + event.y - downY
        )
    }

    private fun scheduleLongPress(service: ControlAccessibilityService) {
        cancelLongPress()
        longPressRunnable = Runnable {
            val moved = abs(lastTouchX - downX) > longPressCancelSlopPx ||
                abs(lastTouchY - downY) > longPressCancelSlopPx
            if (moved) return@Runnable
            when (singlePointerMode) {
                SinglePointerMode.POINTER -> {
                    if (touchState != TouchState.ONE_FINGER_DOWN) return@Runnable
                    touchState = TouchState.DRAGGING
                    controlArea.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    service.startDragAtCursor()
                    service.reportControlTutorialAction(ControlTutorialAction.LONG_PRESS)
                }
                SinglePointerMode.GESTURE_ONLY -> {
                    if (touchState != TouchState.DIRECT_GESTURE ||
                        directGestureMoved
                    ) {
                        return@Runnable
                    }
                    directLongPressTriggered = true
                    service.reportControlTutorialAction(ControlTutorialAction.LONG_PRESS)
                    notifyDirectGestureFeedback()
                }
            }
        }
        handler.postDelayed(longPressRunnable!!, longPressTimeout.toLong())
    }

    private fun scheduleDirectTouchStart(service: ControlAccessibilityService) {
        cancelDirectTouchStart()
        directTouchStartRunnable = Runnable {
            directTouchStartRunnable = null
            if (touchState != TouchState.ONE_FINGER_DOWN) return@Runnable
            val moved = abs(lastTouchX - downX) > longPressCancelSlopPx ||
                abs(lastTouchY - downY) > longPressCancelSlopPx
            if (moved) return@Runnable
            if (service.startContinuousGestureAtCursor()) {
                touchState = TouchState.DIRECT_GESTURE
                scheduleLongPress(service)
            }
        }
        handler.postDelayed(directTouchStartRunnable!!, directTouchStartDelay)
    }

    private fun cancelDirectTouchStart() {
        directTouchStartRunnable?.let { handler.removeCallbacks(it) }
        directTouchStartRunnable = null
    }

    private fun notifyDirectGestureFeedback() {
        if (directGestureFeedbackSent) return
        directGestureFeedbackSent = true
        onDirectGestureStarted()
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun exitScrollMode() {
        when (activeScrollController) {
            ActiveScrollController.DIRECT -> directScrollController.exit()
            ActiveScrollController.LEGACY -> legacyScrollController.exit()
            ActiveScrollController.NONE -> Unit
        }
        activeScrollController = ActiveScrollController.NONE
        if (touchState == TouchState.SCROLL_MODE) {
            touchState = TouchState.IDLE
        }
    }

    private fun hasMovedPastSlop(event: MotionEvent): Boolean {
        return hypot(
            (event.x - downX).toDouble(),
            (event.y - downY).toDouble()
        ) > touchSlopPx
    }

    private fun hasMovedPastLongPressSlop(event: MotionEvent): Boolean {
        return abs(event.x - downX) > longPressCancelSlopPx ||
            abs(event.y - downY) > longPressCancelSlopPx
    }

    private fun resetState() {
        cancelDirectTouchStart()
        touchState = TouchState.IDLE
        suppressSingleUntilUp = false
        directGestureMoved = false
        directLongPressTriggered = false
        directGestureFeedbackSent = false
        setTouchActive(false)
    }

    private fun setTouchActive(active: Boolean) {
        if (isTouchActive == active) return
        isTouchActive = active
        onTouchActiveChanged(active)
    }

    companion object {
        private const val TOUCH_SLOP_DP = 8f
        private const val LONG_PRESS_CANCEL_DP = 3f
    }
}
