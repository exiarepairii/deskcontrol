package com.deskcontrol

import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors

internal class CursorSettingsFragment :
    SettingsPageFragment(SettingsPage.CURSOR) {

    override fun buildPage() {
        val context = requireContext()

        val size = context.sliderSetting(R.string.settings_cursor_size)
        bindSlider(
            size,
            SettingsSliderRanges.CURSOR_SCALE,
            SettingsStore.cursorScale,
            { getString(R.string.settings_cursor_scale_value, it) },
            {
                SettingsStore.setCursorScale(context, it)
                showPreview()
            }
        )
        size.slider.addOnSliderTouchListener(previewTouchListener(::showPreview))

        val colors = context.cursorColorSetting()
        colors.blackTarget.setOnClickListener {
            SettingsStore.setCursorColor(context, 0xFF000000.toInt())
            updateColorSelection(colors)
            showPreview()
        }
        colors.whiteTarget.setOnClickListener {
            SettingsStore.setCursorColor(context, 0xFFFFFFFF.toInt())
            updateColorSelection(colors)
            showPreview()
        }
        updateColorSelection(colors)

        val opacity = context.sliderSetting(R.string.settings_cursor_opacity)
        bindSlider(
            opacity,
            SettingsSliderRanges.CURSOR_OPACITY,
            SettingsStore.cursorAlpha,
            {
                getString(R.string.settings_cursor_opacity_value, (it * 100).toInt())
            },
            {
                SettingsStore.setCursorAlpha(context, it)
                showPreview()
            }
        )
        opacity.slider.addOnSliderTouchListener(previewTouchListener(::showPreview))

        val speed = context.sliderSetting(R.string.settings_cursor_speed)
        bindSlider(
            speed,
            SettingsSliderRanges.CURSOR_SPEED,
            TouchpadTuning.baseGain,
            { getString(R.string.settings_cursor_speed_value, it) },
            {
                SettingsStore.setPointerSpeed(context, it)
                showPreview()
            }
        )
        speed.slider.addOnSliderTouchListener(previewTouchListener(::showPreview))

        pageLayout.addGroup(
            R.string.settings_cursor_appearance_group,
            size.row,
            colors.row,
            opacity.row,
            speed.row
        )

        val autoHide = context.switchSetting(R.string.settings_cursor_auto_hide)
        val hideDelay = context.sliderSetting(R.string.settings_hide_delay)
        val initialDelay = if (SettingsStore.cursorHideDelayMs > 0L) {
            SettingsStore.cursorHideDelayMs / 1000f
        } else {
            2.5f
        }
        bindSlider(
            hideDelay,
            SettingsSliderRanges.CURSOR_HIDE_DELAY_SECONDS,
            initialDelay,
            { getString(R.string.settings_cursor_hide_delay_value, it) },
            {
                SettingsStore.setCursorHideDelay(context, (it * 1000).toLong())
                showPreview()
            }
        )
        hideDelay.slider.addOnSliderTouchListener(previewTouchListener(::showPreview))
        autoHide.switch.isChecked = SettingsStore.cursorHideDelayMs > 0L
        hideDelay.row.isVisible = autoHide.switch.isChecked
        autoHide.switch.setOnCheckedChangeListener { _, checked ->
            hideDelay.row.isVisible = checked
            SettingsStore.setCursorHideDelay(
                context,
                if (checked) (hideDelay.slider.value * 1000).toLong() else 0L
            )
            showPreview()
        }
        pageLayout.addGroup(
            R.string.settings_cursor_visibility_group,
            autoHide.row,
            hideDelay.row
        )
    }

    private fun updateColorSelection(setting: ColorSetting) {
        val accent = MaterialColors.getColor(
            setting.black,
            com.google.android.material.R.attr.colorPrimary,
            0
        )
        val neutral = MaterialColors.getColor(
            setting.black,
            com.google.android.material.R.attr.colorOutline,
            0
        )
        val blackSelected = SettingsStore.cursorColor == 0xFF000000.toInt()
        setting.black.strokeWidth = requireContext().dp(if (blackSelected) 2 else 1)
        setting.white.strokeWidth = requireContext().dp(if (blackSelected) 1 else 2)
        setting.black.strokeColor = if (blackSelected) accent else neutral
        setting.white.strokeColor = if (blackSelected) neutral else accent
        setting.black.isChecked = blackSelected
        setting.white.isChecked = !blackSelected
    }

    private fun showPreview() {
        ControlAccessibilityService.requestCursorForceVisible(true)
    }

    override fun onPause() {
        super.onPause()
        ControlAccessibilityService.requestCursorForceVisible(false)
    }
}
