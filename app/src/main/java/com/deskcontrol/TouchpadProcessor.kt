package com.deskcontrol

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class TouchpadProcessor(private val tuning: TouchpadTuning) {
    data class Output(val dx: Float, val dy: Float)

    private var lastEventTime = 0L
    private var smoothDx = 0f
    private var smoothDy = 0f

    fun reset() {
        lastEventTime = 0L
        smoothDx = 0f
        smoothDy = 0f
    }

    fun process(rawDx: Float, rawDy: Float, eventTime: Long): Output {
        if (lastEventTime == 0L) {
            lastEventTime = eventTime
            return Output(0f, 0f)
        }
        val dtMs = max(1L, eventTime - lastEventTime).toFloat()
        lastEventTime = eventTime

        smoothDx = smoothDx + tuning.emaAlpha * (rawDx - smoothDx)
        smoothDy = smoothDy + tuning.emaAlpha * (rawDy - smoothDy)

        val distance = hypot(smoothDx.toDouble(), smoothDy.toDouble()).toFloat()
        val deadzone = tuning.jitterThresholdPx
        if (distance <= deadzone) {
            return Output(0f, 0f)
        }
        val scale = (distance - deadzone) / distance

        val speed = distance / dtMs
        val normalized = (speed / tuning.speedForMaxAccel).coerceAtMost(1f)
        val accel = 1f + normalized * tuning.maxAccelGain
        val gain = tuning.baseGain * accel

        return Output(smoothDx * scale * gain, smoothDy * scale * gain)
    }
}
