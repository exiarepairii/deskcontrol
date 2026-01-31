package com.deskcontrol

import android.content.Context

object SettingsStore {
    private const val PREFS_NAME = "deskcontrol_settings"
    private const val PREF_APP_LANGUAGE = "app_language"
    private const val LANGUAGE_SYSTEM = "system"
    private const val LANGUAGE_ENGLISH = "en"
    private const val LANGUAGE_CHINESE = "zh-CN"
    private const val BASE_SCROLL_SPEED = 0.4f

    var nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        private set
    var cursorScale = 1.0f
        private set
    var cursorAlpha = 1.0f
        private set
    var cursorHideDelayMs = 2500L
        private set
    var cursorColor = 0xFFFFFFFF.toInt()
        private set
    var appLanguageTag = LANGUAGE_SYSTEM
        private set
    var keepScreenOn = true
        private set
    var touchpadAutoDimEnabled = true
        private set
    var touchpadDimLevel = 0.03f
        private set
    var touchpadIntroShown = false
        private set
    var touchpadScrollSpeed = 1.0f
        private set
    private const val PREF_SCROLL_SPEED_SCALE = "tp_scroll_scale"
    private const val PREF_SCROLL_SPEED_LEGACY = "tp_scroll_speed"
    var touchpadScrollInverted = true
        private set
    var switchBarEnabled = true
        private set
    var switchBarScale = 1.0f
        private set
    var motionSensitivity = 1.0f
        private set
    var motionSmoothingAlpha = 0.22f
        private set
    var motionDeadzone = 0.03f
        private set
    var motionQuickRangeDeg = 12f
        private set
    var motionCalibrationValid = false
        private set
    var motionCalibrationYawTopLeft = 0f
        private set
    var motionCalibrationPitchTopLeft = 0f
        private set
    var motionCalibrationYawBottomRight = 0f
        private set
    var motionCalibrationPitchBottomRight = 0f
        private set
    var motionCalibrationYawOffset = 0f
        private set
    var motionCalibrationPitchOffset = 0f
        private set
    var motionCalibrationReference: FloatArray? = null
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        nightMode = prefs.getInt("night_mode", nightMode)
        cursorScale = prefs.getFloat("cursor_scale", cursorScale)
        cursorAlpha = prefs.getFloat("cursor_alpha", cursorAlpha)
        cursorHideDelayMs = prefs.getLong("cursor_hide_delay_ms", cursorHideDelayMs)
        cursorColor = prefs.getInt("cursor_color", cursorColor)
        appLanguageTag = prefs.getString(PREF_APP_LANGUAGE, appLanguageTag) ?: LANGUAGE_SYSTEM
        keepScreenOn = prefs.getBoolean("keep_screen_on", keepScreenOn)
        touchpadAutoDimEnabled = prefs.getBoolean("touchpad_auto_dim", touchpadAutoDimEnabled)
        touchpadDimLevel = prefs.getFloat("touchpad_dim_level", touchpadDimLevel)
        touchpadIntroShown = prefs.getBoolean("touchpad_intro_shown", touchpadIntroShown)
        touchpadScrollSpeed = if (prefs.contains(PREF_SCROLL_SPEED_SCALE)) {
            prefs.getFloat(PREF_SCROLL_SPEED_SCALE, touchpadScrollSpeed)
        } else if (prefs.contains(PREF_SCROLL_SPEED_LEGACY)) {
            val legacy = prefs.getFloat(PREF_SCROLL_SPEED_LEGACY, BASE_SCROLL_SPEED)
            (legacy / BASE_SCROLL_SPEED)
        } else {
            touchpadScrollSpeed
        }.coerceIn(0.5f, 3.0f)
        touchpadScrollInverted = prefs.getBoolean("tp_scroll_invert", touchpadScrollInverted)
        switchBarEnabled = prefs.getBoolean("switch_bar_enabled", switchBarEnabled)
        switchBarScale = prefs.getFloat("switch_bar_scale", switchBarScale)
            .coerceIn(0.7f, 1.3f)
        motionSensitivity = prefs.getFloat("motion_sensitivity", motionSensitivity)
            .coerceIn(0.6f, 2.0f)
        motionSmoothingAlpha = prefs.getFloat("motion_smoothing", motionSmoothingAlpha)
            .coerceIn(0.05f, 0.6f)
        motionDeadzone = prefs.getFloat("motion_deadzone", motionDeadzone)
            .coerceIn(0.01f, 0.08f)
        motionQuickRangeDeg = prefs.getFloat("motion_quick_range_deg", motionQuickRangeDeg)
            .coerceIn(6f, 24f)
        motionCalibrationValid = prefs.getBoolean("motion_calibration_valid", motionCalibrationValid)
        motionCalibrationYawTopLeft =
            prefs.getFloat("motion_calibration_yaw_top_left", motionCalibrationYawTopLeft)
        motionCalibrationPitchTopLeft =
            prefs.getFloat("motion_calibration_pitch_top_left", motionCalibrationPitchTopLeft)
        motionCalibrationYawBottomRight =
            prefs.getFloat("motion_calibration_yaw_bottom_right", motionCalibrationYawBottomRight)
        motionCalibrationPitchBottomRight =
            prefs.getFloat("motion_calibration_pitch_bottom_right", motionCalibrationPitchBottomRight)
        motionCalibrationYawOffset =
            prefs.getFloat("motion_calibration_yaw_offset", motionCalibrationYawOffset)
        motionCalibrationPitchOffset =
            prefs.getFloat("motion_calibration_pitch_offset", motionCalibrationPitchOffset)
        motionCalibrationReference = decodeMatrix(
            prefs.getString("motion_calibration_reference", null)
        )

        TouchpadTuning.baseGain = prefs.getFloat("tp_base_gain", TouchpadTuning.baseGain)
        TouchpadTuning.maxAccelGain = prefs.getFloat("tp_max_accel", TouchpadTuning.maxAccelGain)
        TouchpadTuning.speedForMaxAccel = prefs.getFloat("tp_speed_max", TouchpadTuning.speedForMaxAccel)
        TouchpadTuning.jitterThresholdPx = prefs.getFloat("tp_jitter", TouchpadTuning.jitterThresholdPx)
        TouchpadTuning.emaAlpha = prefs.getFloat("tp_smoothing", TouchpadTuning.emaAlpha)
        TouchpadTuning.scrollStepPx = prefs.getFloat("tp_scroll_step", TouchpadTuning.scrollStepPx)
        TouchpadTuning.dragBoost = prefs.getFloat("tp_drag_boost", TouchpadTuning.dragBoost)
    }

    fun setNightMode(context: Context, value: Int) {
        nightMode = value
        persist(context) { putInt("night_mode", value) }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(value)
    }

    fun setCursorScale(context: Context, value: Float) {
        cursorScale = value
        persist(context) { putFloat("cursor_scale", value) }
        ControlAccessibilityService.requestCursorAppearanceRefresh()
    }

    fun setCursorAlpha(context: Context, value: Float) {
        cursorAlpha = value
        persist(context) { putFloat("cursor_alpha", value) }
        ControlAccessibilityService.requestCursorAppearanceRefresh()
    }

    fun setCursorColor(context: Context, value: Int) {
        cursorColor = value
        persist(context) { putInt("cursor_color", value) }
        ControlAccessibilityService.requestCursorAppearanceRefresh()
    }

    fun setCursorHideDelay(context: Context, valueMs: Long) {
        cursorHideDelayMs = valueMs
        persist(context) { putLong("cursor_hide_delay_ms", valueMs) }
    }

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        keepScreenOn = enabled
        persist(context) { putBoolean("keep_screen_on", enabled) }
    }

    fun setTouchpadAutoDimEnabled(context: Context, enabled: Boolean) {
        touchpadAutoDimEnabled = enabled
        persist(context) { putBoolean("touchpad_auto_dim", enabled) }
    }

    fun setTouchpadDimLevel(context: Context, value: Float) {
        val clamped = value.coerceIn(0.01f, 0.15f)
        touchpadDimLevel = clamped
        persist(context) { putFloat("touchpad_dim_level", clamped) }
    }

    fun setTouchpadIntroShown(context: Context) {
        touchpadIntroShown = true
        persist(context) { putBoolean("touchpad_intro_shown", true) }
    }

    fun setTouchpadScrollSpeed(context: Context, value: Float) {
        val clamped = value.coerceIn(0.5f, 3.0f)
        touchpadScrollSpeed = clamped
        persist(context) { putFloat(PREF_SCROLL_SPEED_SCALE, clamped) }
    }

    fun getTouchpadScrollBaseSpeed(): Float = BASE_SCROLL_SPEED

    fun setTouchpadScrollInverted(context: Context, inverted: Boolean) {
        touchpadScrollInverted = inverted
        persist(context) { putBoolean("tp_scroll_invert", inverted) }
    }

    fun setSwitchBarEnabled(context: Context, enabled: Boolean) {
        switchBarEnabled = enabled
        persist(context) { putBoolean("switch_bar_enabled", enabled) }
        ControlAccessibilityService.requestSwitchBarRefresh()
    }

    fun setSwitchBarScale(context: Context, value: Float) {
        val clamped = value.coerceIn(0.7f, 1.3f)
        switchBarScale = clamped
        persist(context) { putFloat("switch_bar_scale", clamped) }
        ControlAccessibilityService.requestSwitchBarRefresh()
    }

    fun setMotionSensitivity(context: Context, value: Float) {
        val clamped = value.coerceIn(0.6f, 2.0f)
        motionSensitivity = clamped
        persist(context) { putFloat("motion_sensitivity", clamped) }
    }

    fun setMotionSmoothingAlpha(context: Context, value: Float) {
        val clamped = value.coerceIn(0.05f, 0.6f)
        motionSmoothingAlpha = clamped
        persist(context) { putFloat("motion_smoothing", clamped) }
    }

    fun setMotionDeadzone(context: Context, value: Float) {
        val clamped = value.coerceIn(0.01f, 0.08f)
        motionDeadzone = clamped
        persist(context) { putFloat("motion_deadzone", clamped) }
    }

    fun setMotionQuickRangeDeg(context: Context, value: Float) {
        val clamped = value.coerceIn(6f, 24f)
        motionQuickRangeDeg = clamped
        persist(context) { putFloat("motion_quick_range_deg", clamped) }
    }

    fun setMotionCalibration(
        context: Context,
        reference: FloatArray,
        yawTopLeft: Float,
        pitchTopLeft: Float,
        yawBottomRight: Float,
        pitchBottomRight: Float
    ) {
        motionCalibrationReference = reference.copyOf()
        motionCalibrationYawTopLeft = yawTopLeft
        motionCalibrationPitchTopLeft = pitchTopLeft
        motionCalibrationYawBottomRight = yawBottomRight
        motionCalibrationPitchBottomRight = pitchBottomRight
        motionCalibrationYawOffset = 0f
        motionCalibrationPitchOffset = 0f
        motionCalibrationValid = true
        persist(context) {
            putBoolean("motion_calibration_valid", true)
            putFloat("motion_calibration_yaw_top_left", yawTopLeft)
            putFloat("motion_calibration_pitch_top_left", pitchTopLeft)
            putFloat("motion_calibration_yaw_bottom_right", yawBottomRight)
            putFloat("motion_calibration_pitch_bottom_right", pitchBottomRight)
            putFloat("motion_calibration_yaw_offset", 0f)
            putFloat("motion_calibration_pitch_offset", 0f)
            putString("motion_calibration_reference", encodeMatrix(reference))
        }
    }

    fun setMotionCalibrationOffsets(context: Context, yawOffset: Float, pitchOffset: Float) {
        motionCalibrationYawOffset = yawOffset
        motionCalibrationPitchOffset = pitchOffset
        persist(context) {
            putFloat("motion_calibration_yaw_offset", yawOffset)
            putFloat("motion_calibration_pitch_offset", pitchOffset)
        }
    }

    fun clearMotionCalibration(context: Context) {
        motionCalibrationValid = false
        motionCalibrationReference = null
        motionCalibrationYawTopLeft = 0f
        motionCalibrationPitchTopLeft = 0f
        motionCalibrationYawBottomRight = 0f
        motionCalibrationPitchBottomRight = 0f
        motionCalibrationYawOffset = 0f
        motionCalibrationPitchOffset = 0f
        persist(context) {
            putBoolean("motion_calibration_valid", false)
            remove("motion_calibration_reference")
            remove("motion_calibration_yaw_top_left")
            remove("motion_calibration_pitch_top_left")
            remove("motion_calibration_yaw_bottom_right")
            remove("motion_calibration_pitch_bottom_right")
            remove("motion_calibration_yaw_offset")
            remove("motion_calibration_pitch_offset")
        }
    }

    private fun encodeMatrix(matrix: FloatArray): String {
        return matrix.joinToString(separator = ",")
    }

    private fun decodeMatrix(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank()) return null
        val parts = encoded.split(",")
        if (parts.size != 9) return null
        val values = FloatArray(9)
        for (i in 0 until 9) {
            values[i] = parts[i].toFloatOrNull() ?: return null
        }
        return values
    }

    fun setAppLanguage(context: Context, languageTag: String) {
        appLanguageTag = languageTag
        persist(context) { putString(PREF_APP_LANGUAGE, languageTag) }
        applyAppLanguage()
    }

    fun applyAppLanguage() {
        val locales = if (appLanguageTag == LANGUAGE_SYSTEM) {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            androidx.core.os.LocaleListCompat.forLanguageTags(appLanguageTag)
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }

    fun isLanguageSystem(): Boolean = appLanguageTag == LANGUAGE_SYSTEM
    fun isLanguageEnglish(): Boolean = appLanguageTag == LANGUAGE_ENGLISH
    fun isLanguageChinese(): Boolean = appLanguageTag == LANGUAGE_CHINESE

    fun setPointerSpeed(context: Context, value: Float) {
        TouchpadTuning.baseGain = value
        persist(context) { putFloat("tp_base_gain", value) }
    }

    fun setTouchpadMaxAccel(context: Context, value: Float) {
        TouchpadTuning.maxAccelGain = value
        persist(context) { putFloat("tp_max_accel", value) }
    }

    fun setTouchpadSpeedForMaxAccel(context: Context, value: Float) {
        TouchpadTuning.speedForMaxAccel = value
        persist(context) { putFloat("tp_speed_max", value) }
    }

    fun setTouchpadJitter(context: Context, value: Float) {
        TouchpadTuning.jitterThresholdPx = value
        persist(context) { putFloat("tp_jitter", value) }
    }

    fun setTouchpadSmoothing(context: Context, value: Float) {
        TouchpadTuning.emaAlpha = value
        persist(context) { putFloat("tp_smoothing", value) }
    }

    fun setTouchpadScrollStep(context: Context, value: Float) {
        TouchpadTuning.scrollStepPx = value
        persist(context) { putFloat("tp_scroll_step", value) }
    }

    fun setTouchpadDragBoost(context: Context, value: Float) {
        TouchpadTuning.dragBoost = value
        persist(context) { putFloat("tp_drag_boost", value) }
    }

    private fun persist(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply(block).apply()
    }
}
