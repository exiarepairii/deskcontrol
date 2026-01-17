package com.deskcontrol

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.deskcontrol.databinding.ActivityTouchpadBinding
import kotlin.math.abs

class TouchpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTouchpadBinding
    private val processor = TouchpadProcessor(TouchpadTuning)
    private val scrollProcessor = TouchpadScrollProcessor(TouchpadTuning)
    private val handler = Handler(Looper.getMainLooper())
    private var dimRunnable: Runnable? = null
    private var dimAnimator: ValueAnimator? = null
    private var originalWindowBrightness: Float = 0f
    private var hasOriginalWindowBrightness = false
    private var dimmedThisSession = false
    private var focusSessionId = 0
    private var isFocused = false
    private var isDragging = false
    private var isScrolling = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastScrollY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private var touchSlop = 0
    private var tapTimeout = 0
    private var doubleTapTimeout = 0
    private var pendingSingleTap: Runnable? = null
    private var touchpadActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isNightMode()) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }

        val viewConfig = ViewConfiguration.get(this)
        touchSlop = viewConfig.scaledTouchSlop
        tapTimeout = ViewConfiguration.getTapTimeout()
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()

        binding.touchpadToolbar.title = getString(R.string.touchpad_title)
        binding.touchpadToolbar.setNavigationOnClickListener { finish() }
        binding.touchpadToolbar.setOnLongClickListener {
            toggleTuningPanel()
            true
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.touchpadArea.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        setupTuningControls()
        setTouchpadActive(false)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (touchpadActive) {
                        val service = ControlAccessibilityService.current()
                        if (service?.performBack() != true) {
                            Toast.makeText(
                                this@TouchpadActivity,
                                getString(R.string.touchpad_accessibility_required_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        updateAccessibilityGate()
    }

    override fun onResume() {
        super.onResume()
        updateKeepScreenOn(true)
        startAutoDimSession()
    }

    override fun onPause() {
        stopAutoDimSession()
        updateKeepScreenOn(false)
        super.onPause()
    }

    override fun onStop() {
        stopAutoDimSession()
        super.onStop()
    }

    override fun onDestroy() {
        stopAutoDimSession()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val rect = android.graphics.Rect()
            binding.touchpadArea.getGlobalVisibleRect(rect)
            setTouchpadActive(rect.contains(event.rawX.toInt(), event.rawY.toInt()))
        }
        return super.dispatchTouchEvent(event)
    }

    private fun handleTouch(event: MotionEvent) {
        val service = serviceOrToast() ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                processor.reset()
                scrollProcessor.reset()
                isScrolling = false
                isDragging = false
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                lastTouchX = event.x
                lastTouchY = event.y
                service.wakeCursor()
                if (isSecondTapHold(event)) {
                    cancelPendingSingleTap()
                    isDragging = true
                    binding.touchpadArea.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    service.startDragAtCursor()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    cancelPendingSingleTap()
                    isScrolling = true
                    isDragging = false
                    lastScrollY = averageY(event)
                    service.wakeCursor()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrolling && event.pointerCount >= 2) {
                    val currentY = averageY(event)
                    val deltaY = currentY - lastScrollY
                    lastScrollY = currentY
                    scrollProcessor.consume(deltaY) { steps ->
                        service.scrollVertical(steps)
                    }
                    return
                }

                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                val output = processor.process(dx, dy, event.eventTime)
                if (output.dx != 0f || output.dy != 0f) {
                    val boost = if (isDragging) TouchpadTuning.dragBoost else 1f
                    service.moveCursorBy(output.dx * boost, output.dy * boost)
                    if (isDragging) {
                        service.updateDragToCursor()
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y

                val moved = abs(event.x - downX) > touchSlop ||
                    abs(event.y - downY) > touchSlop
                if (moved) {
                    cancelPendingSingleTap()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isScrolling = false
                    lastScrollY = 0f
                    scrollProcessor.reset()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    service.endDragAtCursor()
                    isDragging = false
                    return
                }
                val duration = event.eventTime - downTime
                val moved = abs(event.x - downX) > touchSlop ||
                    abs(event.y - downY) > touchSlop
                if (duration <= tapTimeout && !moved) {
                    scheduleSingleTap(service, event.eventTime, event.x, event.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingSingleTap()
                if (isDragging) {
                    service.cancelDrag()
                }
                isDragging = false
                isScrolling = false
            }
        }
    }

    private fun scheduleSingleTap(
        service: ControlAccessibilityService,
        eventTime: Long,
        x: Float,
        y: Float
    ) {
        lastTapUpTime = eventTime
        lastTapUpX = x
        lastTapUpY = y
        pendingSingleTap = Runnable {
            service.tapAtCursor()
            pendingSingleTap = null
        }
        handler.postDelayed(pendingSingleTap!!, doubleTapTimeout.toLong())
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    private fun isSecondTapHold(event: MotionEvent): Boolean {
        val timeDelta = event.eventTime - lastTapUpTime
        if (timeDelta <= 0 || timeDelta > doubleTapTimeout) return false
        val moved = abs(event.x - lastTapUpX) > touchSlop ||
            abs(event.y - lastTapUpY) > touchSlop
        return !moved
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 1) return event.y
        return (event.getY(0) + event.getY(1)) / 2f
    }

    private fun toggleTuningPanel() {
        binding.tuningPanel.visibility =
            if (binding.tuningPanel.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
    }

    private fun setTouchpadActive(active: Boolean) {
        touchpadActive = active
        binding.touchpadArea.isActivated = active
        val hintColorRes = if (active) {
            R.color.touchpadHintActive
        } else {
            R.color.touchpadHintInactive
        }
        binding.touchpadHint.setTextColor(ContextCompat.getColor(this, hintColorRes))
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupTuningControls() {
        configureSlider(
            binding.labelBaseGain,
            binding.sliderBaseGain,
            min = 0.4f,
            max = 2.4f,
            current = TouchpadTuning.baseGain,
            format = { getString(R.string.touchpad_base_gain_value, it) }
        ) { TouchpadTuning.baseGain = it }

        configureSlider(
            binding.labelAccel,
            binding.sliderAccel,
            min = 0.6f,
            max = 3.5f,
            current = TouchpadTuning.maxAccelGain,
            format = { getString(R.string.touchpad_acceleration_value, it) }
        ) { TouchpadTuning.maxAccelGain = it }

        configureSlider(
            binding.labelSpeed,
            binding.sliderSpeed,
            min = 0.6f,
            max = 2.8f,
            current = TouchpadTuning.speedForMaxAccel,
            format = { getString(R.string.touchpad_speed_for_max_accel_value, it) }
        ) { TouchpadTuning.speedForMaxAccel = it }

        configureSlider(
            binding.labelJitter,
            binding.sliderJitter,
            min = 0.1f,
            max = 2.0f,
            current = TouchpadTuning.jitterThresholdPx,
            format = { getString(R.string.touchpad_jitter_threshold_value, it) }
        ) { TouchpadTuning.jitterThresholdPx = it }

        configureSlider(
            binding.labelSmoothing,
            binding.sliderSmoothing,
            min = 0.05f,
            max = 0.85f,
            current = TouchpadTuning.emaAlpha,
            format = { getString(R.string.touchpad_smoothing_value, it) }
        ) { TouchpadTuning.emaAlpha = it }

        configureSlider(
            binding.labelScroll,
            binding.sliderScroll,
            min = 8f,
            max = 64f,
            current = TouchpadTuning.scrollStepPx,
            format = { getString(R.string.touchpad_scroll_step_value, it) }
        ) { TouchpadTuning.scrollStepPx = it }
    }

    private fun configureSlider(
        labelView: android.widget.TextView,
        slider: SeekBar,
        min: Float,
        max: Float,
        current: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        slider.max = 1000
        val initial = ((current - min) / (max - min) * slider.max).toInt()
        slider.progress = initial.coerceIn(0, slider.max)
        labelView.text = format(current)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = min + (max - min) * (progress / slider.max.toFloat())
                labelView.text = format(value)
                onChange(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun serviceOrToast(): ControlAccessibilityService? {
        val service = ControlAccessibilityService.current()
        if (service == null) {
            Toast.makeText(
                this,
                getString(R.string.touchpad_accessibility_required_toast),
                Toast.LENGTH_SHORT
            ).show()
        }
        return service
    }

    private fun updateAccessibilityGate() {
        val enabled = ControlAccessibilityService.isEnabled(this)
        binding.accessibilityGate.isVisible = !enabled
        binding.touchpadContent.alpha = if (enabled) 1f else 0.35f
        binding.touchpadArea.isEnabled = enabled
        binding.tuningPanel.isEnabled = enabled
        setTouchpadActive(false)
    }

    private fun updateKeepScreenOn(visible: Boolean) {
        if (visible && SettingsStore.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startAutoDimSession() {
        isFocused = true
        focusSessionId += 1
        dimmedThisSession = false
        cancelDimTimer()
        cancelDimAnimator()
        captureOriginalBrightness()

        if (!SettingsStore.touchpadAutoDimEnabled) return
        val sessionId = focusSessionId
        dimRunnable = Runnable {
            if (!isFocused || sessionId != focusSessionId || dimmedThisSession) return@Runnable
            dimWindowBrightness()
        }
        handler.postDelayed(dimRunnable!!, AUTO_DIM_DELAY_MS)
    }

    private fun stopAutoDimSession() {
        isFocused = false
        cancelDimTimer()
        cancelDimAnimator()
        restoreOriginalBrightness()
    }

    private fun captureOriginalBrightness() {
        val current = window.attributes.screenBrightness
        originalWindowBrightness = current
        hasOriginalWindowBrightness = true
    }

    private fun restoreOriginalBrightness() {
        if (!hasOriginalWindowBrightness) return
        window.attributes = window.attributes.apply {
            screenBrightness = originalWindowBrightness
        }
        hasOriginalWindowBrightness = false
        dimmedThisSession = false
    }

    private fun dimWindowBrightness() {
        val target = computeDimTarget()
        val start = getEstimatedCurrentBrightness().coerceAtLeast(target)
        if (start <= target) {
            applyWindowBrightness(target)
            dimmedThisSession = true
            return
        }
        dimAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = DIM_ANIMATION_DURATION_MS
            addUpdateListener { animator ->
                applyWindowBrightness(animator.animatedValue as Float)
            }
            start()
        }
        dimmedThisSession = true
    }

    private fun applyWindowBrightness(value: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = value.coerceIn(0f, 1f)
        }
    }

    private fun getEstimatedCurrentBrightness(): Float {
        val windowValue = window.attributes.screenBrightness
        if (windowValue >= 0f) {
            return windowValue.coerceIn(0f, 1f)
        }
        return try {
            val systemValue = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (systemValue / 255f).coerceIn(0f, 1f)
        } catch (e: Exception) {
            SettingsStore.touchpadDimLevel.coerceIn(0f, 1f)
        }
    }

    private fun computeDimTarget(): Float {
        val preferred = SettingsStore.touchpadDimLevel.coerceIn(0f, 1f)
        val current = getEstimatedCurrentBrightness()
        return minOf(preferred, current)
    }

    private fun cancelDimTimer() {
        dimRunnable?.let { handler.removeCallbacks(it) }
        dimRunnable = null
    }

    private fun cancelDimAnimator() {
        dimAnimator?.cancel()
        dimAnimator = null
    }

    companion object {
        private const val AUTO_DIM_DELAY_MS = 10_000L
        private const val DIM_ANIMATION_DURATION_MS = 400L
    }
}
