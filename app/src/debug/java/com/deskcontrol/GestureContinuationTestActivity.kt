package com.deskcontrol

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

class GestureContinuationTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            object : View(this) {
                init {
                    setBackgroundColor(Color.BLACK)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    val recorded = RecordedMotionEvent(
                        action = event.actionMasked,
                        x = event.x,
                        y = event.y,
                        eventTime = event.eventTime
                    )
                    events += recorded
                    Log.i(
                        TAG,
                        "run=$runId event action=${recorded.action} " +
                            "x=${recorded.x} y=${recorded.y}"
                    )
                    return true
                }
            }
        )
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCommand(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "run=$runId focus=$hasFocus")
    }

    private fun handleCommand(intent: Intent?) {
        intent ?: return
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_RESET -> {
                runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
                resetEvents()
                Log.i(TAG, "run=$runId reset")
            }

            MODE_ASSERT_PAUSED_REVERSAL -> {
                val snapshot = events.toList()
                val moveY = snapshot
                    .filter { it.action == MotionEvent.ACTION_MOVE }
                    .map { it.y }
                val minimumIndex = moveY.indices.minByOrNull { moveY[it] }
                val reversed = minimumIndex != null &&
                    moveY.drop(minimumIndex + 1).any { it > moveY[minimumIndex] + 8f }
                logAssertion(
                    "paused_reversal",
                    hasCompleteUncancelledGesture(snapshot) && reversed,
                    "moves=$moveY"
                )
            }

            MODE_ASSERT_TWO_GESTURES -> {
                val snapshot = events.toList()
                val downCount = snapshot.count { it.action == MotionEvent.ACTION_DOWN }
                val upCount = snapshot.count { it.action == MotionEvent.ACTION_UP }
                val cancelCount = snapshot.count { it.action == MotionEvent.ACTION_CANCEL }
                logAssertion(
                    "two_gestures",
                    downCount >= 2 && upCount >= 2 && cancelCount == 0,
                    "down=$downCount up=$upCount cancel=$cancelCount"
                )
            }
        }
    }

    private fun hasCompleteUncancelledGesture(events: List<RecordedMotionEvent>): Boolean =
        events.any { it.action == MotionEvent.ACTION_DOWN } &&
            events.any { it.action == MotionEvent.ACTION_UP } &&
            events.none { it.action == MotionEvent.ACTION_CANCEL }

    private fun logAssertion(name: String, passed: Boolean, details: String) {
        Log.i(
            TAG,
            "run=$runId assertion=$name result=${if (passed) "PASS" else "FAIL"} $details"
        )
    }

    companion object {
        private const val TAG = "GestureDeviceTest"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RUN_ID = "run_id"
        const val MODE_RESET = "reset"
        const val MODE_ASSERT_PAUSED_REVERSAL = "assert_paused_reversal"
        const val MODE_ASSERT_TWO_GESTURES = "assert_two_gestures"

        val events = CopyOnWriteArrayList<RecordedMotionEvent>()
        private var runId = ""

        fun resetEvents() {
            events.clear()
        }
    }
}

data class RecordedMotionEvent(
    val action: Int,
    val x: Float,
    val y: Float,
    val eventTime: Long
)
