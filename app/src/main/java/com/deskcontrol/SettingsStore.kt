package com.deskcontrol

import android.content.Context

object SettingsStore {
    private const val PREFS_NAME = "deskcontrol_settings"
    private const val PREF_APP_LANGUAGE = "app_language"
    private const val LANGUAGE_SYSTEM = "system"
    private const val LANGUAGE_ENGLISH = "en"
    private const val LANGUAGE_CHINESE = "zh-CN"

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

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        nightMode = prefs.getInt("night_mode", nightMode)
        cursorScale = prefs.getFloat("cursor_scale", cursorScale)
        cursorAlpha = prefs.getFloat("cursor_alpha", cursorAlpha)
        cursorHideDelayMs = prefs.getLong("cursor_hide_delay_ms", cursorHideDelayMs)
        cursorColor = prefs.getInt("cursor_color", cursorColor)
        appLanguageTag = prefs.getString(PREF_APP_LANGUAGE, appLanguageTag) ?: LANGUAGE_SYSTEM
        keepScreenOn = prefs.getBoolean("keep_screen_on", keepScreenOn)

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
