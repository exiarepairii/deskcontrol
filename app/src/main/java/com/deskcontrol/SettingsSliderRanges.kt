package com.deskcontrol

import kotlin.math.round

internal data class SettingsSliderRange(
    val start: Float,
    val end: Float,
    val step: Float
) {
    fun snap(value: Float): Float {
        val finiteValue = if (value.isFinite()) value else start
        val steps = round((finiteValue.coerceIn(start, end) - start) / step).toInt()
        return (round((start + steps * step) * 1000f) / 1000f).coerceIn(start, end)
    }
}

internal object SettingsSliderRanges {
    val DIM_LEVEL = SettingsSliderRange(0.01f, 0.15f, 0.01f)
    val DOCK_SCALE = SettingsSliderRange(0.7f, 1.3f, 0.05f)

    val TOUCHPAD_SCROLL_SPEED = SettingsSliderRange(0.5f, 2f, 0.1f)
    val TOUCHPAD_SCROLL_DISTANCE = SettingsSliderRange(3f, 12f, 1f)
    val TOUCHPAD_DIRECT_GAIN = SettingsSliderRange(0.5f, 2f, 0.1f)
    val TOUCHPAD_DIRECT_STEP = SettingsSliderRange(16f, 64f, 4f)
    val TOUCHPAD_DRAG_BOOST = SettingsSliderRange(0.8f, 2f, 0.1f)

    val MOTION_HORIZONTAL_RANGE = SettingsSliderRange(10f, 40f, 1f)
    val MOTION_VERTICAL_RANGE = SettingsSliderRange(8f, 40f, 1f)
    val MOTION_SMOOTHING = SettingsSliderRange(0.05f, 0.8f, 0.05f)
    val MOTION_EMIT_INTERVAL = SettingsSliderRange(0f, 32f, 4f)
    val MOTION_EMIT_DISTANCE = SettingsSliderRange(0f, 5f, 0.5f)

    val CURSOR_SCALE = SettingsSliderRange(0.5f, 2f, 0.1f)
    val CURSOR_OPACITY = SettingsSliderRange(0.6f, 1f, 0.1f)
    val CURSOR_SPEED = SettingsSliderRange(0.4f, 1.6f, 0.1f)
    val CURSOR_HIDE_DELAY_SECONDS = SettingsSliderRange(1f, 5f, 0.5f)
}
