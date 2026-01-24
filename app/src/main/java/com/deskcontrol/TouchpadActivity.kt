package com.deskcontrol

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.deskcontrol.databinding.ActivityTouchpadBinding
import kotlin.math.abs

class TouchpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTouchpadBinding
    private val processor = TouchpadProcessor(TouchpadTuning)
    private val handler = Handler(Looper.getMainLooper())
    private var dimRunnable: Runnable? = null
    private var dimAnimator: ValueAnimator? = null
    private var originalWindowBrightness: Float = 0f
    private var originalSystemBrightness: Float = 1f
    private var hasOriginalWindowBrightness = false
    private var dimmedThisSession = false
    private var focusSessionId = 0
    private var isFocused = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTwoFingerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var touchSlopPx = 0f
    private var longPressTimeout = 0
    private var longPressRunnable: Runnable? = null
    private var scrollAccumDy = 0f
    private var scrollSpeedMultiplier = 1f
    private var lastScrollEventTime = 0L
    private var scrollTickerRunning = false
    private var scrollAnchorX = 0f
    private var scrollAnchorY = 0f
    private var injectAnchorX = 0f
    private var injectAnchorY = 0f
    private var touchpadActive = false
    private var touchState = TouchState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DiagnosticsLog.add("Touchpad: create displayId=${display?.displayId ?: -1}")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root, includeTop = false)
        applyToolbarInsets()
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        val touchpadBg = ContextCompat.getColor(this, R.color.touchpadBackground)
        @Suppress("DEPRECATION")
        window.statusBarColor = touchpadBg
        @Suppress("DEPRECATION")
        window.navigationBarColor = touchpadBg
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        touchSlopPx = resources.displayMetrics.density * TOUCH_SLOP_DP
        longPressTimeout = ViewConfiguration.getLongPressTimeout()

        binding.touchpadToolbar.title = getString(R.string.touchpad_title)
        binding.touchpadToolbar.inflateMenu(R.menu.touchpad_menu)
        tintToolbarMenu()
        binding.touchpadToolbar.setNavigationOnClickListener {
            DiagnosticsLog.add("Touchpad: exit via toolbar")
            finish()
        }
        binding.touchpadToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_blackout) {
                setBlackoutVisible(true)
                true
            } else {
                false
            }
        }
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
        binding.blackoutOverlay.setOnClickListener {
            setBlackoutVisible(false)
        }

        setupTuningControls()
        setTouchpadActive(false)
        showTouchpadIntroIfNeeded()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (touchpadActive) {
                        val displayInfo = DisplaySessionManager.getExternalDisplayInfo()
                        val sessionActive = displayInfo != null
                        if (!sessionActive) {
                            DiagnosticsLog.add("Touchpad: back blocked (no external display)")
                            Toast.makeText(
                                this@TouchpadActivity,
                                getString(R.string.touchpad_no_external_display),
                                Toast.LENGTH_SHORT
                            ).show()
                            return
                        }
                        val backTimestamp = SystemClock.uptimeMillis()
                        DiagnosticsLog.add("Touchpad: back requested t=$backTimestamp")
                        val service = ControlAccessibilityService.current()
                        if (service == null) {
                            DiagnosticsLog.add("Touchpad: back failed (accessibility missing)")
                            Toast.makeText(
                                this@TouchpadActivity,
                                getString(R.string.touchpad_accessibility_required_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val success = service.performBack()
                            if (!success && SessionStore.lastBackFailure == "external_not_focused") {
                                val message =
                                    getString(R.string.touchpad_back_external_not_focused)
                                service.showToastOnExternalDisplay(message)
                            } else if (!success &&
                                SessionStore.lastBackFailure == "external_window_missing"
                            ) {
                                val message =
                                    getString(R.string.touchpad_back_external_window_missing)
                                service.showToastOnExternalDisplay(message)
                            }
                        }
                        DiagnosticsLog.add("Touchpad: back forwarded")
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
        if (touchpadActive) {
            startAutoDimSession()
        } else {
            stopAutoDimSession()
        }
        ControlAccessibilityService.current()?.warmUpBackPipeline()
        DiagnosticsLog.add("Touchpad: resume")
    }

    override fun onPause() {
        stopAutoDimSession()
        cancelLongPress()
        exitScrollMode()
        updateKeepScreenOn(false)
        DiagnosticsLog.add("Touchpad: pause")
        super.onPause()
    }

    override fun onStop() {
        stopAutoDimSession()
        cancelLongPress()
        exitScrollMode()
        super.onStop()
    }

    override fun onDestroy() {
        stopAutoDimSession()
        cancelLongPress()
        exitScrollMode()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (binding.blackoutOverlay.isVisible) {
            return super.dispatchTouchEvent(event)
        }
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
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                touchState = TouchState.ONE_FINGER_DOWN
                scheduleLongPress(service)
                service.wakeCursor()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    cancelLongPress()
                    if (touchState == TouchState.DRAGGING) {
                        service.endDragAtCursor()
                    }
                    enterScrollMode(service, event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchState == TouchState.SCROLL_MODE && event.pointerCount >= 2) {
                    updateScrollMode(event)
                    return
                }

                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                val output = processor.process(dx, dy, event.eventTime)
                if (output.dx != 0f || output.dy != 0f) {
                    val boost = if (touchState == TouchState.DRAGGING) {
                        TouchpadTuning.dragBoost
                    } else {
                        1f
                    }
                    service.moveCursorBy(output.dx * boost, output.dy * boost)
                    if (touchState == TouchState.DRAGGING) {
                        service.updateDragToCursor()
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y

                if (touchState == TouchState.ONE_FINGER_DOWN) {
                    val moved = abs(event.x - downX) > touchSlopPx ||
                        abs(event.y - downY) > touchSlopPx
                    if (moved) {
                        cancelLongPress()
                        touchState = TouchState.MOVING_CURSOR
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchState == TouchState.SCROLL_MODE && event.pointerCount <= 2) {
                    exitScrollMode()
                    resetTouchBaseline(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (touchState == TouchState.SCROLL_MODE) {
                    exitScrollMode()
                    resetTouchBaseline(event)
                    return
                }
                if (touchState == TouchState.DRAGGING) {
                    service.endDragAtCursor()
                    touchState = TouchState.IDLE
                    return
                }
                val moved = abs(event.x - downX) > touchSlopPx ||
                    abs(event.y - downY) > touchSlopPx
                if (touchState == TouchState.ONE_FINGER_DOWN && !moved) {
                    service.tapAtCursor()
                }
                touchState = TouchState.IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (touchState == TouchState.DRAGGING) {
                    service.cancelDrag()
                }
                if (touchState == TouchState.SCROLL_MODE) {
                    exitScrollMode()
                }
                touchState = TouchState.IDLE
            }
        }
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 1) return event.y
        return (event.getY(0) + event.getY(1)) / 2f
    }

    private fun averageX(event: MotionEvent): Float {
        if (event.pointerCount == 1) return event.x
        return (event.getX(0) + event.getX(1)) / 2f
    }

    private fun resetTouchBaseline(event: MotionEvent) {
        lastTouchX = averageX(event)
        lastTouchY = averageY(event)
        downX = lastTouchX
        downY = lastTouchY
    }

    private fun scheduleLongPress(service: ControlAccessibilityService) {
        cancelLongPress()
        longPressRunnable = Runnable {
            if (touchState != TouchState.ONE_FINGER_DOWN) return@Runnable
            val moved = abs(lastTouchX - downX) > touchSlopPx ||
                abs(lastTouchY - downY) > touchSlopPx
            if (moved) return@Runnable
            touchState = TouchState.DRAGGING
            binding.touchpadArea.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            service.startDragAtCursor()
        }
        handler.postDelayed(longPressRunnable!!, longPressTimeout.toLong())
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun setBlackoutVisible(visible: Boolean) {
        if (binding.blackoutOverlay.isVisible == visible) return
        if (visible) {
            cancelLongPress()
            exitScrollMode()
            if (touchState == TouchState.DRAGGING) {
                ControlAccessibilityService.current()?.endDragAtCursor()
                touchState = TouchState.IDLE
            }
        }
        binding.blackoutOverlay.isVisible = visible
        DiagnosticsLog.add("Touchpad: blackout=$visible")
    }

    private fun enterScrollMode(service: ControlAccessibilityService, event: MotionEvent) {
        touchState = TouchState.SCROLL_MODE
        lastTwoFingerY = averageY(event)
        lastScrollEventTime = event.eventTime
        scrollAccumDy = 0f
        scrollSpeedMultiplier = 1f
        val cursor = service.getCursorPosition()
        scrollAnchorX = cursor.x
        scrollAnchorY = cursor.y
        val injectAnchor = service.prepareScrollMode(scrollAnchorX, scrollAnchorY)
        injectAnchorX = injectAnchor.x
        injectAnchorY = injectAnchor.y
        DiagnosticsLog.add(
            "Touchpad: scroll mode enter anchor=(${scrollAnchorX.toInt()},${scrollAnchorY.toInt()})"
        )
        startScrollTicker()
    }

    private fun updateScrollMode(event: MotionEvent) {
        val currentY = averageY(event)
        val deltaY = currentY - lastTwoFingerY
        lastTwoFingerY = currentY
        if ((deltaY > 0f && scrollAccumDy < 0f) || (deltaY < 0f && scrollAccumDy > 0f)) {
            scrollAccumDy = 0f
        }
        scrollAccumDy += deltaY
        val dt = (event.eventTime - lastScrollEventTime).coerceAtLeast(1L)
        val velocity = abs(deltaY) / dt.toFloat()
        scrollSpeedMultiplier = computeScrollSpeedMultiplier(velocity)
        lastScrollEventTime = event.eventTime
        emitScrollSteps()
    }

    private fun exitScrollMode() {
        if (touchState != TouchState.SCROLL_MODE) return
        touchState = TouchState.IDLE
        stopScrollTicker()
        scrollAccumDy = 0f
        lastScrollEventTime = 0L
        DiagnosticsLog.add("Touchpad: scroll mode exit")
    }

    private fun startScrollTicker() {
        if (scrollTickerRunning) return
        scrollTickerRunning = true
        handler.post(scrollTicker)
    }

    private fun stopScrollTicker() {
        if (!scrollTickerRunning) return
        handler.removeCallbacks(scrollTicker)
        scrollTickerRunning = false
    }

    private val scrollTicker = object : Runnable {
        override fun run() {
            if (touchState != TouchState.SCROLL_MODE) {
                scrollTickerRunning = false
                return
            }
            val idleFor = SystemClock.uptimeMillis() - lastScrollEventTime
            if (idleFor > SCROLL_IDLE_TIMEOUT_MS) {
                scrollAccumDy = 0f
                scrollTickerRunning = false
                return
            }
            emitScrollSteps()
            handler.postDelayed(this, SCROLL_TICK_MS)
        }
    }

    private fun emitScrollSteps() {
        val service = ControlAccessibilityService.current() ?: return
        val userSpeed = SettingsStore.touchpadScrollSpeed.coerceIn(
            SCROLL_SPEED_SETTING_MIN,
            SCROLL_SPEED_SETTING_MAX
        )
        val multiplier = (scrollSpeedMultiplier * userSpeed)
            .coerceIn(SCROLL_SPEED_MIN, SCROLL_SPEED_MAX)
        val stepThreshold = (resources.displayMetrics.density * SCROLL_STEP_DP) / multiplier
        val absAccum = abs(scrollAccumDy)
        if (stepThreshold <= 0f || absAccum < stepThreshold) return
        var direction = if (scrollAccumDy > 0f) 1 else -1
        if (SettingsStore.touchpadScrollInverted) {
            direction *= -1
        }
        val maxSteps = SCROLL_MAX_STEPS_PER_TICK
        var stepsToEmit = (absAccum / stepThreshold).toInt()
        if (stepsToEmit > maxSteps) stepsToEmit = maxSteps
        var emitted = 0
        while (emitted < stepsToEmit) {
            val success = service.performScrollStep(
                direction,
                injectAnchorX,
                injectAnchorY,
                multiplier
            )
            if (!success) break
            scrollAccumDy -= direction * stepThreshold
            emitted += 1
        }
    }

    private fun computeScrollSpeedMultiplier(velocityPxPerMs: Float): Float {
        if (velocityPxPerMs <= 0f) return 1f
        val scaled = velocityPxPerMs / SCROLL_SPEED_BASE_PX_PER_MS
        return scaled.coerceIn(SCROLL_SPEED_MIN, SCROLL_SPEED_MAX)
    }

    private fun applyToolbarInsets() {
        val initialTop = binding.touchpadToolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.touchpadToolbar) { view, insets ->
            val systemInsets = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                view.paddingLeft,
                initialTop + systemInsets.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
    }

    private fun toggleTuningPanel() {
        binding.tuningPanel.visibility =
            if (binding.tuningPanel.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
    }

    private fun tintToolbarMenu() {
        val color = ContextCompat.getColor(this, R.color.touchpadToolbarText)
        val menu = binding.touchpadToolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val title = item.title ?: continue
            val spannable = SpannableString(title)
            spannable.setSpan(
                ForegroundColorSpan(color),
                0,
                spannable.length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE
            )
            item.title = spannable
        }
    }

    private fun setTouchpadActive(active: Boolean) {
        val wasActive = touchpadActive
        touchpadActive = active
        binding.touchpadArea.isActivated = active
        val hintColorRes = if (active) {
            R.color.touchpadHintActive
        } else {
            R.color.touchpadHintInactive
        }
        binding.touchpadHint.setTextColor(ContextCompat.getColor(this, hintColorRes))
        if (wasActive != active) {
            DiagnosticsLog.add("Touchpad: active=$active")
            if (active) {
                startAutoDimSession()
            } else {
                stopAutoDimSession()
            }
        }
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

    private fun showTouchpadIntroIfNeeded() {
        if (SettingsStore.touchpadIntroShown) return
        val message = getString(
            R.string.touchpad_intro_message,
            getString(R.string.touchpad_intro_gesture_move),
            getString(R.string.touchpad_intro_gesture_tap),
            getString(R.string.touchpad_intro_gesture_drag),
            getString(R.string.touchpad_intro_dim_behavior),
            getString(R.string.touchpad_intro_back_behavior),
            getString(R.string.touchpad_intro_exit_hint)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.touchpad_intro_title)
            .setMessage(message)
            .setPositiveButton(R.string.touchpad_intro_got_it) { dialog, _ -> dialog.dismiss() }
            .show()
        SettingsStore.setTouchpadIntroShown(this)
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
        DiagnosticsLog.add("Touchpad: dim timer started")
    }

    private fun stopAutoDimSession() {
        isFocused = false
        cancelDimTimer()
        cancelDimAnimator()
        restoreOriginalBrightness()
        DiagnosticsLog.add("Touchpad: dim session stopped")
    }

    private fun captureOriginalBrightness() {
        val current = window.attributes.screenBrightness
        originalWindowBrightness = current
        hasOriginalWindowBrightness = true
        originalSystemBrightness = readSystemBrightness()
    }

    private fun restoreOriginalBrightness() {
        if (!hasOriginalWindowBrightness) return
        window.attributes = window.attributes.apply {
            screenBrightness = originalWindowBrightness
        }
        hasOriginalWindowBrightness = false
        dimmedThisSession = false
        DiagnosticsLog.add("Touchpad: brightness restored")
    }

    private fun dimWindowBrightness() {
        val target = computeDimTarget() ?: run {
            DiagnosticsLog.add("Touchpad: dim skipped (avoid brightening)")
            return
        }
        val start = getEstimatedCurrentBrightness().coerceAtLeast(target)
        if (start <= target) {
            applyWindowBrightness(target)
            dimmedThisSession = true
            DiagnosticsLog.add("Touchpad: dimmed target=$target")
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
        DiagnosticsLog.add("Touchpad: dimmed target=$target")
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
        return readSystemBrightness()
    }

    private fun computeDimTarget(): Float? {
        val preferred = SettingsStore.touchpadDimLevel.coerceIn(0f, 1f)
        if (originalWindowBrightness < 0f) {
            val systemBrightness = originalSystemBrightness.coerceIn(0f, 1f)
            if (preferred >= systemBrightness) {
                return null
            }
            return preferred.coerceAtMost(systemBrightness)
        }
        val current = getEstimatedCurrentBrightness()
        return minOf(preferred, current)
    }

    private fun readSystemBrightness(): Float {
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
        private const val TOUCH_SLOP_DP = 8f
        private const val SCROLL_STEP_DP = 8f
        private const val SCROLL_TICK_MS = 16L
        private const val SCROLL_MAX_STEPS_PER_TICK = 3
        private const val SCROLL_SPEED_BASE_PX_PER_MS = 0.6f
        private const val SCROLL_SPEED_MIN = 0.6f
        private const val SCROLL_SPEED_MAX = 2.0f
        private const val SCROLL_SPEED_SETTING_MIN = 0.4f
        private const val SCROLL_SPEED_SETTING_MAX = 1.2f
        private const val SCROLL_IDLE_TIMEOUT_MS = 50L
    }

    private enum class TouchState {
        IDLE,
        ONE_FINGER_DOWN,
        MOVING_CURSOR,
        DRAGGING,
        SCROLL_MODE
    }
}
