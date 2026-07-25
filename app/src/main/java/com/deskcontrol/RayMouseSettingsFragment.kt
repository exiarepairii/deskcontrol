package com.deskcontrol

internal class RayMouseSettingsFragment :
    SettingsPageFragment(SettingsPage.RAY_MOUSE) {

    override fun buildPage() {
        val context = requireContext()

        val haptic = context.switchSetting(
            R.string.ray_mouse_haptic_feedback,
            R.string.ray_mouse_haptic_feedback_summary
        )
        haptic.switch.isChecked = SettingsStore.rayHapticFeedbackEnabled
        haptic.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setRayHapticFeedbackEnabled(context, checked)
        }
        pageLayout.addGroup(
            R.string.settings_ray_feedback_group,
            haptic.row
        )

        val horizontal = context.sliderSetting(R.string.ray_mouse_tuning_horizontal_range)
        bindSlider(
            horizontal,
            SettingsSliderRanges.MOTION_HORIZONTAL_RANGE,
            SettingsStore.rayHorizontalRangeDeg,
            { getString(R.string.ray_mouse_tuning_degree_value, it) },
            { SettingsStore.setRayHorizontalRangeDeg(context, it) }
        )
        val vertical = context.sliderSetting(R.string.ray_mouse_tuning_vertical_range)
        bindSlider(
            vertical,
            SettingsSliderRanges.MOTION_VERTICAL_RANGE,
            SettingsStore.rayVerticalRangeDeg,
            { getString(R.string.ray_mouse_tuning_degree_value, it) },
            { SettingsStore.setRayVerticalRangeDeg(context, it) }
        )
        pageLayout.addGroup(
            R.string.settings_ray_range_group,
            horizontal.row,
            vertical.row
        )

        val smoothing = context.sliderSetting(R.string.ray_mouse_tuning_smoothing)
        bindSlider(
            smoothing,
            SettingsSliderRanges.MOTION_SMOOTHING,
            SettingsStore.raySmoothing,
            { getString(R.string.ray_mouse_tuning_decimal_value, it) },
            { SettingsStore.setRaySmoothing(context, it) }
        )
        val interval = context.sliderSetting(R.string.ray_mouse_tuning_emit_interval)
        bindSlider(
            interval,
            SettingsSliderRanges.MOTION_EMIT_INTERVAL,
            SettingsStore.rayMinEmitIntervalMs.toFloat(),
            { getString(R.string.ray_mouse_tuning_ms_value, it) },
            { SettingsStore.setRayMinEmitIntervalMs(context, it.toLong()) }
        )
        val distance = context.sliderSetting(R.string.ray_mouse_tuning_emit_distance)
        bindSlider(
            distance,
            SettingsSliderRanges.MOTION_EMIT_DISTANCE,
            SettingsStore.rayMinEmitDistancePx,
            { getString(R.string.ray_mouse_tuning_px_value, it) },
            { SettingsStore.setRayMinEmitDistancePx(context, it) }
        )
        pageLayout.addGroup(
            R.string.settings_ray_stability_group,
            smoothing.row,
            interval.row,
            distance.row
        )
    }
}
