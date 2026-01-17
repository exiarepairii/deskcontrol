package com.deskcontrol

class TouchpadScrollProcessor(private val tuning: TouchpadTuning) {
    private var accumulator = 0f

    fun reset() {
        accumulator = 0f
    }

    fun consume(deltaY: Float, onStep: (steps: Int) -> Unit) {
        accumulator += deltaY
        val stepSize = tuning.scrollStepPx
        if (stepSize <= 0f) return
        val steps = (accumulator / stepSize).toInt()
        if (steps != 0) {
            accumulator -= steps * stepSize
            onStep(steps)
        }
    }
}
