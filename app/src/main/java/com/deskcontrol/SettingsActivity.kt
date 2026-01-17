package com.deskcontrol

import android.media.projection.MediaProjectionConfig
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<android.view.View>(R.id.settingsRoot)
        applyEdgeToEdgePadding(root)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.settingsToolbar)
        toolbar.title = getString(R.string.settings_title)
        toolbar.setNavigationOnClickListener { finish() }

        bindSettings()
    }

    private fun bindSettings() {
        val themeToggleGroup =
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                R.id.themeToggleGroup
            )
        val themeSystem = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnThemeSystem
        )
        val themeDark = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnThemeDark
        )
        val themeLight = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnThemeLight
        )
        val cursorColorBlack = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cursorColorBlack)
        val cursorColorWhite = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cursorColorWhite)
        val cursorSizeSlider = findViewById<Slider>(R.id.sliderCursorSize)
        val cursorSizeValue = findViewById<android.widget.TextView>(R.id.cursorSizeValue)
        val languageToggleGroup =
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                R.id.languageToggleGroup
            )
        val languageSystem = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnLanguageSystem
        )
        val languageEnglish = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnLanguageEnglish
        )
        val languageChinese = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnLanguageChinese
        )
        val cursorOpacitySlider = findViewById<Slider>(R.id.sliderCursorOpacity)
        val cursorOpacityValue = findViewById<android.widget.TextView>(R.id.cursorOpacityValue)
        val cursorSpeedSlider = findViewById<Slider>(R.id.sliderCursorSpeed)
        val cursorSpeedValue = findViewById<android.widget.TextView>(R.id.cursorSpeedValue)
        val cursorHideSwitch = findViewById<SwitchMaterial>(R.id.switchCursorHide)
        val keepScreenOnSwitch = findViewById<SwitchMaterial>(R.id.switchKeepScreenOn)
        val touchpadAutoDimSwitch = findViewById<SwitchMaterial>(R.id.switchTouchpadAutoDim)
        val touchpadDimLevelValue = findViewById<android.widget.TextView>(R.id.touchpadDimLevelValue)
        val touchpadDimLevelSlider = findViewById<Slider>(R.id.sliderTouchpadDimLevel)
        val cursorHideOptions = findViewById<android.view.View>(R.id.cursorHideOptions)
        val cursorHideDelayValue = findViewById<android.widget.TextView>(R.id.cursorHideDelayValue)
        val cursorHideDelaySlider = findViewById<Slider>(R.id.sliderCursorHideDelay)

        when (SettingsStore.nightMode) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES ->
                themeToggleGroup.check(themeDark.id)
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO ->
                themeToggleGroup.check(themeLight.id)
            else ->
                themeToggleGroup.check(themeSystem.id)
        }
        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                themeDark.id -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                themeLight.id -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != SettingsStore.nightMode) {
                SettingsStore.setNightMode(this, mode)
                recreate()
            }
        }

        keepScreenOnSwitch.isChecked = SettingsStore.keepScreenOn
        keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setKeepScreenOn(this, isChecked)
        }

        touchpadAutoDimSwitch.isChecked = SettingsStore.touchpadAutoDimEnabled
        touchpadAutoDimSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setTouchpadAutoDimEnabled(this, isChecked)
        }

        touchpadDimLevelSlider.valueFrom = 0.05f
        touchpadDimLevelSlider.valueTo = 0.30f
        touchpadDimLevelSlider.stepSize = 0.01f
        touchpadDimLevelSlider.value = SettingsStore.touchpadDimLevel.coerceIn(
            touchpadDimLevelSlider.valueFrom,
            touchpadDimLevelSlider.valueTo
        )
        touchpadDimLevelValue.text = getString(
            R.string.settings_touchpad_dim_level_value,
            (touchpadDimLevelSlider.value * 100).toInt()
        )
        touchpadDimLevelSlider.addOnChangeListener { _, value, fromUser ->
            touchpadDimLevelValue.text = getString(
                R.string.settings_touchpad_dim_level_value,
                (value * 100).toInt()
            )
            if (fromUser) {
                SettingsStore.setTouchpadDimLevel(this, value)
            }
        }

        when {
            SettingsStore.isLanguageEnglish() -> languageToggleGroup.check(languageEnglish.id)
            SettingsStore.isLanguageChinese() -> languageToggleGroup.check(languageChinese.id)
            else -> languageToggleGroup.check(languageSystem.id)
        }
        languageToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val previous = SettingsStore.appLanguageTag
            when (checkedId) {
                languageEnglish.id -> SettingsStore.setAppLanguage(this, "en")
                languageChinese.id -> SettingsStore.setAppLanguage(this, "zh-CN")
                else -> SettingsStore.setAppLanguage(this, "system")
            }
            if (previous != SettingsStore.appLanguageTag) {
                recreate()
            }
        }

        cursorSizeSlider.valueFrom = 0.5f
        cursorSizeSlider.valueTo = 3.0f
        cursorSizeSlider.stepSize = 0.1f
        cursorSizeSlider.value = snapToStep(
            SettingsStore.cursorScale.coerceIn(0.5f, 3.0f),
            cursorSizeSlider.valueFrom,
            cursorSizeSlider.stepSize
        )
        cursorSizeValue.text = getString(R.string.settings_cursor_scale_value, cursorSizeSlider.value)
        cursorSizeSlider.addOnChangeListener { _, value, fromUser ->
            val snapped = snapToStep(value, cursorSizeSlider.valueFrom, cursorSizeSlider.stepSize)
            cursorSizeValue.text = getString(R.string.settings_cursor_scale_value, snapped)
            if (fromUser) {
                if (snapped != value) {
                    cursorSizeSlider.value = snapped
                }
                SettingsStore.setCursorScale(this, snapped)
            }
        }

        cursorColorBlack.setOnClickListener {
            SettingsStore.setCursorColor(this, 0xFF000000.toInt())
            updateCursorColorSelection(cursorColorBlack, cursorColorWhite)
        }
        cursorColorWhite.setOnClickListener {
            SettingsStore.setCursorColor(this, 0xFFFFFFFF.toInt())
            updateCursorColorSelection(cursorColorBlack, cursorColorWhite)
        }
        updateCursorColorSelection(cursorColorBlack, cursorColorWhite)

        cursorOpacitySlider.valueFrom = 0.6f
        cursorOpacitySlider.valueTo = 1.0f
        cursorOpacitySlider.stepSize = 0.1f
        cursorOpacitySlider.value = SettingsStore.cursorAlpha.coerceIn(0.6f, 1.0f)
        cursorOpacityValue.text = getString(
            R.string.settings_cursor_opacity_value,
            (cursorOpacitySlider.value * 100).toInt()
        )
        cursorOpacitySlider.addOnChangeListener { _, value, fromUser ->
            cursorOpacityValue.text = getString(
                R.string.settings_cursor_opacity_value,
                (value * 100).toInt()
            )
            if (fromUser) {
                SettingsStore.setCursorAlpha(this, value)
            }
        }

        cursorSpeedSlider.valueFrom = 0.7f
        cursorSpeedSlider.valueTo = 1.2f
        cursorSpeedSlider.stepSize = 0.1f
        cursorSpeedSlider.value = TouchpadTuning.baseGain.coerceIn(0.7f, 1.2f)
        cursorSpeedValue.text = getString(R.string.settings_cursor_speed_value, cursorSpeedSlider.value)
        cursorSpeedSlider.addOnChangeListener { _, value, fromUser ->
            cursorSpeedValue.text = getString(R.string.settings_cursor_speed_value, value)
            if (fromUser) {
                SettingsStore.setPointerSpeed(this, value)
            }
        }

        val hideEnabled = SettingsStore.cursorHideDelayMs > 0
        cursorHideSwitch.isChecked = hideEnabled
        cursorHideOptions.isVisible = hideEnabled
        cursorHideSwitch.setOnCheckedChangeListener { _, isChecked ->
            cursorHideOptions.isVisible = isChecked
            if (!isChecked) {
                SettingsStore.setCursorHideDelay(this, 0L)
            } else if (SettingsStore.cursorHideDelayMs == 0L) {
                SettingsStore.setCursorHideDelay(this, 2500L)
            }
            updateHideDelay(cursorHideDelayValue, cursorHideDelaySlider)
        }

        cursorHideDelaySlider.valueFrom = 1.0f
        cursorHideDelaySlider.valueTo = 5.0f
        cursorHideDelaySlider.stepSize = 0.5f
        cursorHideDelaySlider.value = (SettingsStore.cursorHideDelayMs / 1000f)
            .coerceIn(1.0f, 5.0f)
        updateHideDelay(cursorHideDelayValue, cursorHideDelaySlider)
        cursorHideDelaySlider.addOnChangeListener { _, value, fromUser ->
            cursorHideDelayValue.text = getString(R.string.settings_cursor_hide_delay_value, value)
            if (fromUser) {
                SettingsStore.setCursorHideDelay(this, (value * 1000).toLong())
            }
        }
    }

    private fun updateCursorColorSelection(
        black: com.google.android.material.card.MaterialCardView,
        white: com.google.android.material.card.MaterialCardView
    ) {
        val accent = MaterialColors.getColor(
            black,
            com.google.android.material.R.attr.colorPrimary,
            0
        )
        val neutral = MaterialColors.getColor(
            black,
            com.google.android.material.R.attr.colorOutline,
            0
        )
        val selectedBlack = SettingsStore.cursorColor == 0xFF000000.toInt()
        black.strokeWidth = if (selectedBlack) dpToPx(2) else dpToPx(1)
        white.strokeWidth = if (!selectedBlack) dpToPx(2) else dpToPx(1)
        black.strokeColor = if (selectedBlack) accent else neutral
        white.strokeColor = if (!selectedBlack) accent else neutral
    }

    private fun updateHideDelay(
        label: android.widget.TextView,
        slider: Slider
    ) {
        label.text = getString(R.string.settings_cursor_hide_delay_value, slider.value)
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun snapToStep(value: Float, start: Float, step: Float): Float {
        val steps = kotlin.math.round((value - start) / step).toInt()
        return start + steps * step
    }


}
