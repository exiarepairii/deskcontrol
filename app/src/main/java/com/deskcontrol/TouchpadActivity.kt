package com.deskcontrol

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.pm.PackageManager
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
import rikka.shizuku.Shizuku
import kotlin.math.abs

class TouchpadActivity : AppCompatActivity(), DisplaySessionManager.Listener {

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
    private var downX = 0f
    private var downY = 0f
    private var touchSlopPx = 0f
    private var longPressCancelSlopPx = 0f
    private var longPressTimeout = 0
    private var longPressRunnable: Runnable? = null
    private var blackoutHintFadeRunnable: Runnable? = null
    private var blackoutDownX = 0f
    private var blackoutDownY = 0f
    private var blackoutMoved = false
    private var blackoutSwipeMinPx = 0f
    private var blackoutSwipeOffset = 0f
    private lateinit var legacyScrollController: LegacyScrollController
    private lateinit var directScrollController: DirectScrollController
    private var activeScrollController = ActiveScrollController.NONE
    private var touchpadActive = false
    private var touchState = TouchState.IDLE
    private var suppressSingleUntilUp = false
    private var shizukuBinderReady = false
    private var shizukuEnableInFlight = false
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        shizukuBinderReady = true
        updateShizukuButton()
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        shizukuBinderReady = false
        updateShizukuButton()
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            enableAccessibilityWithShizuku()
        } else {
            Toast.makeText(
                this,
                getString(R.string.touchpad_shizuku_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
            updateShizukuButton()
        }
    }

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
        longPressCancelSlopPx = resources.displayMetrics.density * LONG_PRESS_CANCEL_DP
        blackoutSwipeMinPx = resources.displayMetrics.density * BLACKOUT_SWIPE_MIN_DP
        longPressTimeout = ViewConfiguration.getLongPressTimeout()
        legacyScrollController = LegacyScrollController(
            context = this,
            handler = handler,
            serviceProvider = { ControlAccessibilityService.current() }
        )
        directScrollController = DirectScrollController(
            context = this,
            touchpadSizeProvider = { binding.touchpadArea.width to binding.touchpadArea.height },
            serviceProvider = { ControlAccessibilityService.current() }
        )

        binding.touchpadBack.setOnClickListener {
            DiagnosticsLog.add("Touchpad: exit via toolbar")
            finish()
        }
        binding.touchpadBlackout.setOnClickListener {
            setBlackoutVisible(true)
        }
        binding.touchpadToolbar.setOnLongClickListener {
            toggleTuningPanel()
            true
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnEnableAccessibilityShizuku.setOnClickListener {
            requestAccessibilityViaShizuku()
        }
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refreshShizukuBinderState()
        updateShizukuButton()

        binding.touchpadArea.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
        binding.blackoutOverlay.setOnTouchListener { view, event ->
            if (!binding.blackoutOverlay.isVisible) return@setOnTouchListener false
            if (event.pointerCount > 1) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    blackoutDownX = event.x
                    blackoutDownY = event.y
                    blackoutMoved = false
                    blackoutSwipeOffset = 0f
                    view.translationY = 0f
                    showBlackoutHintImmediate()
                    restoreOriginalBrightness()
                    if (binding.blackoutOverlay.isVisible || touchpadActive) {
                        startAutoDimSession()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - blackoutDownX
                    val dy = event.y - blackoutDownY
                    if (!blackoutMoved && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                        blackoutMoved = true
                    }
                    showBlackoutHintImmediate()
                    if (blackoutMoved) {
                        val offset = minOf(0f, dy)
                        blackoutSwipeOffset += (offset - blackoutSwipeOffset) * BLACKOUT_SWIPE_SMOOTHING
                        view.translationY = blackoutSwipeOffset
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - blackoutDownX
                    val dy = event.y - blackoutDownY
                    val isSwipeUp = dy <= -blackoutSwipeMinPx && abs(dy) > abs(dx)
                    if (isSwipeUp) {
                        val target = -view.height.toFloat().coerceAtLeast(1f)
                        view.animate()
                            .translationY(target)
                            .setDuration(BLACKOUT_SWIPE_ANIM_MS)
                            .withEndAction {
                                view.translationY = 0f
                                blackoutSwipeOffset = 0f
                                unlockFromBlackout()
                            }
                            .start()
                    } else if (!blackoutMoved) {
                        showBlackoutHintImmediate()
                        view.animate()
                            .translationY(0f)
                            .setDuration(BLACKOUT_SWIPE_ANIM_MS)
                            .withEndAction { blackoutSwipeOffset = 0f }
                            .start()
                    } else {
                        view.animate()
                            .translationY(0f)
                            .setDuration(BLACKOUT_SWIPE_ANIM_MS)
                            .withEndAction { blackoutSwipeOffset = 0f }
                            .start()
                    }
                    if (!isSwipeUp) {
                        scheduleBlackoutHintFade()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    scheduleBlackoutHintFade()
                    true
                }
                else -> false
            }
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
                            } else if (!success &&
                                SessionStore.lastBackFailure == "dispatch_failed"
                            ) {
                                val message =
                                    getString(R.string.touchpad_back_dispatch_failed)
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
        DisplaySessionManager.addListener(this)
        refreshShizukuBinderState()
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
        if (SettingsStore.touchpadAutoFocusEnabled) {
            ControlAccessibilityService.requestExternalFocusWarmup("touchpad_resume")
        }
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
        DisplaySessionManager.removeListener(this)
        stopAutoDimSession()
        cancelLongPress()
        exitScrollMode()
        super.onStop()
    }

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        if (info == null) {
            cancelDimTimer()
            cancelDimAnimator()
            restoreOriginalBrightness()
            setBlackoutVisible(false)
            DiagnosticsLog.add("Touchpad: brightness restored (external display removed)")
        } else if (touchpadActive) {
            startAutoDimSession()
        }
    }

    override fun onDestroy() {
        stopAutoDimSession()
        cancelLongPress()
        exitScrollMode()
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (binding.blackoutOverlay.isVisible) {
            return super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val rect = android.graphics.Rect()
            binding.touchpadArea.getGlobalVisibleRect(rect)
            val inTouchpad = rect.contains(event.rawX.toInt(), event.rawY.toInt())
            setTouchpadActive(inTouchpad)
            if (inTouchpad && SettingsStore.touchpadAutoFocusEnabled) {
                // Proactively warm up external focus on first finger down to reduce
                // "back no-op due to missing focus" right after returning to touchpad.
                ControlAccessibilityService.requestExternalFocusWarmup("touch_down")
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun handleTouch(event: MotionEvent) {
        val service = serviceOrToast() ?: return
        if (suppressSingleUntilUp) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    suppressSingleUntilUp = false
                    touchState = TouchState.IDLE
                    return
                }
                MotionEvent.ACTION_DOWN -> {
                    suppressSingleUntilUp = false
                }
                else -> return
            }
        }
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
                    val movedForLongPress = abs(event.x - downX) > longPressCancelSlopPx ||
                        abs(event.y - downY) > longPressCancelSlopPx
                    if (movedForLongPress) {
                        cancelLongPress()
                    }
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
                    suppressSingleUntilUp = true
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (touchState == TouchState.SCROLL_MODE) {
                    exitScrollMode()
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
            val moved = abs(lastTouchX - downX) > longPressCancelSlopPx ||
                abs(lastTouchY - downY) > longPressCancelSlopPx
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
            hideBlackoutHint()
            binding.blackoutOverlay.translationY = 0f
        } else {
            hideBlackoutHint()
            binding.blackoutOverlay.translationY = 0f
        }
        binding.blackoutOverlay.isVisible = visible
        DiagnosticsLog.add("Touchpad: blackout=$visible")
    }

    private fun unlockFromBlackout() {
        setBlackoutVisible(false)
        restoreOriginalBrightness()
        if (touchpadActive) {
            startAutoDimSession()
        }
    }

    private fun showBlackoutHintImmediate() {
        val hint = binding.blackoutHint
        hint.animate().cancel()
        blackoutHintFadeRunnable?.let { handler.removeCallbacks(it) }
        hint.alpha = 1f
        hint.isVisible = true
    }

    private fun scheduleBlackoutHintFade() {
        blackoutHintFadeRunnable?.let { handler.removeCallbacks(it) }
        blackoutHintFadeRunnable = Runnable {
            val hint = binding.blackoutHint
            if (!binding.blackoutOverlay.isVisible) return@Runnable
            hint.animate()
                .alpha(0f)
                .setDuration(BLACKOUT_HINT_FADE_MS)
                .withEndAction { hint.isVisible = false }
                .start()
        }
        handler.postDelayed(blackoutHintFadeRunnable!!, BLACKOUT_HINT_VISIBLE_MS)
    }

    private fun hideBlackoutHint() {
        blackoutHintFadeRunnable?.let { handler.removeCallbacks(it) }
        blackoutHintFadeRunnable = null
        binding.blackoutHint.animate().cancel()
        binding.blackoutHint.alpha = 0f
        binding.blackoutHint.isVisible = false
    }

    private fun enterScrollMode(service: ControlAccessibilityService, event: MotionEvent) {
        touchState = TouchState.SCROLL_MODE
        val useDirect = SettingsStore.touchpadDirectScrollGestureEnabled &&
            directScrollController.enter(service, event)
        if (useDirect) {
            activeScrollController = ActiveScrollController.DIRECT
            return
        }
        legacyScrollController.enter(service, event)
        activeScrollController = ActiveScrollController.LEGACY
    }

    private fun updateScrollMode(event: MotionEvent) {
        when (activeScrollController) {
            ActiveScrollController.DIRECT -> directScrollController.update(event)
            ActiveScrollController.LEGACY -> legacyScrollController.update(event)
            ActiveScrollController.NONE -> Unit
        }
    }

    private fun exitScrollMode() {
        if (touchState != TouchState.SCROLL_MODE) return
        touchState = TouchState.IDLE
        when (activeScrollController) {
            ActiveScrollController.DIRECT -> directScrollController.exit()
            ActiveScrollController.LEGACY -> legacyScrollController.exit()
            ActiveScrollController.NONE -> Unit
        }
        activeScrollController = ActiveScrollController.NONE
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
        updateShizukuButton()
    }

    private fun updateShizukuButton() {
        if (!this::binding.isInitialized) return
        refreshShizukuBinderState()
        val available = isShizukuAvailable()
        val alpha = if (available) 1f else 0.5f
        binding.btnEnableAccessibilityShizuku.alpha = alpha
        binding.btnEnableAccessibilityShizuku.isEnabled = !shizukuEnableInFlight
    }

    private fun refreshShizukuBinderState() {
        shizukuBinderReady = shizukuBinderReady || isShizukuBinderAlive()
    }

    private fun requestAccessibilityViaShizuku() {
        val available = isShizukuAvailable()
        if (!available) {
            showShizukuIntroDialog()
            return
        }
        val permission = try {
            Shizuku.checkSelfPermission()
        } catch (e: Throwable) {
            DiagnosticsLog.add("Shizuku: not running (${e.javaClass.simpleName})")
            showShizukuIntroDialog()
            return
        }
        if (permission == PackageManager.PERMISSION_GRANTED) {
            enableAccessibilityWithShizuku()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(
                this,
                getString(R.string.touchpad_shizuku_permission_rationale),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        } catch (e: Throwable) {
            DiagnosticsLog.add("Shizuku: requestPermission failed ${e.javaClass.simpleName}")
            showShizukuIntroDialog()
        }
    }

    private fun enableAccessibilityWithShizuku() {
        if (shizukuEnableInFlight) return
        shizukuEnableInFlight = true
        updateShizukuButton()
        Thread {
            val success = enableAccessibilityWithShizukuInternal()
            runOnUiThread {
                shizukuEnableInFlight = false
                updateShizukuButton()
                if (success) {
                    Toast.makeText(
                        this,
                        getString(R.string.touchpad_shizuku_enable_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.touchpad_shizuku_enable_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updateAccessibilityGate()
            }
        }.start()
    }

    private fun enableAccessibilityWithShizukuInternal(): Boolean {
        val component = ComponentName(this, ControlAccessibilityService::class.java)
            .flattenToString()
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val updated = mergeAccessibilityServices(current, component)
        val setServices = runShizukuCommand(
            arrayOf(
                "settings",
                "put",
                "secure",
                "enabled_accessibility_services",
                updated
            )
        )
        if (setServices.exitCode != 0) {
            DiagnosticsLog.add(
                "Shizuku: enable services failed code=${setServices.exitCode} err=${setServices.error}"
            )
            return false
        }
        val enable = runShizukuCommand(
            arrayOf("settings", "put", "secure", "accessibility_enabled", "1")
        )
        if (enable.exitCode != 0) {
            DiagnosticsLog.add(
                "Shizuku: enable accessibility flag failed code=${enable.exitCode} err=${enable.error}"
            )
            return false
        }
        SystemClock.sleep(150)
        return ControlAccessibilityService.isEnabled(this)
    }

    private fun mergeAccessibilityServices(current: String?, component: String): String {
        if (current.isNullOrBlank() || current == "null") {
            return component
        }
        val entries = current.split(":").filter { it.isNotBlank() }
        if (entries.contains(component)) {
            return entries.joinToString(":")
        }
        return (entries + component).joinToString(":")
    }

    private fun runShizukuCommand(args: Array<String>): ShizukuCommandResult {
        return try {
            val process = newShizukuProcess(args)
                ?: return ShizukuCommandResult(-1, "", "newProcess unavailable")
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            ShizukuCommandResult(exitCode, output, error)
        } catch (e: Exception) {
            ShizukuCommandResult(-1, "", e.message ?: "unknown")
        }
    }

    private fun isShizukuBinderAlive(): Boolean {
        return try {
            val cls = Shizuku::class.java
            val method = cls.declaredMethods.firstOrNull { candidate ->
                (candidate.name == "pingBinder" || candidate.name == "isBinderAlive") &&
                    candidate.parameterTypes.isEmpty()
            } ?: return false
            method.isAccessible = true
            (method.invoke(null) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun showShizukuIntroDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.touchpad_shizuku_intro_title)
            .setMessage(getString(R.string.touchpad_shizuku_intro_message))
            .setPositiveButton(R.string.touchpad_shizuku_intro_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun newShizukuProcess(args: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.declaredMethods.firstOrNull { candidate ->
                candidate.name == "newProcess" && candidate.parameterTypes.size == 3
            } ?: return null
            method.isAccessible = true
            val paramTypes = method.parameterTypes
            val envArg = if (paramTypes[1].isArray) {
                null
            } else {
                null
            }
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, args, envArg, null) as? Process
        } catch (_: Exception) {
            null
        }
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
        private const val LONG_PRESS_CANCEL_DP = 3f
        private const val BLACKOUT_SWIPE_MIN_DP = 120f
        private const val BLACKOUT_HINT_VISIBLE_MS = 2000L
        private const val BLACKOUT_HINT_FADE_MS = 400L
        private const val BLACKOUT_SWIPE_ANIM_MS = 180L
        private const val BLACKOUT_SWIPE_SMOOTHING = 0.25f
        private const val SHIZUKU_PERMISSION_REQUEST = 1201
    }

    private data class ShizukuCommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    private enum class TouchState {
        IDLE,
        ONE_FINGER_DOWN,
        MOVING_CURSOR,
        DRAGGING,
        SCROLL_MODE
    }

    private enum class ActiveScrollController {
        NONE,
        LEGACY,
        DIRECT
    }
}
