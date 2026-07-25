package com.deskcontrol

import android.content.Context
import kotlin.math.roundToInt

object SettingsStore {
    private const val PREFS_NAME = "deskcontrol_settings"
    private const val PREF_APP_LANGUAGE = "app_language"
    private const val PREF_LAST_CONTROL_SURFACE = "last_control_surface"
    private const val PREF_RAY_HORIZONTAL_RANGE_DEG = "ray_horizontal_range_deg"
    private const val PREF_RAY_VERTICAL_RANGE_DEG = "ray_vertical_range_deg"
    private const val PREF_RAY_SMOOTHING = "ray_smoothing"
    private const val PREF_RAY_MIN_EMIT_INTERVAL_MS = "ray_min_emit_interval_ms"
    private const val PREF_RAY_MIN_EMIT_DISTANCE_PX = "ray_min_emit_distance_px"
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
    var rayMouseIntroShown = false
        private set
    var lastControlSurface = ControlSurfaceMode.TOUCHPAD
        private set
    var touchpadScrollSpeed = 1.0f
        private set
    private const val PREF_SCROLL_SPEED_SCALE = "tp_scroll_scale"
    private const val PREF_SCROLL_SPEED_LEGACY = "tp_scroll_speed"
    var touchpadScrollInverted = true
        private set
    var touchpadScrollStepDp = 6.0f
        private set
    var touchpadDirectScrollGestureEnabled = false
        private set
    var touchpadDirectScrollGain = 1.0f
        private set
    var touchpadDirectScrollStepDp = 32.0f
        private set
    var touchpadAutoFocusEnabled = true
        private set
    var rayHapticFeedbackEnabled = true
        private set
    var rayHorizontalRangeDeg = RayMouseController.DEFAULT_HORIZONTAL_RANGE_DEG
        private set
    var rayVerticalRangeDeg = RayMouseController.DEFAULT_VERTICAL_RANGE_DEG
        private set
    var raySmoothing = RayMouseController.DEFAULT_SMOOTHING
        private set
    var rayMinEmitIntervalMs = RayMouseController.DEFAULT_MIN_EMIT_INTERVAL_MS
        private set
    var rayMinEmitDistancePx = RayMouseController.DEFAULT_MIN_EMIT_DISTANCE_PX
        private set
    var switchBarEnabled = true
        private set
    var switchBarScale = 1.0f
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        nightMode = prefs.getInt("night_mode", nightMode)
        cursorScale = SettingsSliderRanges.CURSOR_SCALE.snap(
            prefs.getFloat("cursor_scale", cursorScale)
        )
        cursorAlpha = SettingsSliderRanges.CURSOR_OPACITY.snap(
            prefs.getFloat("cursor_alpha", cursorAlpha)
        )
        val storedHideDelayMs = prefs.getLong("cursor_hide_delay_ms", cursorHideDelayMs)
        cursorHideDelayMs = if (storedHideDelayMs <= 0L) {
            0L
        } else {
            (SettingsSliderRanges.CURSOR_HIDE_DELAY_SECONDS.snap(
                storedHideDelayMs / 1000f
            ) * 1000f).toLong()
        }
        cursorColor = prefs.getInt("cursor_color", cursorColor)
        appLanguageTag = prefs.getString(PREF_APP_LANGUAGE, appLanguageTag) ?: LANGUAGE_SYSTEM
        keepScreenOn = prefs.getBoolean("keep_screen_on", keepScreenOn)
        touchpadAutoDimEnabled = prefs.getBoolean("touchpad_auto_dim", touchpadAutoDimEnabled)
        touchpadDimLevel = SettingsSliderRanges.DIM_LEVEL.snap(
            prefs.getFloat("touchpad_dim_level", touchpadDimLevel)
        )
        touchpadIntroShown = prefs.getBoolean("touchpad_intro_shown", touchpadIntroShown)
        rayMouseIntroShown = prefs.getBoolean("ray_mouse_intro_shown", rayMouseIntroShown)
        lastControlSurface = ControlSurfaceMode.fromPersistedValue(
            prefs.getString(PREF_LAST_CONTROL_SURFACE, null)
        )
        touchpadScrollSpeed = snapToStep(
            if (prefs.contains(PREF_SCROLL_SPEED_SCALE)) {
                prefs.getFloat(PREF_SCROLL_SPEED_SCALE, touchpadScrollSpeed)
            } else if (prefs.contains(PREF_SCROLL_SPEED_LEGACY)) {
                val legacy = prefs.getFloat(PREF_SCROLL_SPEED_LEGACY, BASE_SCROLL_SPEED)
                (legacy / BASE_SCROLL_SPEED)
            } else {
                touchpadScrollSpeed
            },
            min = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.start,
            max = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.end,
            step = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.step
        )
        touchpadScrollInverted = prefs.getBoolean("tp_scroll_invert", touchpadScrollInverted)
        touchpadDirectScrollGestureEnabled = prefs.getBoolean(
            "tp_scroll_direct_gesture",
            touchpadDirectScrollGestureEnabled
        )
        touchpadDirectScrollGain = SettingsSliderRanges.TOUCHPAD_DIRECT_GAIN.snap(
            prefs.getFloat("tp_scroll_direct_gain", touchpadDirectScrollGain)
        )
        touchpadDirectScrollStepDp = SettingsSliderRanges.TOUCHPAD_DIRECT_STEP.snap(
            prefs.getFloat("tp_scroll_direct_step_dp", touchpadDirectScrollStepDp)
        )
        touchpadAutoFocusEnabled = prefs.getBoolean(
            "tp_auto_focus",
            touchpadAutoFocusEnabled
        )
        rayHapticFeedbackEnabled = prefs.getBoolean(
            "ray_haptic_feedback",
            rayHapticFeedbackEnabled
        )
        rayHorizontalRangeDeg = snapToStep(
            prefs.getFloat(PREF_RAY_HORIZONTAL_RANGE_DEG, rayHorizontalRangeDeg),
            min = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.start,
            max = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.end,
            step = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.step
        )
        rayVerticalRangeDeg = snapToStep(
            prefs.getFloat(PREF_RAY_VERTICAL_RANGE_DEG, rayVerticalRangeDeg),
            min = SettingsSliderRanges.MOTION_VERTICAL_RANGE.start,
            max = SettingsSliderRanges.MOTION_VERTICAL_RANGE.end,
            step = SettingsSliderRanges.MOTION_VERTICAL_RANGE.step
        )
        raySmoothing = snapToStep(
            prefs.getFloat(PREF_RAY_SMOOTHING, raySmoothing),
            min = SettingsSliderRanges.MOTION_SMOOTHING.start,
            max = SettingsSliderRanges.MOTION_SMOOTHING.end,
            step = SettingsSliderRanges.MOTION_SMOOTHING.step
        )
        rayMinEmitIntervalMs = snapToStep(
            prefs.getLong(PREF_RAY_MIN_EMIT_INTERVAL_MS, rayMinEmitIntervalMs).toFloat(),
            min = SettingsSliderRanges.MOTION_EMIT_INTERVAL.start,
            max = SettingsSliderRanges.MOTION_EMIT_INTERVAL.end,
            step = SettingsSliderRanges.MOTION_EMIT_INTERVAL.step
        ).toLong()
        rayMinEmitDistancePx = snapToStep(
            prefs.getFloat(PREF_RAY_MIN_EMIT_DISTANCE_PX, rayMinEmitDistancePx),
            min = SettingsSliderRanges.MOTION_EMIT_DISTANCE.start,
            max = SettingsSliderRanges.MOTION_EMIT_DISTANCE.end,
            step = SettingsSliderRanges.MOTION_EMIT_DISTANCE.step
        )
        touchpadScrollStepDp = snapToStep(
            prefs.getFloat("tp_scroll_step_dp", touchpadScrollStepDp),
            min = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.start,
            max = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.end,
            step = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.step
        )
        switchBarEnabled = prefs.getBoolean("switch_bar_enabled", switchBarEnabled)
        switchBarScale = SettingsSliderRanges.DOCK_SCALE.snap(
            prefs.getFloat("switch_bar_scale", switchBarScale)
        )

        TouchpadTuning.baseGain = snapToStep(
            prefs.getFloat("tp_base_gain", TouchpadTuning.baseGain),
            min = SettingsSliderRanges.CURSOR_SPEED.start,
            max = SettingsSliderRanges.CURSOR_SPEED.end,
            step = SettingsSliderRanges.CURSOR_SPEED.step
        )
        TouchpadTuning.maxAccelGain = snapToStep(
            prefs.getFloat("tp_max_accel", TouchpadTuning.maxAccelGain),
            min = 0.6f,
            max = 3.5f,
            step = 0.1f
        )
        TouchpadTuning.speedForMaxAccel = snapToStep(
            prefs.getFloat("tp_speed_max", TouchpadTuning.speedForMaxAccel),
            min = 0.6f,
            max = 2.8f,
            step = 0.1f
        )
        TouchpadTuning.jitterThresholdPx = snapToStep(
            prefs.getFloat("tp_jitter", TouchpadTuning.jitterThresholdPx),
            min = 0.1f,
            max = 2.0f,
            step = 0.1f
        )
        TouchpadTuning.emaAlpha = snapToStep(
            prefs.getFloat("tp_smoothing", TouchpadTuning.emaAlpha),
            min = 0.05f,
            max = 0.85f,
            step = 0.05f
        )
        TouchpadTuning.scrollStepPx = prefs.getFloat("tp_scroll_step", TouchpadTuning.scrollStepPx)
        TouchpadTuning.dragBoost = snapToStep(
            prefs.getFloat("tp_drag_boost", TouchpadTuning.dragBoost),
            min = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.start,
            max = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.end,
            step = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.step
        )
    }

    fun setNightMode(context: Context, value: Int) {
        nightMode = value
        persist(context) { putInt("night_mode", value) }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(value)
    }

    fun setCursorScale(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.CURSOR_SCALE.snap(value)
        cursorScale = snapped
        persist(context) { putFloat("cursor_scale", snapped) }
        ControlAccessibilityService.requestCursorAppearanceRefresh()
    }

    fun setCursorAlpha(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.CURSOR_OPACITY.snap(value)
        cursorAlpha = snapped
        persist(context) { putFloat("cursor_alpha", snapped) }
        ControlAccessibilityService.requestCursorAppearanceRefresh()
    }

    fun setCursorColor(context: Context, value: Int) {
        cursorColor = value
        persist(context) { putInt("cursor_color", value) }
        ControlAccessibilityService.requestCursorAppearanceRefresh()
    }

    fun setCursorHideDelay(context: Context, valueMs: Long) {
        val snapped = if (valueMs <= 0L) {
            0L
        } else {
            (SettingsSliderRanges.CURSOR_HIDE_DELAY_SECONDS.snap(
                valueMs / 1000f
            ) * 1000f).toLong()
        }
        cursorHideDelayMs = snapped
        persist(context) { putLong("cursor_hide_delay_ms", snapped) }
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
        val snapped = SettingsSliderRanges.DIM_LEVEL.snap(value)
        touchpadDimLevel = snapped
        persist(context) { putFloat("touchpad_dim_level", snapped) }
    }

    fun setTouchpadIntroShown(context: Context) {
        touchpadIntroShown = true
        persist(context) { putBoolean("touchpad_intro_shown", true) }
    }

    fun setRayMouseIntroShown(context: Context) {
        rayMouseIntroShown = true
        persist(context) { putBoolean("ray_mouse_intro_shown", true) }
    }

    fun setLastControlSurface(context: Context, mode: ControlSurfaceMode) {
        if (lastControlSurface == mode) return
        lastControlSurface = mode
        persist(context) { putString(PREF_LAST_CONTROL_SURFACE, mode.persistedValue) }
    }

    fun setTouchpadScrollSpeed(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.snap(value)
        if (touchpadScrollSpeed == snapped) return
        touchpadScrollSpeed = snapped
        persist(context) { putFloat(PREF_SCROLL_SPEED_SCALE, snapped) }
    }

    fun getTouchpadScrollBaseSpeed(): Float = BASE_SCROLL_SPEED

    fun setTouchpadScrollInverted(context: Context, inverted: Boolean) {
        touchpadScrollInverted = inverted
        persist(context) { putBoolean("tp_scroll_invert", inverted) }
    }

    fun setTouchpadScrollStepDp(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.snap(value)
        if (touchpadScrollStepDp == snapped) return
        touchpadScrollStepDp = snapped
        persist(context) { putFloat("tp_scroll_step_dp", snapped) }
    }

    fun setTouchpadDirectScrollGestureEnabled(context: Context, enabled: Boolean) {
        touchpadDirectScrollGestureEnabled = enabled
        persist(context) { putBoolean("tp_scroll_direct_gesture", enabled) }
    }

    fun setTouchpadDirectScrollGain(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.TOUCHPAD_DIRECT_GAIN.snap(value)
        touchpadDirectScrollGain = snapped
        persist(context) { putFloat("tp_scroll_direct_gain", snapped) }
    }

    fun setTouchpadDirectScrollStepDp(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.TOUCHPAD_DIRECT_STEP.snap(value)
        touchpadDirectScrollStepDp = snapped
        persist(context) { putFloat("tp_scroll_direct_step_dp", snapped) }
    }

    fun setTouchpadAutoFocusEnabled(context: Context, enabled: Boolean) {
        touchpadAutoFocusEnabled = enabled
        persist(context) { putBoolean("tp_auto_focus", enabled) }
    }

    fun setRayHapticFeedbackEnabled(context: Context, enabled: Boolean) {
        rayHapticFeedbackEnabled = enabled
        persist(context) { putBoolean("ray_haptic_feedback", enabled) }
    }

    fun setRayHorizontalRangeDeg(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.snap(value)
        if (rayHorizontalRangeDeg == snapped) return
        rayHorizontalRangeDeg = snapped
        persist(context) { putFloat(PREF_RAY_HORIZONTAL_RANGE_DEG, snapped) }
    }

    fun setRayVerticalRangeDeg(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.MOTION_VERTICAL_RANGE.snap(value)
        if (rayVerticalRangeDeg == snapped) return
        rayVerticalRangeDeg = snapped
        persist(context) { putFloat(PREF_RAY_VERTICAL_RANGE_DEG, snapped) }
    }

    fun setRaySmoothing(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.MOTION_SMOOTHING.snap(value)
        if (raySmoothing == snapped) return
        raySmoothing = snapped
        persist(context) { putFloat(PREF_RAY_SMOOTHING, snapped) }
    }

    fun setRayMinEmitIntervalMs(context: Context, value: Long) {
        val snapped = SettingsSliderRanges.MOTION_EMIT_INTERVAL.snap(value.toFloat()).toLong()
        if (rayMinEmitIntervalMs == snapped) return
        rayMinEmitIntervalMs = snapped
        persist(context) { putLong(PREF_RAY_MIN_EMIT_INTERVAL_MS, snapped) }
    }

    fun setRayMinEmitDistancePx(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.MOTION_EMIT_DISTANCE.snap(value)
        if (rayMinEmitDistancePx == snapped) return
        rayMinEmitDistancePx = snapped
        persist(context) { putFloat(PREF_RAY_MIN_EMIT_DISTANCE_PX, snapped) }
    }

    fun setSwitchBarEnabled(context: Context, enabled: Boolean) {
        switchBarEnabled = enabled
        persist(context) { putBoolean("switch_bar_enabled", enabled) }
        ControlAccessibilityService.requestSwitchBarRefresh()
    }

    fun setSwitchBarScale(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.DOCK_SCALE.snap(value)
        switchBarScale = snapped
        persist(context) { putFloat("switch_bar_scale", snapped) }
        ControlAccessibilityService.requestSwitchBarRefresh()
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
        val snapped = SettingsSliderRanges.CURSOR_SPEED.snap(value)
        if (TouchpadTuning.baseGain == snapped) return
        TouchpadTuning.baseGain = snapped
        persist(context) { putFloat("tp_base_gain", snapped) }
    }

    fun setTouchpadMaxAccel(context: Context, value: Float) {
        val snapped = snapToStep(value, min = 0.6f, max = 3.5f, step = 0.1f)
        if (TouchpadTuning.maxAccelGain == snapped) return
        TouchpadTuning.maxAccelGain = snapped
        persist(context) { putFloat("tp_max_accel", snapped) }
    }

    fun setTouchpadSpeedForMaxAccel(context: Context, value: Float) {
        val snapped = snapToStep(value, min = 0.6f, max = 2.8f, step = 0.1f)
        if (TouchpadTuning.speedForMaxAccel == snapped) return
        TouchpadTuning.speedForMaxAccel = snapped
        persist(context) { putFloat("tp_speed_max", snapped) }
    }

    fun setTouchpadJitter(context: Context, value: Float) {
        val snapped = snapToStep(value, min = 0.1f, max = 2.0f, step = 0.1f)
        if (TouchpadTuning.jitterThresholdPx == snapped) return
        TouchpadTuning.jitterThresholdPx = snapped
        persist(context) { putFloat("tp_jitter", snapped) }
    }

    fun setTouchpadSmoothing(context: Context, value: Float) {
        val snapped = snapToStep(value, min = 0.05f, max = 0.85f, step = 0.05f)
        if (TouchpadTuning.emaAlpha == snapped) return
        TouchpadTuning.emaAlpha = snapped
        persist(context) { putFloat("tp_smoothing", snapped) }
    }

    fun setTouchpadScrollStep(context: Context, value: Float) {
        TouchpadTuning.scrollStepPx = value
        persist(context) { putFloat("tp_scroll_step", value) }
    }

    fun setTouchpadDragBoost(context: Context, value: Float) {
        val snapped = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.snap(value)
        if (TouchpadTuning.dragBoost == snapped) return
        TouchpadTuning.dragBoost = snapped
        persist(context) { putFloat("tp_drag_boost", snapped) }
    }

    private fun persist(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply(block).apply()
    }

    private fun snapToStep(value: Float, min: Float, max: Float, step: Float): Float {
        val finiteValue = if (value.isFinite()) value else min
        val stepCount = ((finiteValue.coerceIn(min, max) - min) / step).roundToInt()
        return (min + stepCount * step).coerceIn(min, max)
    }
}
