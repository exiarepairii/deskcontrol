package com.deskcontrol

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
        toolbar.title = "Settings"
        toolbar.setNavigationOnClickListener { finish() }

        bindSettings()
    }

    private fun bindSettings() {
        val darkModeSwitch = findViewById<SwitchMaterial>(R.id.switchDarkMode)
        val cursorColorBlack = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cursorColorBlack)
        val cursorColorWhite = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cursorColorWhite)
        val cursorSizeSlider = findViewById<Slider>(R.id.sliderCursorSize)
        val cursorSizeValue = findViewById<android.widget.TextView>(R.id.cursorSizeValue)
        val cursorOpacitySlider = findViewById<Slider>(R.id.sliderCursorOpacity)
        val cursorOpacityValue = findViewById<android.widget.TextView>(R.id.cursorOpacityValue)
        val cursorSpeedSlider = findViewById<Slider>(R.id.sliderCursorSpeed)
        val cursorSpeedValue = findViewById<android.widget.TextView>(R.id.cursorSpeedValue)
        val cursorHideSwitch = findViewById<SwitchMaterial>(R.id.switchCursorHide)
        val cursorHideOptions = findViewById<android.view.View>(R.id.cursorHideOptions)
        val cursorHideDelayValue = findViewById<android.widget.TextView>(R.id.cursorHideDelayValue)
        val cursorHideDelaySlider = findViewById<Slider>(R.id.sliderCursorHideDelay)

        darkModeSwitch.isChecked =
            SettingsStore.nightMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            } else {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            }
            SettingsStore.setNightMode(this, mode)
        }

        cursorSizeSlider.valueFrom = 0.5f
        cursorSizeSlider.valueTo = 3.0f
        cursorSizeSlider.stepSize = 0.1f
        cursorSizeSlider.value = snapToStep(
            SettingsStore.cursorScale.coerceIn(0.5f, 3.0f),
            cursorSizeSlider.valueFrom,
            cursorSizeSlider.stepSize
        )
        cursorSizeValue.text = String.format("%.1fx", cursorSizeSlider.value)
        cursorSizeSlider.addOnChangeListener { _, value, fromUser ->
            val snapped = snapToStep(value, cursorSizeSlider.valueFrom, cursorSizeSlider.stepSize)
            cursorSizeValue.text = String.format("%.1fx", snapped)
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
        cursorOpacityValue.text = "${(cursorOpacitySlider.value * 100).toInt()}%"
        cursorOpacitySlider.addOnChangeListener { _, value, fromUser ->
            cursorOpacityValue.text = "${(value * 100).toInt()}%"
            if (fromUser) {
                SettingsStore.setCursorAlpha(this, value)
            }
        }

        cursorSpeedSlider.valueFrom = 0.7f
        cursorSpeedSlider.valueTo = 1.2f
        cursorSpeedSlider.stepSize = 0.1f
        cursorSpeedSlider.value = TouchpadTuning.baseGain.coerceIn(0.7f, 1.2f)
        cursorSpeedValue.text = String.format("%.1fx", cursorSpeedSlider.value)
        cursorSpeedSlider.addOnChangeListener { _, value, fromUser ->
            cursorSpeedValue.text = String.format("%.1fx", value)
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
            cursorHideDelayValue.text = String.format("%.1fs", value)
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
        label.text = String.format("%.1fs", slider.value)
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun snapToStep(value: Float, start: Float, step: Float): Float {
        val steps = kotlin.math.round((value - start) / step).toInt()
        return start + steps * step
    }
}
