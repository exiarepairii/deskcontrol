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
    private val twoFingerScrollEnabled: Boolean = true,
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
        WAITING_DIRECT_GESTURE,
        RECOVERING_DIRECT_GESTURE,
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
    private var pendingDirectStartRegistered = false
    private var directTouchGeneration = 0L
    private var directGestureRecoveryPoint: PointF? = null

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
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (twoFingerScrollEnabled) {
                    beginScroll(service, event)
                } else {
                    rejectMultiPointerGesture(service)
                }
            }
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
            TouchState.WAITING_DIRECT_GESTURE -> Unit
            TouchState.RECOVERING_DIRECT_GESTURE -> Unit
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
            TouchState.WAITING_DIRECT_GESTURE -> Unit
            TouchState.RECOVERING_DIRECT_GESTURE -> Unit
            TouchState.SCROLL_MODE -> exitScrollMode()
            else -> Unit
        }
        service?.reportControlTutorialAction(ControlTutorialAction.GESTURE_END)
        resetState()
    }

    private fun beginTouch(service: ControlAccessibilityService, event: MotionEvent) {
        directTouchGeneration += 1L
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
        pendingDirectStartRegistered = false
        directGestureRecoveryPoint = null
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

    private fun rejectMultiPointerGesture(service: ControlAccessibilityService) {
        cancelDirectTouchStart()
        cancelLongPress()
        when (touchState) {
            TouchState.DRAGGING -> service.cancelDrag()
            TouchState.DIRECT_GESTURE -> service.cancelContinuousGesture()
            TouchState.SCROLL_MODE -> exitScrollMode()
            else -> Unit
        }
        touchState = TouchState.IDLE
        suppressSingleUntilUp = true
        setTouchActive(false)
        service.reportControlTutorialAction(ControlTutorialAction.GESTURE_END)
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
            if (!shouldForwardDirectGestureMove(
                    movementStarted = directGestureMoved,
                    dxFromDown = event.x - downX,
                    dyFromDown = event.y - downY,
                    touchSlopPx = touchSlopPx
                )
            ) {
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
        if (touchState == TouchState.WAITING_DIRECT_GESTURE) {
            if (hasMovedPastSlop(event)) {
                directGestureMoved = true
                cancelDirectTouchStart()
                cancelLongPress()
            }
            return
        }
        if (touchState == TouchState.RECOVERING_DIRECT_GESTURE) {
            directGestureMoved = true
            cancelDirectTouchStart()
            cancelLongPress()
            recoverDirectGesture(service, relativeGestureTarget(event))
            return
        }
        if (touchState == TouchState.ONE_FINGER_DOWN &&
            hasMovedPastLongPressSlop(event)
        ) {
            cancelDirectTouchStart()
        }
        val moved = hasMovedPastSlop(event)
        if (touchState == TouchState.ONE_FINGER_DOWN && moved) {
            directGestureMoved = true
            requestDirectGestureStart(service)
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
            TouchState.WAITING_DIRECT_GESTURE -> Unit
            TouchState.RECOVERING_DIRECT_GESTURE -> Unit
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
            requestDirectGestureStart(service)
        }
        handler.postDelayed(directTouchStartRunnable!!, directTouchStartDelay)
    }

    private fun requestDirectGestureStart(service: ControlAccessibilityService) {
        val startGeneration = directTouchGeneration
        val started = service.startContinuousGestureAtCursor { lastPoint ->
            handleDirectGestureCancellation(startGeneration, lastPoint)
        }
        if (started) {
            pendingDirectStartRegistered = false
            touchState = TouchState.DIRECT_GESTURE
            directGestureRecoveryPoint = null
            if (directGestureMoved) {
                service.reportControlTutorialAction(ControlTutorialAction.SWIPE)
                notifyDirectGestureFeedback()
                service.updateContinuousGestureTo(
                    directStartCursor.x + lastTouchX - downX,
                    directStartCursor.y + lastTouchY - downY
                )
            } else {
                scheduleLongPress(service)
            }
            return
        }
        if (touchState == TouchState.RECOVERING_DIRECT_GESTURE) return
        if (!service.isContinuousGestureBusy()) {
            touchState = TouchState.RECOVERING_DIRECT_GESTURE
            directGestureRecoveryPoint = service.getCursorPosition()
            return
        }

        touchState = TouchState.WAITING_DIRECT_GESTURE
        if (pendingDirectStartRegistered) return
        pendingDirectStartRegistered = true
        val waitGeneration = directTouchGeneration
        service.whenContinuousGestureIdle {
            if (waitGeneration != directTouchGeneration) {
                return@whenContinuousGestureIdle
            }
            pendingDirectStartRegistered = false
            if (touchState != TouchState.WAITING_DIRECT_GESTURE || !isTouchActive) {
                return@whenContinuousGestureIdle
            }
            requestDirectGestureStart(service)
        }
    }

    private fun handleDirectGestureCancellation(generation: Long, lastPoint: PointF) {
        if (!shouldRecoverDirectGestureCancellation(
                callbackGeneration = generation,
                currentGeneration = directTouchGeneration,
                isTouchActive = isTouchActive,
                isDirectGestureState = touchState == TouchState.DIRECT_GESTURE ||
                    touchState == TouchState.ONE_FINGER_DOWN ||
                    touchState == TouchState.WAITING_DIRECT_GESTURE ||
                    touchState == TouchState.RECOVERING_DIRECT_GESTURE
            )
        ) {
            return
        }
        pendingDirectStartRegistered = false
        directGestureRecoveryPoint = PointF(lastPoint.x, lastPoint.y)
        touchState = TouchState.RECOVERING_DIRECT_GESTURE
        DiagnosticsLog.add(
            "DirectGesture: recovery armed generation=$generation " +
                "point=(${lastPoint.x.toInt()},${lastPoint.y.toInt()})"
        )
    }

    private fun recoverDirectGesture(
        service: ControlAccessibilityService,
        target: PointF
    ) {
        val start = directGestureRecoveryPoint ?: target
        val generation = directTouchGeneration
        val started = service.startContinuousGestureAt(start.x, start.y) { lastPoint ->
            handleDirectGestureCancellation(generation, lastPoint)
        }
        if (started) {
            pendingDirectStartRegistered = false
            directGestureRecoveryPoint = null
            touchState = TouchState.DIRECT_GESTURE
            service.updateContinuousGestureTo(target.x, target.y)
            DiagnosticsLog.add(
                "DirectGesture: recovery started generation=$generation " +
                    "from=(${start.x.toInt()},${start.y.toInt()}) " +
                    "to=(${target.x.toInt()},${target.y.toInt()})"
            )
            return
        }
        if (touchState != TouchState.RECOVERING_DIRECT_GESTURE ||
            !service.isContinuousGestureBusy() ||
            pendingDirectStartRegistered
        ) {
            return
        }
        pendingDirectStartRegistered = true
        service.whenContinuousGestureIdle {
            if (generation != directTouchGeneration ||
                touchState != TouchState.RECOVERING_DIRECT_GESTURE ||
                !isTouchActive
            ) {
                return@whenContinuousGestureIdle
            }
            pendingDirectStartRegistered = false
            recoverDirectGesture(
                service,
                PointF(
                    directStartCursor.x + lastTouchX - downX,
                    directStartCursor.y + lastTouchY - downY
                )
            )
        }
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
        pendingDirectStartRegistered = false
        directGestureRecoveryPoint = null
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

internal fun shouldForwardDirectGestureMove(
    movementStarted: Boolean,
    dxFromDown: Float,
    dyFromDown: Float,
    touchSlopPx: Float
): Boolean {
    if (movementStarted) return true
    return hypot(dxFromDown.toDouble(), dyFromDown.toDouble()) > touchSlopPx
}

internal fun shouldRecoverDirectGestureCancellation(
    callbackGeneration: Long,
    currentGeneration: Long,
    isTouchActive: Boolean,
    isDirectGestureState: Boolean
): Boolean {
    return callbackGeneration == currentGeneration && isTouchActive && isDirectGestureState
}
