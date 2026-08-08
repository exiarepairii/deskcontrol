package com.deskcontrol

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import com.deskcontrol.databinding.ActivityTouchpadBinding
import com.google.android.material.slider.Slider

class TouchpadActivity : AppCompatActivity(), DisplaySessionManager.Listener {

    private lateinit var binding: ActivityTouchpadBinding
    private lateinit var windowPolicy: ControlSurfaceWindowPolicy
    private lateinit var blackoutController: ScreenBlackoutController
    private lateinit var accessibilityGateController: AccessibilityGateController
    private lateinit var backController: ControlSurfaceBackController
    private lateinit var gestureController: ControlSurfaceGestureController
    private lateinit var onboardingController: ControlSurfaceOnboardingController
    private lateinit var tuningPanelController: ControlSurfaceTuningPanelController
    private lateinit var modeIntroController: ControlSurfaceModeIntroController
    private lateinit var volumeKeyController: ControlSurfaceVolumeKeyController
    private val handler = Handler(Looper.getMainLooper())
    private var externalDisplayAvailable = false
    private var touchpadActive = false
    private var introDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTouchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsStore.setLastControlSurface(this, ControlSurfaceMode.TOUCHPAD)
        windowPolicy = ControlSurfaceWindowPolicy(
            activity = this,
            logName = "Touchpad",
            onDimmedChanged = { dimmed ->
                binding.touchpadHint.isInvisible = dimmed
            }
        )
        DiagnosticsLog.add(
            "Touchpad: create savedState=${savedInstanceState != null} " +
                DiagnosticsState.activity(this)
        )
        configureOledControlSurfaceWindow(binding.root, binding.touchpadToolbar)
        volumeControlStream = AudioManager.STREAM_MUSIC
        tuningPanelController = ControlSurfaceTuningPanelController(
            root = binding.touchpadRoot,
            panel = binding.tuningPanel
        ).also { it.setExpanded(false) }

        gestureController = ControlSurfaceGestureController(
            context = this,
            touchpadSizeProvider = { binding.touchpadArea.width to binding.touchpadArea.height },
            handler = handler,
            controlArea = binding.touchpadArea,
            serviceProvider = { ControlAccessibilityService.current() },
            singlePointerMode = ControlSurfaceGestureController.SinglePointerMode.POINTER,
            onServiceUnavailable = ::showAccessibilityRequiredToast
        )
        onboardingController = ControlSurfaceOnboardingController(
            activity = this,
            logName = "Touchpad",
            canPrompt = {
                SettingsStore.touchpadIntroShown && !introDialogVisible
            }
        )
        modeIntroController = ControlSurfaceModeIntroController(
            activity = this,
            root = binding.touchpadRoot,
            currentMode = ControlSurfaceMode.TOUCHPAD,
            switchTarget = binding.touchpadSwitchToRay,
            blackoutTarget = binding.touchpadBlackout,
            controlArea = binding.touchpadArea,
            onActivationRequested = {
                setTouchpadActive(true)
                backController.warmUpOnActivation("tutorial_activation")
            },
            onVisibilityChanged = { visible -> introDialogVisible = visible },
            onFinished = {
                onboardingController.onStateChanged()
                showExternalControlTutorial()
            }
        )
        blackoutController = ScreenBlackoutController(
            overlay = binding.blackoutOverlay,
            hint = binding.blackoutHint,
            logName = "Touchpad",
            onBeforeShow = gestureController::finishActiveGesture,
            onUserInteraction = windowPolicy::restartAutoDimCountdown,
            onUnlocked = windowPolicy::restartAutoDimCountdown,
            onVisibilityChanged = ControlAccessibilityService::requestCursorForceHidden
        )
        volumeKeyController = ControlSurfaceVolumeKeyController(
            context = this,
            handler = handler,
            logName = "Touchpad",
            isBlackoutVisible = { blackoutController.isVisible },
            onInteraction = windowPolicy::restartAutoDimCountdown,
            onCalibrationRequested = ::centerCursorFromVolumeKey,
            onBlackoutToggleRequested = ::toggleBlackoutFromVolumeKey
        )
        accessibilityGateController = AccessibilityGateController(
            activity = this,
            gate = binding.accessibilityGate,
            content = binding.touchpadContent,
            controlArea = binding.touchpadArea,
            tuningPanel = binding.tuningPanel,
            openSettingsButton = binding.btnOpenAccessibility,
            advancedEnableButton = binding.btnEnableAccessibilityAdvanced,
            onEnabledChanged = { enabled ->
                setTouchpadActive(false)
                if (enabled) {
                    modeIntroController.showChoiceIfNeeded()
                }
                onboardingController.onStateChanged()
            }
        )
        backController = ControlSurfaceBackController(
            activity = this,
            logName = "Touchpad",
            isControlActive = { touchpadActive }
        )

        binding.touchpadBack.setOnClickListener {
            DiagnosticsLog.add("Touchpad: exit via toolbar")
            finish()
        }
        binding.touchpadSwitchToRay.setOnClickListener {
            gestureController.finishActiveGesture()
            DiagnosticsLog.add("Touchpad: switch to MotionMouse")
            switchControlSurface(RayMouseActivity::class.java)
        }
        binding.touchpadBlackout.setOnClickListener {
            gestureController.finishActiveGesture()
            setTouchpadActive(false)
            blackoutController.show()
        }
        binding.touchpadMore.setOnClickListener {
            showMoreMenu()
        }

        binding.touchpadArea.setOnTouchListener { _, event ->
            gestureController.handle(event)
        }

        setupTuningControls()
        if (intent.getBooleanExtra(
                ControlSurfaceModeIntroController.EXTRA_SHOW_SWITCH_COACHMARK,
                false
            )
        ) {
            introDialogVisible = true
            binding.touchpadRoot.post(modeIntroController::showSwitchCoachmark)
        }
        setTouchpadActive(false)
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsLog.add(
            "Touchpad: start blackout=${blackoutController.isVisible} " +
                DiagnosticsState.activity(this)
        )
        DisplaySessionManager.addListener(this)
        accessibilityGateController.onStart()
        onboardingController.onStateChanged()
    }

    override fun onResume() {
        super.onResume()
        accessibilityGateController.refresh()
        ControlAccessibilityService.setControlSurfaceKeyHandler(volumeKeyController::handle)
        refreshTuningControls()
        windowPolicy.onResume()
        updateWindowPolicyActivity()
        backController.warmUpOnResume("touchpad_resume")
        DiagnosticsLog.add(
            "Touchpad: resume blackout=${blackoutController.isVisible} " +
                DiagnosticsState.activity(this)
        )
    }

    override fun onPause() {
        DiagnosticsLog.add(
            "Touchpad: pause blackout=${blackoutController.isVisible} " +
                "changingConfig=$isChangingConfigurations finishing=$isFinishing " +
                DiagnosticsState.activity(this)
        )
        ControlAccessibilityService.dismissControlTutorial()
        ControlAccessibilityService.setControlSurfaceKeyHandler(null)
        volumeKeyController.cancel()
        windowPolicy.onPause()
        gestureController.cancel()
        super.onPause()
    }

    override fun onStop() {
        DiagnosticsLog.add(
            "Touchpad: stop blackout=${blackoutController.isVisible} " +
                "changingConfig=$isChangingConfigurations finishing=$isFinishing"
        )
        DisplaySessionManager.removeListener(this)
        windowPolicy.onStop()
        gestureController.cancel()
        super.onStop()
    }

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        val externalDisplayWasAvailable = externalDisplayAvailable
        DiagnosticsLog.add(
            "Touchpad: display callback info=${info?.displayId ?: "none"} " +
                "previousAvailable=$externalDisplayWasAvailable " +
                "blackout=${blackoutController.isVisible}"
        )
        externalDisplayAvailable = info != null
        if (externalDisplayWasAvailable && info == null) {
            blackoutController.hide("external_display_missing")
            DiagnosticsLog.add("Touchpad: brightness restored (external display removed)")
        }
        updateWindowPolicyActivity()
        onboardingController.onStateChanged()
    }

    override fun onDestroy() {
        DiagnosticsLog.add(
            "Touchpad: destroy blackout=${blackoutController.isVisible} " +
                "changingConfig=$isChangingConfigurations finishing=$isFinishing " +
                DiagnosticsState.activity(this)
        )
        volumeKeyController.cancel()
        blackoutController.destroy()
        accessibilityGateController.onDestroy()
        onboardingController.onDestroy()
        modeIntroController.destroy()
        windowPolicy.onDestroy()
        gestureController.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DiagnosticsLog.add(
            "Touchpad: saveInstanceState blackout=${blackoutController.isVisible} " +
                DiagnosticsState.activity(this)
        )
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::windowPolicy.isInitialized) {
            windowPolicy.onWindowFocusChanged(hasFocus)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (blackoutController.isVisible) {
            return super.dispatchTouchEvent(event)
        }
        if (introDialogVisible) {
            return super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val rect = android.graphics.Rect()
            binding.touchpadArea.getGlobalVisibleRect(rect)
            val inTouchpad = rect.contains(event.rawX.toInt(), event.rawY.toInt())
            setTouchpadActive(inTouchpad)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (volumeKeyController.handle(event)) true else super.dispatchKeyEvent(event)
    }

    private fun showMoreMenu() {
        PopupMenu(this, binding.touchpadMore).apply {
            inflate(R.menu.control_surface_menu)
            menu.findItem(R.id.action_control_surface_tuning).isChecked =
                tuningPanelController.isExpanded
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_control_surface_tuning -> {
                        tuningPanelController.toggle()
                        true
                    }
                    R.id.action_replay_control_tutorial -> {
                        if (ControlAccessibilityService.isEnabled(this@TouchpadActivity)) {
                            gestureController.finishActiveGesture()
                            setTouchpadActive(false)
                            modeIntroController.replay()
                        } else {
                            accessibilityGateController.refresh()
                        }
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun setTouchpadActive(active: Boolean) {
        val resolvedActive = active &&
            externalDisplayAvailable &&
            ControlAccessibilityService.isEnabled(this) &&
            !blackoutController.isVisible
        val wasActive = touchpadActive
        touchpadActive = resolvedActive
        binding.touchpadArea.isActivated = resolvedActive
        val hintColorRes = if (resolvedActive) {
            R.color.touchpadHintActive
        } else {
            R.color.touchpadHintInactive
        }
        binding.touchpadHint.setTextColor(ContextCompat.getColor(this, hintColorRes))
        if (wasActive != resolvedActive) {
            DiagnosticsLog.add("Touchpad: active=$resolvedActive")
        }
        updateWindowPolicyActivity()
    }

    private fun centerCursorFromVolumeKey(): ControlSurfaceVolumeKeyController.CalibrationResult {
        val service = ControlAccessibilityService.current()
        val failureReason = when {
            !externalDisplayAvailable -> "external_display_missing"
            !ControlAccessibilityService.isEnabled(this) -> "accessibility_disabled"
            service == null -> "accessibility_service_unavailable"
            !service.hasExternalDisplaySession() -> "external_display_session_missing"
            else -> null
        }
        if (failureReason != null || service == null) {
            binding.root.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return ControlSurfaceVolumeKeyController.CalibrationResult(
                success = false,
                failureReason = failureReason ?: "unknown"
            )
        }
        service.centerCursorOnExternalDisplay()
        setTouchpadActive(true)
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return ControlSurfaceVolumeKeyController.CalibrationResult(success = true)
    }

    private fun toggleBlackoutFromVolumeKey(wasBlackoutVisible: Boolean) {
        gestureController.finishActiveGesture()
        if (wasBlackoutVisible) {
            blackoutController.hide("volume_up_hold")
            binding.touchpadArea.requestFocusFromTouch()
            setTouchpadActive(true)
            backController.warmUpOnActivation("volume_up_unlock")
        } else {
            setTouchpadActive(false)
            blackoutController.show()
        }
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun showExternalControlTutorial() {
        if (ControlAccessibilityService.requestControlTutorial(ControlSurfaceMode.TOUCHPAD)) {
            binding.root.announceForAccessibility(
                getString(R.string.external_tutorial_move_to_circle)
            )
        }
    }

    private fun updateWindowPolicyActivity() {
        if (!::windowPolicy.isInitialized) return
        windowPolicy.setInteractionActive(touchpadActive && externalDisplayAvailable)
    }

    private fun setupTuningControls() {
        setupSlider(
            binding.sliderBaseGain,
            valueFrom = SettingsSliderRanges.CURSOR_SPEED.start,
            valueTo = SettingsSliderRanges.CURSOR_SPEED.end,
            stepSize = SettingsSliderRanges.CURSOR_SPEED.step,
            currentValue = TouchpadTuning.baseGain,
            valueView = binding.touchpadBaseGainValue,
            valueFormatRes = R.string.settings_cursor_speed_value
        ) { SettingsStore.setPointerSpeed(this, it) }

        setupSlider(
            binding.sliderAccel,
            valueFrom = 0.6f,
            valueTo = 3.5f,
            stepSize = 0.1f,
            currentValue = TouchpadTuning.maxAccelGain,
            valueView = binding.touchpadAccelerationValue,
            valueFormatRes = R.string.touchpad_tuning_multiplier_value
        ) { SettingsStore.setTouchpadMaxAccel(this, it) }

        setupSlider(
            binding.sliderSpeed,
            valueFrom = 0.6f,
            valueTo = 2.8f,
            stepSize = 0.1f,
            currentValue = TouchpadTuning.speedForMaxAccel,
            valueView = binding.touchpadMaxAccelSpeedValue,
            valueFormatRes = R.string.touchpad_tuning_speed_value
        ) { SettingsStore.setTouchpadSpeedForMaxAccel(this, it) }

        setupSlider(
            binding.sliderJitter,
            valueFrom = 0.1f,
            valueTo = 2.0f,
            stepSize = 0.1f,
            currentValue = TouchpadTuning.jitterThresholdPx,
            valueView = binding.touchpadJitterValue,
            valueFormatRes = R.string.touchpad_tuning_px_value
        ) { SettingsStore.setTouchpadJitter(this, it) }

        setupSlider(
            binding.sliderSmoothing,
            valueFrom = 0.05f,
            valueTo = 0.85f,
            stepSize = 0.05f,
            currentValue = TouchpadTuning.emaAlpha,
            valueView = binding.touchpadSmoothingValue,
            valueFormatRes = R.string.ray_mouse_tuning_decimal_value
        ) { SettingsStore.setTouchpadSmoothing(this, it) }

        setupSlider(
            binding.sliderTouchpadDragBoostInline,
            valueFrom = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.start,
            valueTo = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.end,
            stepSize = SettingsSliderRanges.TOUCHPAD_DRAG_BOOST.step,
            currentValue = TouchpadTuning.dragBoost,
            valueView = binding.touchpadDragBoostInlineValue,
            valueFormatRes = R.string.settings_touchpad_drag_boost_value
        ) { SettingsStore.setTouchpadDragBoost(this, it) }

        binding.switchTouchpadScrollInvertInline.isChecked =
            SettingsStore.touchpadScrollInverted
        binding.switchTouchpadScrollInvertInline.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setTouchpadScrollInverted(this, checked)
        }

        setupSlider(
            binding.sliderTouchpadScrollSpeedInline,
            valueFrom = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.start,
            valueTo = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.end,
            stepSize = SettingsSliderRanges.TOUCHPAD_SCROLL_SPEED.step,
            currentValue = SettingsStore.touchpadScrollSpeed,
            valueView = binding.touchpadScrollSpeedInlineValue,
            valueFormatRes = R.string.settings_touchpad_scroll_speed_value
        ) { SettingsStore.setTouchpadScrollSpeed(this, it) }

        setupSlider(
            binding.sliderTouchpadScrollDistanceInline,
            valueFrom = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.start,
            valueTo = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.end,
            stepSize = SettingsSliderRanges.TOUCHPAD_SCROLL_DISTANCE.step,
            currentValue = SettingsStore.touchpadScrollStepDp,
            valueView = binding.touchpadScrollDistanceInlineValue,
            valueFormatRes = R.string.settings_touchpad_scroll_distance_value
        ) { SettingsStore.setTouchpadScrollStepDp(this, it) }
    }

    private fun refreshTuningControls() {
        binding.sliderBaseGain.value = TouchpadTuning.baseGain
        binding.sliderAccel.value = TouchpadTuning.maxAccelGain
        binding.sliderSpeed.value = TouchpadTuning.speedForMaxAccel
        binding.sliderJitter.value = TouchpadTuning.jitterThresholdPx
        binding.sliderSmoothing.value = TouchpadTuning.emaAlpha
        binding.sliderTouchpadDragBoostInline.value = TouchpadTuning.dragBoost
        binding.switchTouchpadScrollInvertInline.isChecked =
            SettingsStore.touchpadScrollInverted
        binding.sliderTouchpadScrollSpeedInline.value = SettingsStore.touchpadScrollSpeed
        binding.sliderTouchpadScrollDistanceInline.value = SettingsStore.touchpadScrollStepDp
    }

    private fun setupSlider(
        slider: Slider,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        currentValue: Float,
        valueView: android.widget.TextView,
        valueFormatRes: Int,
        onChange: (Float) -> Unit
    ) {
        slider.valueFrom = valueFrom
        slider.valueTo = valueTo
        slider.stepSize = stepSize
        slider.value = currentValue.coerceIn(valueFrom, valueTo)
        valueView.text = getString(valueFormatRes, slider.value)
        slider.addOnChangeListener { _, value, fromUser ->
            valueView.text = getString(valueFormatRes, value)
            if (fromUser) {
                onChange(value)
            }
        }
    }

    private fun showAccessibilityRequiredToast() {
        Toast.makeText(
            this,
            getString(R.string.touchpad_accessibility_required_toast),
            Toast.LENGTH_SHORT
        ).show()
    }

}
