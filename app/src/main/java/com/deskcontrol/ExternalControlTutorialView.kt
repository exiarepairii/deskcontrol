package com.deskcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

enum class ControlTutorialAction {
    CURSOR_MOVE,
    SWIPE,
    LONG_PRESS,
    DRAG,
    SCROLL,
    VOLUME_DOWN_HOLD,
    BLACKOUT_LOCK,
    BLACKOUT_UNLOCK,
    GESTURE_END
}

internal class ExternalControlTutorialView(
    context: Context,
    private val mode: ControlSurfaceMode,
    private val onFinished: (ExternalControlTutorialView) -> Unit
) : FrameLayout(context) {

    private enum class StepKind {
        MOVE_TO_CIRCLE,
        CLICK_BUTTON,
        LONG_PRESS_BUTTON,
        DRAG_CARD,
        SCROLL_PAGE,
        HOLD_VOLUME_DOWN,
        HOLD_VOLUME_UP_LOCK,
        HOLD_VOLUME_UP_UNLOCK
    }

    private data class Step(
        val kind: StepKind,
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int? = null
    )

    private val steps = buildList {
        add(
            Step(
                StepKind.HOLD_VOLUME_DOWN,
                if (mode == ControlSurfaceMode.TOUCHPAD) {
                    R.string.external_tutorial_center_cursor
                } else {
                    R.string.external_tutorial_calibrate
                },
                if (mode == ControlSurfaceMode.TOUCHPAD) {
                    R.string.external_tutorial_center_cursor_hint
                } else {
                    R.string.external_tutorial_calibrate_posture
                }
            )
        )
        add(Step(StepKind.MOVE_TO_CIRCLE, R.string.external_tutorial_move_to_circle))
        add(Step(StepKind.CLICK_BUTTON, R.string.external_tutorial_click))
        add(Step(StepKind.LONG_PRESS_BUTTON, R.string.external_tutorial_long_press))
        if (mode == ControlSurfaceMode.TOUCHPAD) {
            add(Step(StepKind.DRAG_CARD, R.string.external_tutorial_drag))
        }
        add(
            Step(
                StepKind.SCROLL_PAGE,
                if (mode == ControlSurfaceMode.TOUCHPAD) {
                    R.string.external_tutorial_scroll_touch
                } else {
                    R.string.external_tutorial_scroll_motion
                }
            )
        )
        add(
            Step(
                StepKind.HOLD_VOLUME_UP_LOCK,
                R.string.external_tutorial_blackout_lock,
                R.string.external_tutorial_blackout_lock_hint
            )
        )
        add(
            Step(
                StepKind.HOLD_VOLUME_UP_UNLOCK,
                R.string.external_tutorial_blackout_unlock,
                R.string.external_tutorial_blackout_unlock_hint
            )
        )
    }

    private val density = resources.displayMetrics.density
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, 82)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, 205)
        textSize = 18f * density
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val progressPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, 145)
        textSize = 15f * density
    }
    private val actionButton = createActionButton()

    private var startedAt = 0L
    private var currentStepIndex = 0
    private var advancePending = false
    private var waitingForGestureEnd = false
    private var completedAt: Long? = null
    private var finishPosted = false

    private var draggingCard = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragCardCenterX = 0f
    private var dragCardCenterY = 0f
    private var scrollLastY = 0f
    private var scrollOffset = 0f
    private var longPressStartedAt = 0L
    private var longPressFillActive = false
    private var longPressCompleted = false

    private val longPressProgressRunnable = object : Runnable {
        override fun run() {
            if (!longPressFillActive ||
                currentStep()?.kind != StepKind.LONG_PRESS_BUTTON
            ) {
                return
            }
            val elapsed = SystemClock.uptimeMillis() - longPressStartedAt
            val progress = (elapsed / LONG_PRESS_FILL_MS.toFloat()).coerceIn(0f, 1f)
            actionButton.fillFraction = progress
            if (progress >= 1f) {
                longPressFillActive = false
                longPressCompleted = true
                actionButton.isPressed = false
                requestAdvance(
                    waitForGestureEnd = false,
                    delayMs = LONG_PRESS_COMPLETE_HOLD_MS
                )
            } else {
                actionButton.postOnAnimation(this)
            }
        }
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
        addView(actionButton)
        actionButton.visibility = View.GONE
    }

    fun start() {
        startedAt = SystemClock.uptimeMillis()
        configureCurrentStep(announce = true)
        postInvalidateOnAnimation()
    }

    fun onCursorMoved(x: Float, y: Float) {
        if (currentStep()?.kind != StepKind.MOVE_TO_CIRCLE || advancePending) return
        val target = cursorTarget()
        if (hypot(x - target.first, y - target.second) <= target.third) {
            requestAdvance(waitForGestureEnd = false)
        }
    }

    fun onAction(action: ControlTutorialAction) {
        if (completedAt != null) return
        when {
            action == ControlTutorialAction.GESTURE_END && waitingForGestureEnd -> {
                waitingForGestureEnd = false
                advanceStep()
            }
            action == ControlTutorialAction.VOLUME_DOWN_HOLD &&
                currentStep()?.kind == StepKind.HOLD_VOLUME_DOWN -> {
                requestAdvance(waitForGestureEnd = false)
            }
            action == ControlTutorialAction.BLACKOUT_LOCK &&
                currentStep()?.kind == StepKind.HOLD_VOLUME_UP_LOCK -> {
                requestAdvance(waitForGestureEnd = false)
            }
            action == ControlTutorialAction.BLACKOUT_UNLOCK &&
                currentStep()?.kind == StepKind.HOLD_VOLUME_UP_UNLOCK -> {
                requestAdvance(waitForGestureEnd = false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startedAt == 0L) return
        val now = SystemClock.uptimeMillis()
        val introElapsed = now - startedAt
        val completedElapsed = completedAt?.let { now - it }
        val alpha = when {
            completedElapsed != null ->
                (1f - completedElapsed / COMPLETE_FADE_MS.toFloat()).coerceIn(0f, 1f)
            introElapsed < FADE_MS -> introElapsed / FADE_MS.toFloat()
            else -> 1f
        }

        scrimPaint.color = ColorUtils.setAlphaComponent(Color.BLACK, (242 * alpha).toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        if (completedElapsed != null) {
            drawCompletion(canvas, alpha)
            if (completedElapsed >= COMPLETE_FADE_MS && !finishPosted) {
                finishPosted = true
                post { onFinished(this) }
            } else {
                postInvalidateOnAnimation()
            }
        } else {
            drawStep(canvas, currentStep() ?: return, now, alpha)
            postInvalidateOnAnimation()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val buttonWidth = min((240f * density).toInt(), (width * 0.46f).toInt())
        val buttonHeight = (64f * density).toInt()
        val buttonLeft = (width - buttonWidth) / 2
        val buttonTop = (height * 0.52f).toInt()
        actionButton.layout(
            buttonLeft,
            buttonTop,
            buttonLeft + buttonWidth,
            buttonTop + buttonHeight
        )
        if (dragCardCenterX == 0f && width > 0) {
            resetDragCard()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (advancePending || completedAt != null) return true
        return when (currentStep()?.kind) {
            StepKind.DRAG_CARD -> handleCardDrag(event)
            StepKind.SCROLL_PAGE -> handlePageScroll(event)
            else -> true
        }
    }

    private fun drawStep(canvas: Canvas, step: Step, now: Long, alpha: Float) {
        progressPaint.alpha = (145 * alpha).toInt()
        drawCenteredText(
            canvas,
            resources.getString(
                R.string.external_tutorial_progress,
                currentStepIndex + 1,
                steps.size
            ),
            progressPaint,
            height * 0.055f,
            width * 0.35f
        )

        titlePaint.alpha = (255 * alpha).toInt()
        val titleTop = if (step.kind == StepKind.MOVE_TO_CIRCLE) {
            height * 0.56f
        } else {
            height * 0.16f
        }
        val titleWidth = if (step.kind == StepKind.MOVE_TO_CIRCLE) {
            width * 0.68f
        } else {
            width * 0.78f
        }
        drawCenteredText(
            canvas,
            resources.getString(step.titleRes),
            titlePaint,
            titleTop,
            titleWidth
        )

        when (step.kind) {
            StepKind.MOVE_TO_CIRCLE -> drawCursorTarget(canvas, now, alpha)
            StepKind.CLICK_BUTTON,
            StepKind.LONG_PRESS_BUTTON -> Unit
            StepKind.DRAG_CARD -> drawDragTask(canvas, alpha)
            StepKind.SCROLL_PAGE -> drawScrollTask(canvas, alpha)
            StepKind.HOLD_VOLUME_DOWN -> drawVolumeKeyTask(canvas, now, alpha, isUp = false)
            StepKind.HOLD_VOLUME_UP_LOCK,
            StepKind.HOLD_VOLUME_UP_UNLOCK ->
                drawVolumeKeyTask(canvas, now, alpha, isUp = true)
        }

        step.subtitleRes?.let {
            bodyPaint.alpha = (205 * alpha).toInt()
            drawCenteredText(
                canvas,
                resources.getString(it),
                bodyPaint,
                height * 0.73f,
                width * 0.68f
            )
        }
    }

    private fun drawCursorTarget(canvas: Canvas, now: Long, alpha: Float) {
        val (centerX, centerY, radius) = cursorTarget()
        val pulse = (sin(now / 210.0).toFloat() + 1f) / 2f
        accentPaint.style = Paint.Style.FILL
        accentPaint.alpha = ((28 + 28 * pulse) * alpha).toInt()
        canvas.drawCircle(centerX, centerY, radius + 10f * density * pulse, accentPaint)
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = 5f * density
        accentPaint.alpha = (255 * alpha).toInt()
        canvas.drawCircle(centerX, centerY, radius, accentPaint)
    }

    private fun drawDragTask(canvas: Canvas, alpha: Float) {
        val target = dragTargetRect()
        mutedPaint.style = Paint.Style.STROKE
        mutedPaint.strokeWidth = 3f * density
        mutedPaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
        mutedPaint.alpha = (130 * alpha).toInt()
        canvas.drawRoundRect(target, 18f * density, 18f * density, mutedPaint)
        mutedPaint.pathEffect = null

        val card = dragCardRect()
        accentPaint.style = Paint.Style.FILL
        accentPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(card, 18f * density, 18f * density, accentPaint)
        labelPaint.color = 0xFF17201F.toInt()
        labelPaint.alpha = (255 * alpha).toInt()
        drawTextInRect(
            canvas,
            resources.getString(R.string.external_tutorial_drag_card),
            labelPaint,
            card
        )
        labelPaint.color = Color.WHITE
    }

    private fun drawScrollTask(canvas: Canvas, alpha: Float) {
        val page = scrollPageRect()
        whitePaint.style = Paint.Style.FILL
        whitePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (24 * alpha).toInt())
        canvas.drawRoundRect(page, 22f * density, 22f * density, whitePaint)
        whitePaint.style = Paint.Style.STROKE
        whitePaint.strokeWidth = 2f * density
        whitePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (70 * alpha).toInt())
        canvas.drawRoundRect(page, 22f * density, 22f * density, whitePaint)

        canvas.save()
        canvas.clipRect(page)
        val contentTop = page.top + 34f * density - scrollOffset
        labelPaint.alpha = (255 * alpha).toInt()
        canvas.drawText(
            resources.getString(R.string.external_tutorial_scroll_card_title),
            page.left + 30f * density,
            contentTop,
            labelPaint
        )
        bodyPaint.alpha = (190 * alpha).toInt()
        canvas.drawText(
            resources.getString(R.string.external_tutorial_scroll_card_body),
            page.left + 30f * density,
            contentTop + 43f * density,
            bodyPaint
        )
        mutedPaint.style = Paint.Style.FILL
        mutedPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (55 * alpha).toInt())
        repeat(7) { index ->
            val top = contentTop + (82f + index * 60f) * density
            canvas.drawRoundRect(
                RectF(
                    page.left + 30f * density,
                    top,
                    page.right - (30f + (index % 3) * 42f) * density,
                    top + 13f * density
                ),
                7f * density,
                7f * density,
                mutedPaint
            )
        }
        canvas.restore()
    }

    private fun drawVolumeKeyTask(
        canvas: Canvas,
        now: Long,
        alpha: Float,
        isUp: Boolean
    ) {
        val scale = min(width, height) / 720f
        val centerX = width / 2f
        val centerY = height * 0.48f
        val phone = RectF(
            centerX - 58f * scale,
            centerY - 105f * scale,
            centerX + 58f * scale,
            centerY + 105f * scale
        )
        whitePaint.color = Color.WHITE
        whitePaint.style = Paint.Style.STROKE
        whitePaint.strokeWidth = 3f * scale
        whitePaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(phone, 18f * scale, 18f * scale, whitePaint)
        canvas.drawLine(
            centerX - 18f * scale,
            phone.top + 17f * scale,
            centerX + 18f * scale,
            phone.top + 17f * scale,
            whitePaint
        )
        val pulse = (sin(now / 230.0).toFloat() + 1f) / 2f
        val keyCenterY = centerY + (if (isUp) -36f else 36f) * scale
        val keyX = phone.left - (9f + pulse * 5f) * scale
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = (4f + pulse * 2f) * scale
        accentPaint.alpha = (255 * alpha).toInt()
        canvas.drawLine(
            keyX,
            keyCenterY - 22f * scale,
            keyX,
            keyCenterY + 22f * scale,
            accentPaint
        )
        val symbolX = phone.left - 42f * scale
        canvas.drawLine(
            symbolX - 10f * scale,
            keyCenterY,
            symbolX + 10f * scale,
            keyCenterY,
            accentPaint
        )
        if (isUp) {
            canvas.drawLine(
                symbolX,
                keyCenterY - 10f * scale,
                symbolX,
                keyCenterY + 10f * scale,
                accentPaint
            )
        }
    }

    private fun handleCardDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val card = dragCardRect()
                if (card.contains(event.x, event.y)) {
                    draggingCard = true
                    dragOffsetX = event.x - dragCardCenterX
                    dragOffsetY = event.y - dragCardCenterY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingCard) {
                    dragCardCenterX = event.x - dragOffsetX
                    dragCardCenterY = event.y - dragOffsetY
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingCard) {
                    draggingCard = false
                    if (dragTargetRect().contains(dragCardCenterX, dragCardCenterY)) {
                        requestAdvance(waitForGestureEnd = false)
                    } else {
                        resetDragCard()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingCard = false
                resetDragCard()
                invalidate()
            }
        }
        return true
    }

    private fun handlePageScroll(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scrollLastY = event.y
            MotionEvent.ACTION_MOVE -> {
                val delta = scrollLastY - event.y
                scrollLastY = event.y
                if (delta > 0f || scrollOffset > 0f) {
                    scrollOffset = (scrollOffset + delta).coerceIn(0f, 180f * density)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (scrollOffset >= SCROLL_COMPLETE_DP * density) {
                    requestAdvance(waitForGestureEnd = false)
                }
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    private fun requestAdvance(
        waitForGestureEnd: Boolean,
        delayMs: Long = ACTION_CONFIRM_MS
    ) {
        if (advancePending || completedAt != null) return
        advancePending = true
        waitingForGestureEnd = waitForGestureEnd
        if (!waitForGestureEnd) {
            postDelayed(::advanceStep, delayMs)
        }
    }

    private fun advanceStep() {
        if (completedAt != null || (!advancePending && currentStepIndex != 0)) return
        advancePending = false
        waitingForGestureEnd = false
        currentStepIndex += 1
        if (currentStepIndex >= steps.size) {
            actionButton.visibility = View.GONE
            completedAt = SystemClock.uptimeMillis()
            contentDescription = resources.getString(R.string.external_tutorial_complete)
            announceForAccessibility(contentDescription)
        } else {
            configureCurrentStep(announce = true)
        }
        invalidate()
    }

    private fun configureCurrentStep(announce: Boolean) {
        val step = currentStep() ?: return
        actionButton.visibility = when (step.kind) {
            StepKind.CLICK_BUTTON,
            StepKind.LONG_PRESS_BUTTON -> View.VISIBLE
            else -> View.GONE
        }
        actionButton.setOnClickListener(null)
        actionButton.setOnLongClickListener(null)
        actionButton.setOnTouchListener(null)
        cancelLongPressFill(resetVisual = true)
        when (step.kind) {
            StepKind.CLICK_BUTTON -> {
                actionButton.text =
                    resources.getString(R.string.external_tutorial_click_button)
                actionButton.setOnClickListener {
                    actionButton.baseColor = BUTTON_CLICKED
                    actionButton.fillFraction = 0f
                    actionButton.setTextColor(Color.WHITE)
                    requestAdvance(
                        waitForGestureEnd = false,
                        delayMs = CLICK_FEEDBACK_MS
                    )
                }
            }
            StepKind.LONG_PRESS_BUTTON -> {
                actionButton.text =
                    resources.getString(R.string.external_tutorial_long_press_button)
                actionButton.setOnLongClickListener {
                    completeLongPressFill()
                    true
                }
                actionButton.setOnTouchListener { view, event ->
                    val handled = handleLongPressButtonTouch(event)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    handled
                }
            }
            StepKind.DRAG_CARD -> resetDragCard()
            StepKind.SCROLL_PAGE -> scrollOffset = 0f
            else -> Unit
        }
        val title = resources.getString(step.titleRes)
        val subtitle = step.subtitleRes?.let(resources::getString)
        contentDescription = listOfNotNull(title, subtitle).joinToString(". ")
        if (announce) {
            announceForAccessibility(contentDescription)
        }
    }

    private fun handleLongPressButtonTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressStartedAt = SystemClock.uptimeMillis()
                longPressFillActive = true
                longPressCompleted = false
                actionButton.baseColor = BUTTON_LONG_PRESS_BASE
                actionButton.fillColor = BUTTON_LONG_PRESS_FILL
                actionButton.fillFraction = 0f
                actionButton.setTextColor(Color.WHITE)
                actionButton.isPressed = true
                actionButton.postOnAnimation(longPressProgressRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x in 0f..actionButton.width.toFloat() &&
                    event.y in 0f..actionButton.height.toFloat()
                if (!inside && !longPressCompleted) {
                    cancelLongPressFill(resetVisual = true)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                actionButton.isPressed = false
                if (!longPressCompleted) {
                    cancelLongPressFill(resetVisual = true)
                }
            }
        }
        return true
    }

    private fun completeLongPressFill() {
        if (advancePending || longPressCompleted) return
        longPressFillActive = false
        longPressCompleted = true
        actionButton.baseColor = BUTTON_LONG_PRESS_BASE
        actionButton.fillColor = BUTTON_LONG_PRESS_FILL
        actionButton.fillFraction = 1f
        actionButton.setTextColor(Color.WHITE)
        requestAdvance(
            waitForGestureEnd = false,
            delayMs = LONG_PRESS_COMPLETE_HOLD_MS
        )
    }

    private fun cancelLongPressFill(resetVisual: Boolean) {
        longPressFillActive = false
        longPressCompleted = false
        actionButton.removeCallbacks(longPressProgressRunnable)
        actionButton.isPressed = false
        if (resetVisual) {
            actionButton.resetVisual()
        }
    }

    private fun currentStep(): Step? = steps.getOrNull(currentStepIndex)

    private fun cursorTarget(): Triple<Float, Float, Float> {
        return Triple(width * 0.84f, height * 0.20f, 50f * density)
    }

    private fun resetDragCard() {
        dragCardCenterX = width * 0.28f
        dragCardCenterY = height * 0.55f
    }

    private fun dragCardRect(): RectF {
        val halfWidth = min(width * 0.15f, 116f * density)
        val halfHeight = 52f * density
        return RectF(
            dragCardCenterX - halfWidth,
            dragCardCenterY - halfHeight,
            dragCardCenterX + halfWidth,
            dragCardCenterY + halfHeight
        )
    }

    private fun dragTargetRect(): RectF {
        val halfWidth = min(width * 0.22f, 168f * density)
        val halfHeight = 78f * density
        val centerX = width * 0.72f
        val centerY = height * 0.55f
        return RectF(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight
        )
    }

    private fun scrollPageRect(): RectF {
        return RectF(
            width * 0.18f,
            height * 0.38f,
            width * 0.82f,
            height * 0.84f
        )
    }

    private fun drawCompletion(canvas: Canvas, alpha: Float) {
        val scale = min(width, height) / 720f
        val centerX = width / 2f
        val centerY = height * 0.42f
        accentPaint.alpha = (255 * alpha).toInt()
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = 8f * scale
        canvas.drawCircle(centerX, centerY, 58f * scale, accentPaint)
        canvas.drawLine(
            centerX - 28f * scale,
            centerY,
            centerX - 7f * scale,
            centerY + 22f * scale,
            accentPaint
        )
        canvas.drawLine(
            centerX - 7f * scale,
            centerY + 22f * scale,
            centerX + 34f * scale,
            centerY - 25f * scale,
            accentPaint
        )
        titlePaint.alpha = (255 * alpha).toInt()
        drawCenteredText(
            canvas,
            resources.getString(R.string.external_tutorial_complete),
            titlePaint,
            height * 0.61f,
            width * 0.72f
        )
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        top: Float,
        maxWidth: Float
    ) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate((width - layout.width) / 2f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        rect: RectF
    ) {
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, rect.centerX() - paint.measureText(text) / 2f, baseline, paint)
    }

    private inner class TutorialActionButton(context: Context) : AppCompatButton(context) {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clipPath = Path()
        var baseColor: Int = ACCENT
            set(value) {
                field = value
                invalidate()
            }
        var fillColor: Int = BUTTON_LONG_PRESS_FILL
            set(value) {
                field = value
                invalidate()
            }
        var fillFraction: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        fun resetVisual() {
            baseColor = ACCENT
            fillColor = BUTTON_LONG_PRESS_FILL
            fillFraction = 0f
            setTextColor(0xFF17201F.toInt())
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val radius = 18f * density
            backgroundPaint.color = baseColor
            canvas.drawRoundRect(bounds, radius, radius, backgroundPaint)
            if (fillFraction > 0f) {
                clipPath.reset()
                clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)
                backgroundPaint.color = fillColor
                canvas.drawRect(
                    0f,
                    0f,
                    width * fillFraction,
                    height.toFloat(),
                    backgroundPaint
                )
                canvas.restore()
            }
            super.onDraw(canvas)
        }
    }

    private fun createActionButton(): TutorialActionButton {
        return TutorialActionButton(context).apply {
            isAllCaps = false
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = 0
            minHeight = 0
            elevation = 0f
            stateListAnimator = null
            setPadding(20.dp, 0, 20.dp, 0)
            background = null
            resetVisual()
            layoutParams = LayoutParams(
                (240f * density).toInt(),
                (64f * density).toInt()
            )
        }
    }

    private val Int.dp: Int
        get() = (this * density).toInt()

    companion object {
        private const val ACCENT = 0xFF7FB7AE.toInt()
        private const val BUTTON_CLICKED = 0xFF5B6062.toInt()
        private const val BUTTON_LONG_PRESS_BASE = 0xFF42514F.toInt()
        private const val BUTTON_LONG_PRESS_FILL = 0xFF9ACBC3.toInt()
        private const val SCROLL_COMPLETE_DP = 88f
        private const val ACTION_CONFIRM_MS = 220L
        private const val CLICK_FEEDBACK_MS = 420L
        private const val LONG_PRESS_FILL_MS = 900L
        private const val LONG_PRESS_COMPLETE_HOLD_MS = 180L
        private const val FADE_MS = 280L
        private const val COMPLETE_FADE_MS = 1_100L
    }
}
