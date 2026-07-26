package com.deskcontrol

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import kotlin.math.abs

class PlayReviewDemoActivity : AppCompatActivity() {
    private lateinit var display: FrameLayout
    private lateinit var cursor: View
    private lateinit var result: TextView
    private var cursorX = 0f
    private var cursorY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play_review_demo)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(findViewById(R.id.playReviewRoot))
        findViewById<MaterialToolbar>(R.id.playReviewToolbar)
            .setNavigationOnClickListener { finish() }
        display = findViewById(R.id.playReviewDisplay)
        cursor = findViewById(R.id.playReviewCursor)
        result = findViewById(R.id.playReviewResult)
        val touchpad = findViewById<View>(R.id.playReviewTouchpad)
        touchpad.setOnTouchListener { view, event ->
            handleTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        display.post {
            cursorX = display.width / 2f
            cursorY = display.height / 2f
            updateCursor()
        }
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                cursorX += (event.x - lastX) * 1.5f
                cursorY += (event.y - lastY) * 1.5f
                lastX = event.x
                lastY = event.y
                updateCursor()
                result.setText(R.string.play_review_demo_moving)
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) < TAP_SLOP && abs(event.y - downY) < TAP_SLOP) {
                    result.setText(R.string.play_review_demo_clicked)
                }
            }
        }
    }

    private fun updateCursor() {
        cursorX = cursorX.coerceIn(0f, (display.width - cursor.width).coerceAtLeast(0).toFloat())
        cursorY = cursorY.coerceIn(0f, (display.height - cursor.height).coerceAtLeast(0).toFloat())
        cursor.translationX = cursorX
        cursor.translationY = cursorY
    }

    private companion object {
        const val TAP_SLOP = 16f
    }
}
