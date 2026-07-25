package com.deskcontrol

import androidx.core.view.isVisible

internal class DisplaySettingsFragment :
    SettingsPageFragment(SettingsPage.DISPLAY) {

    override fun buildPage() {
        val context = requireContext()
        val keepScreenOn = context.switchSetting(
            R.string.settings_keep_screen_on,
            R.string.settings_keep_screen_on_summary
        )
        keepScreenOn.switch.isChecked = SettingsStore.keepScreenOn
        keepScreenOn.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setKeepScreenOn(context, checked)
        }

        val autoDim = context.switchSetting(
            R.string.settings_touchpad_auto_dim,
            R.string.settings_touchpad_auto_dim_summary
        )
        val dimLevel = context.sliderSetting(R.string.settings_touchpad_dim_level)
        bindSlider(
            dimLevel,
            range = SettingsSliderRanges.DIM_LEVEL,
            currentValue = SettingsStore.touchpadDimLevel,
            formatValue = {
                getString(R.string.settings_touchpad_dim_level_value, (it * 100).toInt())
            }
        ) {
            SettingsStore.setTouchpadDimLevel(context, it)
        }
        autoDim.switch.isChecked = SettingsStore.touchpadAutoDimEnabled
        dimLevel.row.isVisible = autoDim.switch.isChecked
        autoDim.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setTouchpadAutoDimEnabled(context, checked)
            dimLevel.row.isVisible = checked
        }

        pageLayout.addGroup(
            R.string.settings_display_behavior_group,
            keepScreenOn.row,
            autoDim.row,
            dimLevel.row
        )
    }
}
