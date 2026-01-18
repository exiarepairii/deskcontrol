package com.deskcontrol

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator

class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MAX_SCALE = 1.8f
        private const val SPEED_THRESHOLD_PX_S = 1200f
        private const val SPEED_MAX_PX_S = 3200f
        private const val SPEED_EMA_ALPHA = 0.35f
        private const val HOLD_MS = 150L
        private const val ANIM_MS = 120L
    }

    private val arrowDrawable = AppCompatResources.getDrawable(context, R.drawable.cursor_arrow)?.mutate()
    private val outlineBlackDrawable =
        AppCompatResources.getDrawable(context, R.drawable.cursor_arrow_outline)
    private val outlineWhiteDrawable =
        AppCompatResources.getDrawable(context, R.drawable.cursor_arrow_outline_white)
    private val shadowDrawable = AppCompatResources.getDrawable(context, R.drawable.cursor_arrow_shadow)
    private var baseSizePx = 24
    private var currentScale = 1f
    private var targetScale = 1f
    private var speedEma = 0f
    private var lastBoostTime = 0L
    private var scaleAnimator: ValueAnimator? = null
    private var lastMovementTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var decayRunnable: Runnable? = null
    private var outlineDrawable: android.graphics.drawable.Drawable? = null
    private var isAttached = false

    fun setBaseSizePx(value: Int) {
        baseSizePx = value.coerceAtLeast(8)
        invalidate()
    }

    fun setArrowColor(color: Int) {
        arrowDrawable?.setTint(color)
        outlineDrawable = when (color) {
            0xFFFFFFFF.toInt() -> outlineBlackDrawable
            0xFF000000.toInt() -> outlineWhiteDrawable
            else -> outlineBlackDrawable
        }
        invalidate()
    }

    fun onCursorMoved(dx: Float, dy: Float, dtMs: Long) {
        if (dtMs <= 0L) return
        val speed = hypot(dx.toDouble(), dy.toDouble()).toFloat() / (dtMs / 1000f)
        speedEma = speedEma + SPEED_EMA_ALPHA * (speed - speedEma)
        lastMovementTime = SystemClock.uptimeMillis()
        val now = SystemClock.uptimeMillis()
        if (speedEma >= SPEED_THRESHOLD_PX_S) {
            lastBoostTime = now
        }
        val shouldHold = now - lastBoostTime < HOLD_MS
        val desiredScale = if (speedEma >= SPEED_THRESHOLD_PX_S || shouldHold) {
            val normalized = ((speedEma - SPEED_THRESHOLD_PX_S) /
                max(1f, SPEED_MAX_PX_S - SPEED_THRESHOLD_PX_S)).coerceIn(0f, 1f)
            1f + normalized * (MAX_SCALE - 1f)
        } else {
            1f
        }
        animateScaleTo(desiredScale)
        scheduleDecay()
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = arrowDrawable ?: return
        val drawSize = (baseSizePx * currentScale).roundToInt().coerceAtLeast(1)
        shadowDrawable?.let { shadow ->
            val offset = max(1, (drawSize * 0.08f).roundToInt())
            canvas.save()
            canvas.translate(offset.toFloat(), offset.toFloat())
            shadow.setBounds(0, 0, drawSize, drawSize)
            shadow.draw(canvas)
            canvas.restore()
        }
        outlineDrawable?.let { outline ->
            outline.setBounds(0, 0, drawSize, drawSize)
            outline.draw(canvas)
        }
        drawable.setBounds(0, 0, drawSize, drawSize)
        drawable.draw(canvas)
    }

    private fun scheduleDecay() {
        decayRunnable?.let { handler.removeCallbacks(it) }
        decayRunnable = Runnable {
            if (!isAttached) {
                return@Runnable
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastMovementTime >= HOLD_MS && speedEma < SPEED_THRESHOLD_PX_S) {
                animateScaleTo(1f)
            } else {
                scheduleDecay()
            }
        }
        handler.postDelayed(decayRunnable!!, HOLD_MS)
    }

    private fun animateScaleTo(value: Float) {
        val clamped = value.coerceIn(1f, MAX_SCALE)
        if (clamped == targetScale) return
        targetScale = clamped
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(currentScale, targetScale).apply {
            duration = ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        decayRunnable?.let { handler.removeCallbacks(it) }
        decayRunnable = null
        scaleAnimator?.cancel()
        scaleAnimator = null
        super.onDetachedFromWindow()
    }
}
