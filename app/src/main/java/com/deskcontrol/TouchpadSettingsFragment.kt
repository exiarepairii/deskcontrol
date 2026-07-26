package com.deskcontrol

import androidx.core.view.isVisible

internal class TouchpadSettingsFragment :
    SettingsPageFragment(SettingsPage.TOUCHPAD) {

    override fun buildPage() {
        val context = requireContext()

        val autoFocus = context.switchSetting(
            R.string.settings_touchpad_auto_focus,
            R.string.settings_touchpad_auto_focus_summary
        )
        autoFocus.switch.isChecked = SettingsStore.touchpadAutoFocusEnabled
        autoFocus.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setTouchpadAutoFocusEnabled(context, checked)
        }
        pageLayout.addGroup(
            R.string.settings_touchpad_back_section,
            autoFocus.row
        )

        val directMode = context.switchSetting(
            R.string.settings_touchpad_scroll_gesture,
            R.string.settings_touchpad_scroll_gesture_summary
        )
        val naturalDirection = context.switchSetting(
            R.string.settings_touchpad_scroll_invert,
            R.string.settings_touchpad_scroll_invert_summary
        )
        naturalDirection.switch.isChecked = SettingsStore.touchpadScrollInverted
        naturalDirection.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setTouchpadScrollInverted(context, checked)
        }

        val classicSpeed = context.sliderSetting(
            R.string.settings_touchpad_scroll_speed,
            R.string.settings_touchpad_scroll_speed_summary
        )
        bindSlider(
            classicSpeed,
            range = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED,
            currentValue = SettingsStore.touchpadScrollSpeed,
            formatValue = {
                getString(R.string.settings_touchpad_scroll_speed_value, it)
            }
        ) { SettingsStore.setTouchpadScrollSpeed(context, it) }

        val classicDistance = context.sliderSetting(
            R.string.settings_touchpad_scroll_distance,
            R.string.settings_touchpad_scroll_distance_summary
        )
        bindSlider(
            classicDistance,
            range = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE,
            currentValue = SettingsStore.touchpadScrollStepDp,
            formatValue = {
                getString(R.string.settings_touchpad_scroll_distance_value, it)
            }
        ) { SettingsStore.setTouchpadScrollStepDp(context, it) }

        val directGain = context.sliderSetting(
            R.string.settings_touchpad_scroll_gesture_gain,
            R.string.settings_touchpad_scroll_gesture_gain_summary
        )
        bindSlider(
            directGain,
            range = SettingsSliderRanges.TOUCHPAD_DIRECT_GAIN,
            currentValue = SettingsStore.touchpadDirectScrollGain,
            formatValue = {
                getString(R.string.settings_touchpad_scroll_gesture_gain_value, it)
            }
        ) { SettingsStore.setTouchpadDirectScrollGain(context, it) }

        val directStep = context.sliderSetting(
            R.string.settings_touchpad_scroll_gesture_step,
            R.string.settings_touchpad_scroll_gesture_step_summary
        )
        bindSlider(
            directStep,
            range = SettingsSliderRanges.TOUCHPAD_DIRECT_STEP,
            currentValue = SettingsStore.touchpadDirectScrollStepDp,
            formatValue = {
                getString(R.string.settings_touchpad_scroll_gesture_step_value, it)
            }
        ) { SettingsStore.setTouchpadDirectScrollStepDp(context, it) }

        val updateMode: (Boolean) -> Unit = { direct ->
            naturalDirection.row.isVisible = !direct
            classicSpeed.row.isVisible = !direct
            classicDistance.row.isVisible = !direct
            directGain.row.isVisible = direct
            directStep.row.isVisible = direct
        }
        directMode.switch.isChecked = SettingsStore.touchpadDirectScrollGestureEnabled
        updateMode(directMode.switch.isChecked)
        directMode.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setTouchpadDirectScrollGestureEnabled(context, checked)
            updateMode(checked)
        }
        pageLayout.addGroup(
            R.string.settings_touchpad_scrolling_group,
            naturalDirection.row,
            classicSpeed.row,
            classicDistance.row,
            directGain.row,
            directStep.row,
            directMode.row
        )

        val dragBoost = context.sliderSetting(R.string.settings_touchpad_drag_boost)
        bindSlider(
            dragBoost,
            range = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST,
            currentValue = TouchpadTuning.dragBoost,
            formatValue = {
                getString(R.string.settings_touchpad_drag_boost_value, it)
            }
        ) { SettingsStore.setTouchpadDragBoost(context, it) }
        pageLayout.addGroup(
            R.string.settings_touchpad_gestures_group,
            dragBoost.row
        )
    }
}
