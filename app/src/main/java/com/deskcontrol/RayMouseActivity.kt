package com.deskcontrol

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import com.deskcontrol.databinding.ActivityRayMouseBinding
import com.google.android.material.slider.Slider

class RayMouseActivity : AppCompatActivity(), DisplaySessionManager.Listener {

    private lateinit var binding: ActivityRayMouseBinding
    private lateinit var controller: RayMouseController
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
    private var displayInfo: DisplaySessionManager.ExternalDisplayInfo? = null
    private var sensorStarted = false
    private var initialCalibrationDone = false
    private var rayMouseActive = false
    private var introDialogVisible = false
    private var autoCalibrationScheduled = false
    private var autoCalibrationRetryCount = 0
    private val autoCalibrateRunnable = object : Runnable {
        override fun run() {
            autoCalibrationScheduled = false
            tryAutoCalibrate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRayMouseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsStore.setLastControlSurface(this, ControlSurfaceMode.RAY_MOUSE)
        windowPolicy = ControlSurfaceWindowPolicy(
            activity = this,
            logName = "MotionMouse",
            onDimmedChanged = { dimmed ->
                binding.rayMouseHint.isInvisible = dimmed
            }
        )
        gestureController = ControlSurfaceGestureController(
            context = this,
            handler = handler,
            controlArea = binding.rayMouseArea,
            touchpadSizeProvider = {
                binding.rayMouseArea.width to binding.rayMouseArea.height
            },
            serviceProvider = { ControlAccessibilityService.current() },
            singlePointerMode = ControlSurfaceGestureController.SinglePointerMode.GESTURE_ONLY,
            twoFingerScrollEnabled = false,
            onServiceUnavailable = {
                Toast.makeText(
                    this,
                    R.string.touchpad_accessibility_required_toast,
                    Toast.LENGTH_SHORT
                ).show()
            },
            onTap = { performRayHaptic(HapticFeedbackConstants.KEYBOARD_TAP) },
            onDirectGestureStarted = {
                performRayHaptic(HapticFeedbackConstants.LONG_PRESS)
            },
            onTouchActiveChanged = { active ->
                if (!active && ::controller.isInitialized) {
                    ControlAccessibilityService.current()?.let(::rebaseToCurrentCursor)
                }
            }
        )
        onboardingController = ControlSurfaceOnboardingController(
            activity = this,
            logName = "MotionMouse",
            canPrompt = {
                SettingsStore.rayMouseIntroShown && !introDialogVisible
            }
        )
        modeIntroController = ControlSurfaceModeIntroController(
            activity = this,
            root = binding.rayMouseRoot,
            currentMode = ControlSurfaceMode.RAY_MOUSE,
            switchTarget = binding.btnRaySwitchToTouch,
            blackoutTarget = binding.btnRayBlackout,
            controlArea = binding.rayMouseArea,
            onActivationRequested = {
                setRayMouseActive(true)
                backController.warmUpOnActivation("tutorial_activation")
            },
            onVisibilityChanged = { visible -> introDialogVisible = visible },
            onFinished = {
                onboardingController.onStateChanged()
                showExternalControlTutorial()
            }
        )
        blackoutController = ScreenBlackoutController(
            overlay = binding.rayBlackoutOverlay,
            hint = binding.rayBlackoutHint,
            logName = "MotionMouse",
            onBeforeShow = gestureController::finishActiveGesture,
            onUserInteraction = windowPolicy::restartAutoDimCountdown,
            onUnlocked = windowPolicy::restartAutoDimCountdown,
            onVisibilityChanged = ControlAccessibilityService::requestCursorForceHidden
        )
        accessibilityGateController = AccessibilityGateController(
            activity = this,
            gate = binding.rayAccessibilityGate,
            content = binding.rayMouseContent,
            controlArea = binding.rayMouseArea,
            tuningPanel = binding.rayTuningContent,
            openSettingsButton = binding.btnRayOpenAccessibility,
            advancedEnableButton = binding.btnRayEnableAccessibilityAdvanced,
            onEnabledChanged = { enabled ->
                setRayMouseActive(false)
                if (enabled && !introDialogVisible) {
                    showRayMouseIntroIfNeeded()
                }
                onboardingController.onStateChanged()
            }
        )
        backController = ControlSurfaceBackController(
            activity = this,
            logName = "MotionMouse",
            isControlActive = { rayMouseActive }
        )
        DiagnosticsLog.add(
            "MotionMouse: create savedState=${savedInstanceState != null} " +
                DiagnosticsState.activity(this)
        )
        configureOledControlSurfaceWindow(binding.root, binding.rayMouseToolbar)
        tuningPanelController = ControlSurfaceTuningPanelController(
            root = binding.rayMouseRoot,
            panel = binding.rayTuningContent
        ).also { it.setExpanded(false) }
        volumeControlStream = AudioManager.STREAM_MUSIC

        controller = RayMouseController(
            context = this,
            onPointChanged = { point ->
                if (rayMouseActive && !gestureController.isTouchActive) {
                    ControlAccessibilityService.current()?.moveCursorTo(point.x, point.y)
                }
            },
            onOrientationReady = {
                runOnUiThread {
                    tryAutoCalibrate()
                    updateState()
                }
            }
        )
        volumeKeyController = ControlSurfaceVolumeKeyController(
            context = this,
            handler = handler,
            logName = "MotionMouse",
            isBlackoutVisible = { blackoutController.isVisible },
            onInteraction = windowPolicy::restartAutoDimCountdown,
            onCalibrationRequested = ::performVolumeKeyCalibration,
            onBlackoutToggleRequested = ::toggleBlackoutFromVolumeKey
        )

        binding.rayMouseBack.setOnClickListener { finish() }
        binding.btnRaySwitchToTouch.setOnClickListener {
            gestureController.finishActiveGesture()
            DiagnosticsLog.add("MotionMouse: switch to Touchpad")
            switchControlSurface(TouchpadActivity::class.java)
        }
        binding.btnRayBlackout.setOnClickListener {
            gestureController.finishActiveGesture()
            setRayMouseActive(false)
            blackoutController.show()
        }
        binding.rayMouseArea.setOnTouchListener { _, event ->
            gestureController.handle(event)
        }
        binding.btnRayMore.setOnClickListener {
            showMoreMenu()
        }
        setupTuningControls()
        setupHapticFeedbackSwitch()
        if (intent.getBooleanExtra(
                ControlSurfaceModeIntroController.EXTRA_SHOW_SWITCH_COACHMARK,
                false
            )
        ) {
            introDialogVisible = true
            binding.rayMouseRoot.post(modeIntroController::showSwitchCoachmark)
        }
        setRayMouseActive(false)
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsLog.add(
            "MotionMouse: start blackout=${blackoutController.isVisible} " +
                DiagnosticsState.activity(this)
        )
        DisplaySessionManager.addListener(this)
        accessibilityGateController.onStart()
        updateState()
        onboardingController.onStateChanged()
    }

    override fun onResume() {
        super.onResume()
        accessibilityGateController.refresh()
        ControlAccessibilityService.setControlSurfaceKeyHandler(volumeKeyController::handle)
        refreshTuningControls()
        windowPolicy.onResume()
        startSensorsIfPossible()
        autoCalibrationRetryCount = 0
        tryAutoCalibrate()
        updateState()
        backController.warmUpOnResume("ray_mouse_resume")
        DiagnosticsLog.add(
            "MotionMouse: resume blackout=${blackoutController.isVisible} " +
                DiagnosticsState.activity(this)
        )
    }

    override fun onPause() {
        DiagnosticsLog.add(
            "MotionMouse: pause blackout=${blackoutController.isVisible} " +
                "changingConfig=$isChangingConfigurations finishing=$isFinishing " +
                DiagnosticsState.activity(this)
        )
        ControlAccessibilityService.dismissControlTutorial()
        ControlAccessibilityService.setControlSurfaceKeyHandler(null)
        windowPolicy.onPause()
        volumeKeyController.cancel()
        cancelAutoCalibrate()
        gestureController.cancel()
        stopSensors()
        super.onPause()
    }

    override fun onStop() {
        DiagnosticsLog.add(
            "MotionMouse: stop blackout=${blackoutController.isVisible} " +
                "changingConfig=$isChangingConfigurations finishing=$isFinishing"
        )
        DisplaySessionManager.removeListener(this)
        windowPolicy.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        DiagnosticsLog.add(
            "MotionMouse: destroy blackout=${blackoutController.isVisible} " +
                "changingConfig=$isChangingConfigurations finishing=$isFinishing " +
                DiagnosticsState.activity(this)
        )
        volumeKeyController.cancel()
        handler.removeCallbacksAndMessages(null)
        blackoutController.destroy()
        accessibilityGateController.onDestroy()
        onboardingController.onDestroy()
        modeIntroController.destroy()
        windowPolicy.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DiagnosticsLog.add(
            "MotionMouse: saveInstanceState blackout=${blackoutController.isVisible} " +
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
        if (introDialogVisible) {
            return super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !blackoutController.isVisible) {
            val areaBounds = android.graphics.Rect()
            binding.rayMouseArea.getGlobalVisibleRect(areaBounds)
            val inControlArea = areaBounds.contains(event.rawX.toInt(), event.rawY.toInt())
            setRayMouseActive(inControlArea)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (volumeKeyController.handle(event)) true else super.dispatchKeyEvent(event)
    }

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        val previousDisplayInfo = displayInfo
        DiagnosticsLog.add(
            "MotionMouse: display callback info=${info?.displayId ?: "none"} " +
                "previousInfo=${previousDisplayInfo?.displayId ?: "none"} " +
                "blackout=${blackoutController.isVisible}"
        )
        if (previousDisplayInfo != info) {
            gestureController.cancel()
            setRayMouseActive(false)
            controller.resetCalibration()
            initialCalibrationDone = false
        }
        displayInfo = info
        if (previousDisplayInfo != null && info == null) {
            blackoutController.hide("external_display_missing")
        }
        autoCalibrationRetryCount = 0
        tryAutoCalibrate()
        updateState()
        onboardingController.onStateChanged()
    }

    override fun onDisplaysUpdated(
        displays: List<DisplaySessionManager.ExternalDisplayInfo>,
        selectedDisplayId: Int?
    ) = Unit

    private fun startSensorsIfPossible() {
        if (sensorStarted || !controller.isAvailable) return
        controller.start()
        sensorStarted = true
    }

    private fun stopSensors() {
        if (!sensorStarted) return
        controller.stop()
        sensorStarted = false
    }

    private fun calibrate(
        markInitialDone: Boolean,
        showAccessibilityToast: Boolean,
        hapticFeedback: Boolean,
        activateControl: Boolean
    ): Boolean {
        val info = displayInfo ?: return false
        if (!ControlAccessibilityService.isEnabled(this)) {
            if (showAccessibilityToast) {
                Toast.makeText(
                    this,
                    getString(R.string.touchpad_accessibility_required_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
        val service = ControlAccessibilityService.current()
        if (!controller.isAvailable || !controller.hasOrientationSample) return false
        controller.calibrate(info)
        if (markInitialDone) {
            initialCalibrationDone = true
        }
        service?.wakeCursor()
        if (activateControl) {
            setRayMouseActive(true)
        }
        if (hapticFeedback) {
            performCalibrationSuccessHaptic()
        }
        return true
    }

    private fun maybeAutoCalibrate(): Boolean {
        if (initialCalibrationDone) return true
        if (!controller.hasOrientationSample) return false
        if (!controller.isAvailable) return false
        if (displayInfo == null) return false
        if (!ControlAccessibilityService.isEnabled(this)) return false
        return calibrate(
            markInitialDone = true,
            showAccessibilityToast = false,
            hapticFeedback = false,
            activateControl = false
        )
    }

    private fun tryAutoCalibrate() {
        if (!sensorStarted) return
        if (maybeAutoCalibrate()) {
            cancelAutoCalibrate()
            updateState()
            return
        }
        if (initialCalibrationDone || autoCalibrationScheduled) return
        if (!controller.isAvailable || !controller.hasOrientationSample) return
        if (displayInfo == null) return
        if (!ControlAccessibilityService.isEnabled(this)) return
        if (autoCalibrationRetryCount >= AUTO_CALIBRATE_MAX_RETRIES) return
        autoCalibrationRetryCount += 1
        autoCalibrationScheduled = true
        handler.postDelayed(autoCalibrateRunnable, AUTO_CALIBRATE_RETRY_MS)
    }

    private fun cancelAutoCalibrate() {
        autoCalibrationScheduled = false
        handler.removeCallbacks(autoCalibrateRunnable)
    }

    private fun setupTuningControls() {
        setupSlider(
            binding.sliderRayHorizontalRange,
            valueFrom = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.start,
            valueTo = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.end,
            stepSize = SettingsSliderRanges.MOTION_HORIZONTAL_RANGE.step,
            SettingsStore.rayHorizontalRangeDeg,
            binding.rayHorizontalRangeValue,
            R.string.ray_mouse_tuning_degree_value
        ) {
            controller.updateTuning(horizontalRangeDeg = it)
            SettingsStore.setRayHorizontalRangeDeg(this, it)
        }
        setupSlider(
            binding.sliderRayVerticalRange,
            valueFrom = SettingsSliderRanges.MOTION_VERTICAL_RANGE.start,
            valueTo = SettingsSliderRanges.MOTION_VERTICAL_RANGE.end,
            stepSize = SettingsSliderRanges.MOTION_VERTICAL_RANGE.step,
            SettingsStore.rayVerticalRangeDeg,
            binding.rayVerticalRangeValue,
            R.string.ray_mouse_tuning_degree_value
        ) {
            controller.updateTuning(verticalRangeDeg = it)
            SettingsStore.setRayVerticalRangeDeg(this, it)
        }
        setupSlider(
            binding.sliderRaySmoothing,
            valueFrom = SettingsSliderRanges.MOTION_SMOOTHING.start,
            valueTo = SettingsSliderRanges.MOTION_SMOOTHING.end,
            stepSize = SettingsSliderRanges.MOTION_SMOOTHING.step,
            SettingsStore.raySmoothing,
            binding.raySmoothingValue,
            R.string.ray_mouse_tuning_decimal_value
        ) {
            controller.updateTuning(smoothing = it)
            SettingsStore.setRaySmoothing(this, it)
        }
        setupSlider(
            binding.sliderRayEmitInterval,
            valueFrom = SettingsSliderRanges.MOTION_EMIT_INTERVAL.start,
            valueTo = SettingsSliderRanges.MOTION_EMIT_INTERVAL.end,
            stepSize = SettingsSliderRanges.MOTION_EMIT_INTERVAL.step,
            SettingsStore.rayMinEmitIntervalMs.toFloat(),
            binding.rayEmitIntervalValue,
            R.string.ray_mouse_tuning_ms_value
        ) {
            controller.updateTuning(minEmitIntervalMs = it.toLong())
            SettingsStore.setRayMinEmitIntervalMs(this, it.toLong())
        }
        setupSlider(
            binding.sliderRayEmitDistance,
            valueFrom = SettingsSliderRanges.MOTION_EMIT_DISTANCE.start,
            valueTo = SettingsSliderRanges.MOTION_EMIT_DISTANCE.end,
            stepSize = SettingsSliderRanges.MOTION_EMIT_DISTANCE.step,
            SettingsStore.rayMinEmitDistancePx,
            binding.rayEmitDistanceValue,
            R.string.ray_mouse_tuning_px_value
        ) {
            controller.updateTuning(minEmitDistancePx = it)
            SettingsStore.setRayMinEmitDistancePx(this, it)
        }
    }

    private fun refreshTuningControls() {
        controller.updateTuning(
            horizontalRangeDeg = SettingsStore.rayHorizontalRangeDeg,
            verticalRangeDeg = SettingsStore.rayVerticalRangeDeg,
            smoothing = SettingsStore.raySmoothing,
            minEmitIntervalMs = SettingsStore.rayMinEmitIntervalMs,
            minEmitDistancePx = SettingsStore.rayMinEmitDistancePx
        )
        binding.sliderRayHorizontalRange.value = SettingsStore.rayHorizontalRangeDeg
        binding.sliderRayVerticalRange.value = SettingsStore.rayVerticalRangeDeg
        binding.sliderRaySmoothing.value = SettingsStore.raySmoothing
        binding.sliderRayEmitInterval.value = SettingsStore.rayMinEmitIntervalMs.toFloat()
        binding.sliderRayEmitDistance.value = SettingsStore.rayMinEmitDistancePx
        binding.switchRayHapticFeedback.isChecked = SettingsStore.rayHapticFeedbackEnabled
    }

    private fun showMoreMenu() {
        PopupMenu(this, binding.btnRayMore).apply {
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
                        if (ControlAccessibilityService.isEnabled(this@RayMouseActivity)) {
                            gestureController.finishActiveGesture()
                            setRayMouseActive(false)
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

    private fun setupHapticFeedbackSwitch() {
        binding.switchRayHapticFeedback.isChecked = SettingsStore.rayHapticFeedbackEnabled
        binding.switchRayHapticFeedback.setOnCheckedChangeListener { _, enabled ->
            SettingsStore.setRayHapticFeedbackEnabled(this, enabled)
            if (enabled) {
                performRayHaptic(HapticFeedbackConstants.CONFIRM)
            }
        }
    }

    private fun setupSlider(
        slider: Slider,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        defaultValue: Float,
        valueView: android.widget.TextView,
        valueFormatRes: Int,
        onValueChanged: (Float) -> Unit
    ) {
        slider.valueFrom = valueFrom
        slider.valueTo = valueTo
        slider.stepSize = stepSize
        slider.value = defaultValue.coerceIn(slider.valueFrom, slider.valueTo)
        valueView.text = getString(valueFormatRes, slider.value)
        onValueChanged(slider.value)
        slider.addOnChangeListener { _, value, fromUser ->
            valueView.text = getString(valueFormatRes, value)
            if (fromUser) {
                onValueChanged(value)
            }
        }
    }

    private fun performRayHaptic(feedbackConstant: Int) {
        if (!SettingsStore.rayHapticFeedbackEnabled) return
        binding.root.performHapticFeedback(feedbackConstant)
    }

    private fun performCalibrationSuccessHaptic() {
        if (!SettingsStore.rayHapticFeedbackEnabled) return
        val vibrator = getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    CALIBRATION_SUCCESS_VIBRATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun performVolumeKeyCalibration(): ControlSurfaceVolumeKeyController.CalibrationResult {
        val calibrated = calibrate(
            markInitialDone = true,
            showAccessibilityToast = false,
            hapticFeedback = false,
            activateControl = true
        )
        if (calibrated) {
            ControlAccessibilityService.current()?.centerCursorOnExternalDisplay()
            performCalibrationSuccessHaptic()
        } else {
            performRayHaptic(HapticFeedbackConstants.REJECT)
            Toast.makeText(
                this,
                R.string.ray_mouse_quick_calibrate_unavailable,
                Toast.LENGTH_SHORT
            ).show()
        }
        return ControlSurfaceVolumeKeyController.CalibrationResult(
            success = calibrated,
            failureReason = if (calibrated) null else quickCalibrationFailureReason()
        )
    }

    private fun toggleBlackoutFromVolumeKey(wasBlackoutVisible: Boolean) {
        gestureController.finishActiveGesture()
        if (wasBlackoutVisible) {
            blackoutController.hide("volume_up_hold")
            binding.rayMouseArea.requestFocusFromTouch()
            setRayMouseActive(true)
            recalibrateAfterVolumeUnlock()
            backController.warmUpOnActivation("volume_up_unlock")
        } else {
            setRayMouseActive(false)
            blackoutController.show()
        }
        performRayHaptic(HapticFeedbackConstants.CONFIRM)
    }

    private fun recalibrateAfterVolumeUnlock() {
        val recalibrated = calibrate(
            markInitialDone = true,
            showAccessibilityToast = false,
            hapticFeedback = false,
            activateControl = true
        )
        DiagnosticsLog.add(
            "MotionMouse: volume unlock recalibrated=$recalibrated " +
                "failure=${if (recalibrated) "none" else quickCalibrationFailureReason()}"
        )
        if (recalibrated) return

        controller.resetCalibration()
        initialCalibrationDone = false
        autoCalibrationRetryCount = 0
        tryAutoCalibrate()
    }

    private fun quickCalibrationFailureReason(): String {
        return when {
            displayInfo == null -> "external_display_missing"
            !ControlAccessibilityService.isEnabled(this) -> "accessibility_disabled"
            !controller.isAvailable -> "sensor_unavailable"
            !controller.hasOrientationSample -> "orientation_sample_missing"
            else -> "unknown"
        }
    }

    private fun showExternalControlTutorial() {
        if (ControlAccessibilityService.requestControlTutorial(ControlSurfaceMode.RAY_MOUSE)) {
            binding.root.announceForAccessibility(
                getString(R.string.external_tutorial_calibrate)
            )
        }
    }

    private fun setRayMouseActive(active: Boolean) {
        val blackoutVisible =
            ::blackoutController.isInitialized && blackoutController.isVisible
        val resolvedActive = active && isControlSurfaceAvailable() && !blackoutVisible
        val wasActive = rayMouseActive
        rayMouseActive = resolvedActive
        binding.rayMouseArea.isActivated = resolvedActive
        binding.rayMouseHint.setTextColor(
            ContextCompat.getColor(
                this,
                if (resolvedActive) {
                    R.color.touchpadHintActive
                } else {
                    R.color.touchpadHintInactive
                }
            )
        )
        if (wasActive != resolvedActive) {
            DiagnosticsLog.add("MotionMouse: active=$resolvedActive")
        }
        updateWindowPolicyActivity()
    }

    private fun isControlSurfaceAvailable(): Boolean {
        return displayInfo != null &&
            ControlAccessibilityService.isEnabled(this)
    }

    private fun updateWindowPolicyActivity() {
        if (!::windowPolicy.isInitialized) return
        windowPolicy.setInteractionActive(rayMouseActive && isControlSurfaceAvailable())
    }

    private fun rebaseToCurrentCursor(service: ControlAccessibilityService) {
        val info = displayInfo ?: return
        if (!controller.hasOrientationSample) return
        controller.rebaseToPoint(info, service.getCursorPosition())
        initialCalibrationDone = true
    }

    private fun updateState() {
        val canControl = isControlSurfaceAvailable()

        if (!canControl) {
            setRayMouseActive(false)
        } else {
            updateWindowPolicyActivity()
        }

        binding.rayMouseArea.isEnabled = canControl
    }

    private fun showRayMouseIntroIfNeeded() {
        if (SettingsStore.rayMouseIntroShown) return
        val message = getString(
            R.string.touchpad_intro_message,
            getString(R.string.ray_mouse_intro_aim),
            getString(R.string.ray_mouse_intro_touch),
            getString(R.string.ray_mouse_intro_calibrate),
            getString(R.string.ray_mouse_intro_dim),
            getString(R.string.ray_mouse_intro_back),
            getString(R.string.ray_mouse_intro_exit)
        )
        introDialogVisible = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.ray_mouse_intro_title)
            .setMessage(message)
            .setPositiveButton(R.string.touchpad_intro_got_it) { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener {
                introDialogVisible = false
                onboardingController.onStateChanged()
            }
            .show()
        SettingsStore.setRayMouseIntroShown(this)
    }

    companion object {
        private const val AUTO_CALIBRATE_RETRY_MS = 200L
        private const val AUTO_CALIBRATE_MAX_RETRIES = 25
        private const val CALIBRATION_SUCCESS_VIBRATION_MS = 35L
    }
}
