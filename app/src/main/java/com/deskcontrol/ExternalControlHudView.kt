package com.deskcontrol

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Non-interactive feedback drawn on the selected external display.
 *
 * The view intentionally contains no text so it remains useful over any projected app and in
 * every configured language.
 */
class ExternalControlHudView(context: Context) : View(context) {

    enum class HoldAction {
        CALIBRATE,
        LOCK,
        UNLOCK
    }

    private enum class Mode {
        HIDDEN,
        VOLUME,
        HOLD
    }

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var mode = Mode.HIDDEN
    private var holdAction = HoldAction.CALIBRATE
    private var holdProgress = 0f
    private var volumeFraction = 0f
    private var displayedVolumeFraction = 0f
    private var progressAnimator: ValueAnimator? = null
    private var volumeAnimator: ValueAnimator? = null
    private val hideRunnable = Runnable { fadeOut() }
    private val calibrateIcon = loadIcon(R.drawable.ic_hud_calibrate)
    private val lockIcon = loadIcon(R.drawable.ic_hud_lock)
    private val unlockIcon = loadIcon(R.drawable.ic_hud_unlock)
    private val volumeIcon = loadIcon(R.drawable.ic_hud_volume)

    init {
        alpha = 0f
        visibility = INVISIBLE
    }

    fun showVolume(level: Int, maxLevel: Int) {
        cancelAnimations()
        mode = Mode.VOLUME
        volumeFraction = if (maxLevel > 0) {
            level.toFloat().div(maxLevel).coerceIn(0f, 1f)
        } else {
            0f
        }
        showImmediately()
        volumeAnimator = ValueAnimator.ofFloat(displayedVolumeFraction, volumeFraction).apply {
            duration = VOLUME_CHANGE_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedVolumeFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        handler.postDelayed(hideRunnable, VOLUME_VISIBLE_MS)
    }

    fun beginHold(action: HoldAction, durationMs: Long, elapsedMs: Long) {
        cancelAnimations()
        mode = Mode.HOLD
        holdAction = action
        holdProgress = elapsedMs.toFloat().div(durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        showImmediately()
        progressAnimator = ValueAnimator.ofFloat(holdProgress, 1f).apply {
            duration = (durationMs - elapsedMs).coerceAtLeast(1L)
            interpolator = null
            addUpdateListener {
                holdProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun cancelHold() {
        if (mode != Mode.HOLD) return
        progressAnimator?.cancel()
        progressAnimator = null
        fadeOut()
    }

    fun completeHold(success: Boolean) {
        if (mode != Mode.HOLD) return
        progressAnimator?.cancel()
        progressAnimator = null
        if (success) {
            holdProgress = 1f
            invalidate()
            animate()
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(HOLD_CONFIRM_ANIMATION_MS)
                .withEndAction {
                    animate()
                        .alpha(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(HOLD_FADE_ANIMATION_MS)
                        .withEndAction(::hideImmediately)
                        .start()
                }
                .start()
        } else {
            fadeOut()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.VOLUME -> drawVolume(canvas)
            Mode.HOLD -> drawHold(canvas)
            Mode.HIDDEN -> Unit
        }
    }

    private fun drawVolume(canvas: Canvas) {
        val trackWidth = dp(8f)
        val trackHeight = min(height * 0.34f, dp(240f))
        val rightMargin = maxOf(width * 0.035f, dp(28f))
        val left = width - rightMargin - trackWidth
        val top = (height - trackHeight) / 2f
        val track = RectF(left, top, left + trackWidth, top + trackHeight)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = trackWidth
        paint.color = WHITE_TRACK
        canvas.drawLine(track.centerX(), track.top, track.centerX(), track.bottom, paint)

        val fillTop = track.bottom - track.height() * displayedVolumeFraction
        paint.color = Color.WHITE
        canvas.drawLine(track.centerX(), track.bottom, track.centerX(), fillTop, paint)

        val iconSize = dp(24f)
        drawIcon(
            canvas = canvas,
            icon = volumeIcon,
            centerX = track.centerX(),
            centerY = track.bottom + dp(18f) + iconSize / 2f,
            size = iconSize
        )
    }

    private fun drawHold(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.075f
        val strokeWidth = maxOf(dp(4f), radius * 0.08f)
        val ring = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = WHITE_TRACK
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.color = Color.WHITE
        canvas.drawArc(ring, -90f, holdProgress * 360f, false, paint)

        val icon = when (holdAction) {
            HoldAction.CALIBRATE -> calibrateIcon
            HoldAction.LOCK -> lockIcon
            HoldAction.UNLOCK -> unlockIcon
        }
        drawIcon(canvas, icon, centerX, centerY, radius * HOLD_ICON_SIZE_TO_RADIUS)
    }

    private fun drawIcon(
        canvas: Canvas,
        icon: Drawable?,
        centerX: Float,
        centerY: Float,
        size: Float
    ) {
        if (icon == null) return
        val halfSize = size / 2f
        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt()
        )
        icon.draw(canvas)
    }

    private fun loadIcon(resourceId: Int): Drawable? =
        context.getDrawable(resourceId)?.mutate()

    private fun showImmediately() {
        handler.removeCallbacks(hideRunnable)
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        invalidate()
    }

    private fun fadeOut() {
        handler.removeCallbacks(hideRunnable)
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(HOLD_FADE_ANIMATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hideImmediately()
                    animate().setListener(null)
                }
            })
            .start()
    }

    private fun hideImmediately() {
        mode = Mode.HIDDEN
        visibility = INVISIBLE
        alpha = 0f
        scaleX = 1f
        scaleY = 1f
    }

    private fun cancelAnimations() {
        handler.removeCallbacks(hideRunnable)
        animate().cancel()
        progressAnimator?.cancel()
        progressAnimator = null
        volumeAnimator?.cancel()
        volumeAnimator = null
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        const val VOLUME_CHANGE_ANIMATION_MS = 140L
        const val VOLUME_VISIBLE_MS = 850L
        const val HOLD_CONFIRM_ANIMATION_MS = 90L
        const val HOLD_FADE_ANIMATION_MS = 150L
        const val HOLD_ICON_SIZE_TO_RADIUS = 0.90f
        const val WHITE_TRACK = 0x55FFFFFF
    }
}
