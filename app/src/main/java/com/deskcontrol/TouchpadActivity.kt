package com.deskcontrol

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.deskcontrol.databinding.ActivityTouchpadBinding

class TouchpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTouchpadBinding
    private var dragMode = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.touchpadArea.setOnTouchListener { _: View, event: MotionEvent ->
            handleTouch(event)
            true
        }

        binding.btnClick.setOnClickListener {
            val service = serviceOrToast() ?: return@setOnClickListener
            service.tapAtCursor()
        }

        binding.btnToggleDrag.setOnClickListener {
            dragMode = !dragMode
            binding.btnToggleDrag.text = if (dragMode) "Drag: On" else "Drag: Off"
        }

    }

    private fun handleTouch(event: MotionEvent) {
        val service = ControlAccessibilityService.current() ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                val oldPosition = service.getCursorPosition()
                service.moveCursorBy(dx, dy)
                if (dragMode) {
                    val newPosition = service.getCursorPosition()
                    service.dragCursor(oldPosition.x, oldPosition.y, newPosition.x, newPosition.y)
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
    }

    private fun serviceOrToast(): ControlAccessibilityService? {
        val service = ControlAccessibilityService.current()
        if (service == null) {
            Toast.makeText(this, "Enable accessibility service first", Toast.LENGTH_SHORT).show()
        }
        return service
    }
}
