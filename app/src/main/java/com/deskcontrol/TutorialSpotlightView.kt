package com.deskcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class TutorialSpotlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.controlModeCoachmarkScrim)
    }
    private var spotlight: RectF? = null
    private var cornerRadius = 0f

    fun setSpotlight(rect: RectF, radius: Float) {
        spotlight = rect
        cornerRadius = radius
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            spotlight?.let {
                addRoundRect(it, cornerRadius, cornerRadius, Path.Direction.CW)
            }
        }
        canvas.drawPath(path, scrimPaint)
    }
}
