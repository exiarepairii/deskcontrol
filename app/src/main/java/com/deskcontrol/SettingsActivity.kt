package com.deskcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private var switchBarLabelMap: Map<String, String> = emptyMap()
    private var switchBarIconMap: Map<String, android.graphics.drawable.Drawable> = emptyMap()
    private var switchBarSlotIcons: List<android.widget.ImageView> = emptyList()
    private val pickSwitchBarApp =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val packageName = data.getStringExtra(AppPickerActivity.EXTRA_PICK_PACKAGE) ?: return@registerForActivityResult
            val slotIndex = data.getIntExtra(AppPickerActivity.EXTRA_PICK_SLOT, -1)
            if (slotIndex !in 0..2) return@registerForActivityResult
            SwitchBarStore.setFavoriteSlot(this, slotIndex, packageName)
            refreshSwitchBarSlotLabels()
            ControlAccessibilityService.requestSwitchBarRefresh()
        }

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
        val switchBarEnabledSwitch = findViewById<SwitchMaterial>(R.id.switchSwitchBarEnabled)
        val switchBarScaleSlider = findViewById<Slider>(R.id.sliderSwitchBarScale)
        val switchBarScaleValue = findViewById<TextView>(R.id.switchBarScaleValue)
        val switchBarSlot1 = findViewById<android.view.View>(R.id.switchBarSlot1)
        val switchBarSlot2 = findViewById<android.view.View>(R.id.switchBarSlot2)
        val switchBarSlot3 = findViewById<android.view.View>(R.id.switchBarSlot3)
        val switchBarSlotIcon1 = findViewById<android.widget.ImageView>(R.id.switchBarSlotIcon1)
        val switchBarSlotIcon2 = findViewById<android.widget.ImageView>(R.id.switchBarSlotIcon2)
        val switchBarSlotIcon3 = findViewById<android.widget.ImageView>(R.id.switchBarSlotIcon3)
        val settingsLogsRow = findViewById<android.view.View>(R.id.settingsLogsRow)

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

        touchpadDimLevelSlider.valueFrom = 0.01f
        touchpadDimLevelSlider.valueTo = 0.15f
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

        val apps = LaunchableAppCatalog.load(this)
        switchBarLabelMap = apps.associate { it.packageName to it.label }
        switchBarIconMap = apps.associate { it.packageName to it.icon }
        switchBarSlotIcons = listOf(switchBarSlotIcon1, switchBarSlotIcon2, switchBarSlotIcon3)
        refreshSwitchBarSlotLabels()

        switchBarEnabledSwitch.isChecked = SettingsStore.switchBarEnabled
        updateSwitchBarControlsEnabled(
            SettingsStore.switchBarEnabled,
            switchBarScaleSlider,
            switchBarScaleValue,
            listOf(switchBarSlot1, switchBarSlot2, switchBarSlot3)
        )
        switchBarEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setSwitchBarEnabled(this, isChecked)
            updateSwitchBarControlsEnabled(
                isChecked,
                switchBarScaleSlider,
                switchBarScaleValue,
                listOf(switchBarSlot1, switchBarSlot2, switchBarSlot3)
            )
            requestSwitchBarPreview()
        }

        switchBarScaleSlider.valueFrom = 0.7f
        switchBarScaleSlider.valueTo = 1.3f
        switchBarScaleSlider.stepSize = 0.05f
        switchBarScaleSlider.value = snapToStep(
            SettingsStore.switchBarScale.coerceIn(
                switchBarScaleSlider.valueFrom,
                switchBarScaleSlider.valueTo
            ),
            switchBarScaleSlider.valueFrom,
            switchBarScaleSlider.stepSize
        )
        switchBarScaleValue.text = getString(
            R.string.settings_switch_bar_scale_value,
            (switchBarScaleSlider.value * 100).toInt()
        )
        switchBarScaleSlider.addOnChangeListener { _, value, fromUser ->
            val snapped = snapToStep(value, switchBarScaleSlider.valueFrom, switchBarScaleSlider.stepSize)
            switchBarScaleValue.text = getString(
                R.string.settings_switch_bar_scale_value,
                (snapped * 100).toInt()
            )
            if (fromUser) {
                if (snapped != value) {
                    switchBarScaleSlider.value = snapped
                }
                SettingsStore.setSwitchBarScale(this, snapped)
                requestSwitchBarPreview()
            }
        }
        switchBarScaleSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                requestSwitchBarPreview()
            }

            override fun onStopTrackingTouch(slider: Slider) = Unit
        })

        listOf(switchBarSlot1, switchBarSlot2, switchBarSlot3).forEachIndexed { index, row ->
            row.setOnClickListener {
                requestSwitchBarPreview()
                val intent = Intent(this, AppPickerActivity::class.java).apply {
                    putExtra(AppPickerActivity.EXTRA_PICK_MODE, true)
                    putExtra(
                        AppPickerActivity.EXTRA_PICK_TITLE,
                        getString(R.string.settings_switch_bar_pick_title, index + 1)
                    )
                    putExtra(AppPickerActivity.EXTRA_PICK_SLOT, index)
                }
                pickSwitchBarApp.launch(intent)
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
                requestCursorPreview()
            }
        }
        cursorSizeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                requestCursorPreview()
            }

            override fun onStopTrackingTouch(slider: Slider) = Unit
        })

        cursorColorBlack.setOnClickListener {
            SettingsStore.setCursorColor(this, 0xFF000000.toInt())
            updateCursorColorSelection(cursorColorBlack, cursorColorWhite)
            requestCursorPreview()
        }
        cursorColorWhite.setOnClickListener {
            SettingsStore.setCursorColor(this, 0xFFFFFFFF.toInt())
            updateCursorColorSelection(cursorColorBlack, cursorColorWhite)
            requestCursorPreview()
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
                requestCursorPreview()
            }
        }
        cursorOpacitySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                requestCursorPreview()
            }

            override fun onStopTrackingTouch(slider: Slider) = Unit
        })

        cursorSpeedSlider.valueFrom = 0.7f
        cursorSpeedSlider.valueTo = 1.2f
        cursorSpeedSlider.stepSize = 0.1f
        cursorSpeedSlider.value = TouchpadTuning.baseGain.coerceIn(0.7f, 1.2f)
        cursorSpeedValue.text = getString(R.string.settings_cursor_speed_value, cursorSpeedSlider.value)
        cursorSpeedSlider.addOnChangeListener { _, value, fromUser ->
            cursorSpeedValue.text = getString(R.string.settings_cursor_speed_value, value)
            if (fromUser) {
                SettingsStore.setPointerSpeed(this, value)
                requestCursorPreview()
            }
        }
        cursorSpeedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                requestCursorPreview()
            }

            override fun onStopTrackingTouch(slider: Slider) = Unit
        })

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
            requestCursorPreview()
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
                requestCursorPreview()
            }
        }
        cursorHideDelaySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                requestCursorPreview()
            }

            override fun onStopTrackingTouch(slider: Slider) = Unit
        })

        settingsLogsRow.setOnClickListener {
            startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
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

    override fun onPause() {
        super.onPause()
        ControlAccessibilityService.requestSwitchBarForceVisible(false)
        ControlAccessibilityService.requestCursorForceVisible(false)
    }

    private fun refreshSwitchBarSlotLabels() {
        val slots = SwitchBarStore.getFavoriteSlots(this)
        switchBarSlotIcons.forEachIndexed { index, imageView ->
            val pkg = slots.getOrNull(index)
            if (pkg.isNullOrBlank()) {
                imageView.setImageResource(R.drawable.ic_add)
                imageView.contentDescription =
                    getString(R.string.settings_switch_bar_app_add_slot, index + 1)
            } else {
                val icon = switchBarIconMap[pkg]
                if (icon != null) {
                    imageView.setImageDrawable(icon)
                } else {
                    imageView.setImageResource(R.drawable.ic_add)
                }
                imageView.contentDescription = switchBarLabelMap[pkg] ?: pkg
            }
        }
    }

    private fun updateSwitchBarControlsEnabled(
        enabled: Boolean,
        scaleSlider: Slider,
        scaleValue: TextView,
        slotRows: List<android.view.View>
    ) {
        scaleSlider.isEnabled = enabled
        scaleValue.alpha = if (enabled) 1f else 0.4f
        slotRows.forEach { row ->
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun requestSwitchBarPreview() {
        ControlAccessibilityService.requestSwitchBarForceVisible(true)
    }

    private fun requestCursorPreview() {
        ControlAccessibilityService.requestCursorForceVisible(true)
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun snapToStep(value: Float, start: Float, step: Float): Float {
        val steps = kotlin.math.round((value - start) / step).toInt()
        return start + steps * step
    }


}
