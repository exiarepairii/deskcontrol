package com.deskcontrol

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import com.deskcontrol.databinding.ActivityMotionPointerBinding
import kotlin.math.abs

class MotionPointerActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMotionPointerBinding
    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private var usingGameRotation = false
    private var sensorRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressTimeout = 0
    private var dragging = false
    private var touchDown = false
    private var filteredHorizontal = 0f
    private var filteredVertical = 0f
    private var lastGyroTimestampNs = 0L
    private var lastGyroDispatchNs = 0L
    private var lastRotationDispatchNs = 0L
    private var motionMode = MotionMode.SCREEN
    private var calibrationRefMatrix: FloatArray? = null
    private var lastRotationMatrix: FloatArray? = null
    private var calibrationYawTopLeft = 0f
    private var calibrationPitchTopLeft = 0f
    private var calibrationYawBottomRight = 0f
    private var calibrationPitchBottomRight = 0f
    private var calibrationYawOffset = 0f
    private var calibrationPitchOffset = 0f
    private var calibrationValid = false
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var uFiltered = 0f
    private var vFiltered = 0f
    private var hasFilteredUv = false
    private var targetU = 0f
    private var targetV = 0f
    private var hasTargetUv = false
    private var motionTickerRunning = false
    private var snapUntilMs = 0L
    private var touchState = TouchState.IDLE
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchSlopPx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMotionPointerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DiagnosticsLog.add("MotionPointer: create displayId=${display?.displayId ?: -1}")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (rotationSensor != null) {
            usingGameRotation = true
        } else {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            usingGameRotation = false
        }
        longPressTimeout = ViewConfiguration.getLongPressTimeout()
        touchSlopPx = resources.displayMetrics.density * TOUCH_SLOP_DP
        motionMode = MotionMode.SCREEN
        loadCalibrationFromStore()
        updateMotionHint()
        setCalibrationExpanded(false)
        setupDebugControls()

        binding.motionBack.setOnClickListener {
            DiagnosticsLog.add("MotionPointer: exit via toolbar")
            finish()
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.motionRecenter.setOnClickListener {
            quickCalibrate()
            binding.motionRecenter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            DiagnosticsLog.add("MotionPointer: quick calibrate")
        }
        binding.motionInputArea.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
        binding.btnCalibrationReset.setOnClickListener {
            SettingsStore.clearMotionCalibration(this)
            loadCalibrationFromStore()
            resetCalibrationFlow()
            updateMotionHint()
            setCalibrationExpanded(false)
            Toast.makeText(
                this,
                getString(R.string.motion_calibration_reset_done),
                Toast.LENGTH_SHORT
            ).show()
            DiagnosticsLog.add("MotionPointer: calibration reset")
        }
        binding.calibrationToggle.setOnClickListener {
            setCalibrationExpanded(!binding.calibrationBody.isVisible)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
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
        updateAccessibilityGate()
        loadCalibrationFromStore()
        updateMotionHint()
        resetCalibrationFlow()
        setCalibrationExpanded(false)
        startSensorsIfReady()
        DiagnosticsLog.add("MotionPointer: resume")
    }

    override fun onPause() {
        stopSensors()
        stopMotionTicker()
        cancelLongPress()
        if (dragging) {
            ControlAccessibilityService.current()?.endDragAtCursor()
            dragging = false
        }
        if (touchState == TouchState.SCROLLING) {
            ControlAccessibilityService.current()?.cancelScrollGesture()
            touchState = TouchState.IDLE
        }
        updateKeepScreenOn(false)
        resetMotionState()
        DiagnosticsLog.add("MotionPointer: pause")
        super.onPause()
    }

    override fun onDestroy() {
        stopSensors()
        stopMotionTicker()
        cancelLongPress()
        if (dragging) {
            ControlAccessibilityService.current()?.endDragAtCursor()
            dragging = false
        }
        if (touchState == TouchState.SCROLLING) {
            ControlAccessibilityService.current()?.cancelScrollGesture()
            touchState = TouchState.IDLE
        }
        resetMotionState()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!sensorRegistered) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (motionMode != MotionMode.RELATIVE) return
                handleGyroEvent(event)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                val currentMatrix = rotationMatrixFromVector(event.values)
                lastRotationMatrix = currentMatrix
                if (motionMode != MotionMode.SCREEN) return
                handleRotationMatrix(currentMatrix, event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleTouch(event: MotionEvent) {
        val service = serviceOrToast() ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDown = true
                touchState = TouchState.TOUCHING
                downTouchX = event.x
                downTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                scheduleLongPress(service)
                service.wakeCursor()
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchState == TouchState.DRAGGING) return
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (touchState != TouchState.SCROLLING) {
                    val moved = abs(event.x - downTouchX) > touchSlopPx ||
                        abs(event.y - downTouchY) > touchSlopPx
                    if (moved) {
                        cancelLongPress()
                        touchState = TouchState.SCROLLING
                        service.startScrollGestureAtCursor()
                    }
                }
                if (touchState == TouchState.SCROLLING) {
                    service.updateScrollGestureBy(dx, dy)
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                touchDown = false
                cancelLongPress()
                if (touchState == TouchState.SCROLLING) {
                    service.endScrollGesture()
                    touchState = TouchState.IDLE
                    return
                }
                if (dragging) {
                    service.endDragAtCursor()
                    dragging = false
                    touchState = TouchState.IDLE
                } else {
                    touchState = TouchState.IDLE
                    snapUntilMs = SystemClock.uptimeMillis() + MOTION_SNAP_DURATION_MS
                    service.tapAtCursor()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touchDown = false
                cancelLongPress()
                if (touchState == TouchState.SCROLLING) {
                    service.cancelScrollGesture()
                }
                if (dragging) {
                    service.cancelDrag()
                    dragging = false
                }
                touchState = TouchState.IDLE
            }
        }
    }

    private fun scheduleLongPress(service: ControlAccessibilityService) {
        cancelLongPress()
        longPressRunnable = Runnable {
            if (dragging) return@Runnable
            dragging = true
            touchState = TouchState.DRAGGING
            binding.motionInputArea.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            service.startDragAtCursor()
        }
        handler.postDelayed(longPressRunnable!!, longPressTimeout.toLong())
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun handleGyroEvent(event: SensorEvent) {
        val timestampNs = event.timestamp
        if (lastGyroTimestampNs == 0L) {
            lastGyroTimestampNs = timestampNs
            lastGyroDispatchNs = timestampNs
            return
        }

        val (rawHorizontal, rawVertical) = mapGyroToPointer(event.values)
        val deadzone = SettingsStore.motionDeadzone
            .coerceIn(MOTION_DEADZONE_MIN, MOTION_DEADZONE_MAX)
        val alpha = SettingsStore.motionSmoothingAlpha
            .coerceIn(MOTION_SMOOTHING_MIN, MOTION_SMOOTHING_MAX)

        val clampedHorizontal = if (abs(rawHorizontal) < deadzone) 0f else rawHorizontal
        val clampedVertical = if (abs(rawVertical) < deadzone) 0f else rawVertical

        filteredHorizontal = alpha * clampedHorizontal + (1f - alpha) * filteredHorizontal
        filteredVertical = alpha * clampedVertical + (1f - alpha) * filteredVertical

        val elapsedSinceDispatch = timestampNs - lastGyroDispatchNs
        if (elapsedSinceDispatch < FRAME_INTERVAL_NS) return
        lastGyroDispatchNs = timestampNs

        if (touchDown && !dragging) return

        val dtSeconds = elapsedSinceDispatch / 1_000_000_000f
        val sensitivity = SettingsStore.motionSensitivity
            .coerceIn(MOTION_SENSITIVITY_MIN, MOTION_SENSITIVITY_MAX)
        val dx = filteredHorizontal * sensitivity * baseScalePxPerRad() * dtSeconds
        val dy = filteredVertical * sensitivity * baseScalePxPerRad() * dtSeconds
        if (dx == 0f && dy == 0f) return

        val service = ControlAccessibilityService.current() ?: return
        service.moveCursorBy(dx, dy)
        if (dragging) {
            service.updateDragToCursor()
        }
    }

    private fun handleRotationMatrix(currentMatrix: FloatArray, timestampNs: Long) {
        if (!calibrationValid) return
        val refMatrix = calibrationRefMatrix ?: return
        if (lastRotationDispatchNs == 0L) {
            lastRotationDispatchNs = timestampNs
        }
        if (timestampNs - lastRotationDispatchNs < FRAME_INTERVAL_NS) return
        lastRotationDispatchNs = timestampNs

        if (touchDown && !dragging) return

        val relative = multiplyMatrices(transposeMatrix(refMatrix), currentMatrix)
        val (yaw, pitch) = yawPitchFromMatrix(relative)
        val adjustedYaw = yaw + calibrationYawOffset
        val adjustedPitch = pitch + calibrationPitchOffset

        val deadzone = SettingsStore.motionDeadzone
            .coerceIn(MOTION_DEADZONE_MIN, MOTION_DEADZONE_MAX)
        if (abs(adjustedYaw - lastYaw) < deadzone &&
            abs(adjustedPitch - lastPitch) < deadzone
        ) {
            return
        }
        lastYaw = adjustedYaw
        lastPitch = adjustedPitch

        val yawMin = minOf(calibrationYawTopLeft, calibrationYawBottomRight)
        val yawMax = maxOf(calibrationYawTopLeft, calibrationYawBottomRight)
        val pitchMin = minOf(calibrationPitchTopLeft, calibrationPitchBottomRight)
        val pitchMax = maxOf(calibrationPitchTopLeft, calibrationPitchBottomRight)
        val yawRange = yawMax - yawMin
        val pitchRange = pitchMax - pitchMin
        if (abs(yawRange) < MOTION_MIN_RANGE_RAD || abs(pitchRange) < MOTION_MIN_RANGE_RAD) {
            return
        }

        var u = (adjustedYaw - yawMin) / yawRange
        var v = (adjustedPitch - pitchMin) / pitchRange
        val invertYaw = calibrationYawTopLeft > calibrationYawBottomRight
        val invertPitch = calibrationPitchTopLeft > calibrationPitchBottomRight
        if (invertYaw) {
            u = 1f - u
        }
        if (invertPitch) {
            v = 1f - v
        }
        u = u.coerceIn(0f, 1f)
        v = v.coerceIn(0f, 1f)
        targetU = u
        targetV = v
        hasTargetUv = true
        if (!motionTickerRunning) {
            startMotionTicker()
        }
    }

    private fun startMotionTicker() {
        if (motionTickerRunning) return
        motionTickerRunning = true
        handler.post(motionTicker)
    }

    private fun stopMotionTicker() {
        if (!motionTickerRunning) return
        handler.removeCallbacks(motionTicker)
        motionTickerRunning = false
    }

    private val motionTicker = object : Runnable {
        override fun run() {
            if (!motionTickerRunning) return
            if (motionMode != MotionMode.SCREEN || !calibrationValid) {
                motionTickerRunning = false
                return
            }
            if (!hasTargetUv) {
                handler.postDelayed(this, MOTION_TICK_MS)
                return
            }
            if (touchDown && !dragging) {
                handler.postDelayed(this, MOTION_TICK_MS)
                return
            }
        val nowMs = SystemClock.uptimeMillis()
        var alpha = SettingsStore.motionSmoothingAlpha
            .coerceIn(MOTION_UV_SMOOTHING_MIN, MOTION_UV_SMOOTHING_MAX)
        if (nowMs < snapUntilMs) {
            alpha = MOTION_SNAP_ALPHA
        }
        if (touchState == TouchState.SCROLLING || touchState == TouchState.DRAGGING) {
            alpha = 1f
        }
            if (!hasFilteredUv) {
                uFiltered = targetU
                vFiltered = targetV
                hasFilteredUv = true
            } else {
                uFiltered += (targetU - uFiltered) * alpha
                vFiltered += (targetV - vFiltered) * alpha
            }
            val info = DisplaySessionManager.getExternalDisplayInfo()
            val service = ControlAccessibilityService.current()
            if (info != null && service != null) {
                val targetX = uFiltered * info.width
                val targetY = vFiltered * info.height
                val current = service.getCursorPosition()
                val dx = targetX - current.x
                val dy = targetY - current.y
                if (abs(dx) >= MOTION_POSITION_EPS || abs(dy) >= MOTION_POSITION_EPS) {
                    service.moveCursorBy(dx, dy)
                    if (dragging) {
                        service.updateDragToCursor()
                    }
                }
            }
            handler.postDelayed(this, MOTION_TICK_MS)
        }
    }

    private fun mapGyroToPointer(values: FloatArray): Pair<Float, Float> {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        return when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> Pair(-z, -y)
            Surface.ROTATION_180 -> Pair(y, x)
            Surface.ROTATION_270 -> Pair(z, y)
            else -> Pair(-y, -x)
        }
    }

    private fun rotationMatrixFromVector(values: FloatArray): FloatArray {
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val remapped = FloatArray(9)
        when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotation,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remapped
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotation,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                remapped
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotation,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                remapped
            )
            else -> rotation.copyInto(remapped)
        }
        return remapped
    }

    private fun yawPitchFromMatrix(matrix: FloatArray): Pair<Float, Float> {
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        return Pair(orientation[0], orientation[1])
    }

    private fun transposeMatrix(matrix: FloatArray): FloatArray {
        return floatArrayOf(
            matrix[0], matrix[3], matrix[6],
            matrix[1], matrix[4], matrix[7],
            matrix[2], matrix[5], matrix[8]
        )
    }

    private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        out[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6]
        out[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7]
        out[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8]
        out[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6]
        out[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7]
        out[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8]
        out[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6]
        out[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7]
        out[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
        return out
    }

    private fun loadCalibrationFromStore() {
        calibrationValid = SettingsStore.motionCalibrationValid
        calibrationYawTopLeft = SettingsStore.motionCalibrationYawTopLeft
        calibrationPitchTopLeft = SettingsStore.motionCalibrationPitchTopLeft
        calibrationYawBottomRight = SettingsStore.motionCalibrationYawBottomRight
        calibrationPitchBottomRight = SettingsStore.motionCalibrationPitchBottomRight
        calibrationYawOffset = SettingsStore.motionCalibrationYawOffset
        calibrationPitchOffset = SettingsStore.motionCalibrationPitchOffset
        calibrationRefMatrix = SettingsStore.motionCalibrationReference
        if (calibrationRefMatrix == null) {
            calibrationValid = false
        }
    }

    private fun updateMotionHint() {
        val hintRes = if (calibrationValid) {
            R.string.motion_pointer_hint
        } else {
            R.string.motion_pointer_hint_calibrate
        }
        binding.motionHint.text = getString(hintRes)
    }

    private fun setupDebugControls() {
        configureSlider(
            binding.labelQuickRange,
            binding.sliderQuickRange,
            min = 6f,
            max = 24f,
            current = SettingsStore.motionQuickRangeDeg,
            format = { getString(R.string.motion_calibration_quick_range, it) }
        ) { value ->
            SettingsStore.setMotionQuickRangeDeg(this, value)
        }

        configureSlider(
            binding.labelMotionSmoothing,
            binding.sliderMotionSmoothing,
            min = 0.05f,
            max = 0.6f,
            current = SettingsStore.motionSmoothingAlpha,
            format = { getString(R.string.motion_calibration_smoothing, it) }
        ) { value ->
            SettingsStore.setMotionSmoothingAlpha(this, value)
        }

        configureSlider(
            binding.labelMotionDeadzone,
            binding.sliderMotionDeadzone,
            min = 0.01f,
            max = 0.08f,
            current = SettingsStore.motionDeadzone,
            format = { getString(R.string.motion_calibration_deadzone, it) }
        ) { value ->
            SettingsStore.setMotionDeadzone(this, value)
        }
    }

    private fun configureSlider(
        labelView: android.widget.TextView,
        slider: android.widget.SeekBar,
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
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val value = min + (max - min) * (progress / slider.max.toFloat())
                labelView.text = format(value)
                onChange(value)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
    }

    private fun setCalibrationExpanded(expanded: Boolean) {
        binding.calibrationBody.isVisible = expanded
        binding.calibrationToggle.text = getString(
            if (expanded) R.string.motion_calibration_collapse else R.string.motion_calibration_expand
        )
    }

    private fun resetCalibrationFlow() {
        binding.calibrationStatus.text = getString(R.string.empty_text)
    }

    private fun quickCalibrate() {
        if (rotationSensor == null) {
            Toast.makeText(
                this,
                getString(R.string.motion_calibration_no_rotation),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val currentMatrix = lastRotationMatrix
        if (currentMatrix == null) {
            Toast.makeText(
                this,
                getString(R.string.motion_calibration_wait_for_motion),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val sensitivity = SettingsStore.motionSensitivity
            .coerceIn(MOTION_SENSITIVITY_MIN, MOTION_SENSITIVITY_MAX)
        val rangeDeg = SettingsStore.motionQuickRangeDeg
        val baseRange = Math.toRadians(rangeDeg.toDouble()).toFloat()
        val range = (baseRange / sensitivity).coerceAtLeast(MOTION_MIN_RANGE_RAD)
        val ref = currentMatrix.copyOf()
        val success = completeCalibration(
            ref,
            yawTopLeft = -range,
            pitchTopLeft = -range,
            yawBottomRight = range,
            pitchBottomRight = range
        )
        if (success) {
            binding.calibrationStatus.text = getString(R.string.motion_calibration_captured)
            applyCalibrationCenter()
        }
        updateMotionHint()
        startSensorsIfReady()
    }

    private fun completeCalibration(
        refMatrix: FloatArray,
        yawTopLeft: Float,
        pitchTopLeft: Float,
        yawBottomRight: Float,
        pitchBottomRight: Float
    ): Boolean {
        val yawRange = abs(yawBottomRight - yawTopLeft)
        val pitchRange = abs(pitchBottomRight - pitchTopLeft)
        if (yawRange < MOTION_MIN_RANGE_RAD || pitchRange < MOTION_MIN_RANGE_RAD) {
            DiagnosticsLog.add(
                "MotionPointer: calibration range too small yaw=${Math.toDegrees(yawRange.toDouble())} " +
                    "pitch=${Math.toDegrees(pitchRange.toDouble())}"
            )
            Toast.makeText(
                this,
                getString(R.string.motion_calibration_too_small),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        SettingsStore.setMotionCalibration(
            this,
            refMatrix,
            yawTopLeft,
            pitchTopLeft,
            yawBottomRight,
            pitchBottomRight
        )
        calibrationValid = true
        calibrationYawOffset = 0f
        calibrationPitchOffset = 0f
        loadCalibrationFromStore()
        resetMotionFilters()
        val yawMin = Math.toDegrees(minOf(yawTopLeft, yawBottomRight).toDouble())
        val yawMax = Math.toDegrees(maxOf(yawTopLeft, yawBottomRight).toDouble())
        val pitchMin = Math.toDegrees(minOf(pitchTopLeft, pitchBottomRight).toDouble())
        val pitchMax = Math.toDegrees(maxOf(pitchTopLeft, pitchBottomRight).toDouble())
        DiagnosticsLog.add(
            "MotionPointer: calibration done yawMin=$yawMin yawMax=$yawMax " +
                "pitchMin=$pitchMin pitchMax=$pitchMax game=$usingGameRotation"
        )
        setCalibrationExpanded(false)
        return true
    }

    private fun recenterToCursor() {
        if (!calibrationValid) return
        val ref = calibrationRefMatrix ?: return
        val currentMatrix = lastRotationMatrix ?: return
        val info = DisplaySessionManager.getExternalDisplayInfo() ?: return
        val service = ControlAccessibilityService.current() ?: return
        val cursor = service.getCursorPosition()
        val u = (cursor.x / info.width).coerceIn(0f, 1f)
        val v = (cursor.y / info.height).coerceIn(0f, 1f)
        val yawMin = minOf(calibrationYawTopLeft, calibrationYawBottomRight)
        val yawMax = maxOf(calibrationYawTopLeft, calibrationYawBottomRight)
        val pitchMin = minOf(calibrationPitchTopLeft, calibrationPitchBottomRight)
        val pitchMax = maxOf(calibrationPitchTopLeft, calibrationPitchBottomRight)
        val invertYaw = calibrationYawTopLeft > calibrationYawBottomRight
        val invertPitch = calibrationPitchTopLeft > calibrationPitchBottomRight
        val mappedU = if (invertYaw) 1f - u else u
        val mappedV = if (invertPitch) 1f - v else v
        val targetYaw = yawMin + mappedU * (yawMax - yawMin)
        val targetPitch = pitchMin + mappedV * (pitchMax - pitchMin)
        val relative = multiplyMatrices(transposeMatrix(ref), currentMatrix)
        val (yaw, pitch) = yawPitchFromMatrix(relative)
        calibrationYawOffset = targetYaw - yaw
        calibrationPitchOffset = targetPitch - pitch
        SettingsStore.setMotionCalibrationOffsets(
            this,
            calibrationYawOffset,
            calibrationPitchOffset
        )
    }

    private fun centerCursorOnDisplay() {
        val info = DisplaySessionManager.getExternalDisplayInfo() ?: return
        val service = ControlAccessibilityService.current() ?: return
        val current = service.getCursorPosition()
        val targetX = info.width / 2f
        val targetY = info.height / 2f
        service.moveCursorBy(targetX - current.x, targetY - current.y)
    }

    private fun applyCalibrationCenter() {
        resetMotionFilters()
        centerCursorOnDisplay()
        if (calibrationValid) {
            val ref = calibrationRefMatrix ?: return
            val currentMatrix = lastRotationMatrix ?: return
            val relative = multiplyMatrices(transposeMatrix(ref), currentMatrix)
            val (yaw, pitch) = yawPitchFromMatrix(relative)
            val yawMin = minOf(calibrationYawTopLeft, calibrationYawBottomRight)
            val yawMax = maxOf(calibrationYawTopLeft, calibrationYawBottomRight)
            val pitchMin = minOf(calibrationPitchTopLeft, calibrationPitchBottomRight)
            val pitchMax = maxOf(calibrationPitchTopLeft, calibrationPitchBottomRight)
            val targetYaw = (yawMin + yawMax) / 2f
            val targetPitch = (pitchMin + pitchMax) / 2f
            calibrationYawOffset = targetYaw - yaw
            calibrationPitchOffset = targetPitch - pitch
            SettingsStore.setMotionCalibrationOffsets(
                this,
                calibrationYawOffset,
                calibrationPitchOffset
            )
        }
    }

    private fun resetMotionFilters() {
        stopMotionTicker()
        hasFilteredUv = true
        hasTargetUv = true
        uFiltered = 0.5f
        vFiltered = 0.5f
        targetU = 0.5f
        targetV = 0.5f
        lastYaw = 0f
        lastPitch = 0f
    }

    private fun resetMotionState() {
        filteredHorizontal = 0f
        filteredVertical = 0f
        touchDown = false
        touchState = TouchState.IDLE
        lastGyroTimestampNs = 0L
        lastGyroDispatchNs = 0L
        lastRotationDispatchNs = 0L
        lastYaw = 0f
        lastPitch = 0f
        hasFilteredUv = false
        hasTargetUv = false
        uFiltered = 0f
        vFiltered = 0f
    }

    private fun startSensorsIfReady() {
        val enabled = ControlAccessibilityService.isEnabled(this)
        if (!enabled) {
            stopSensors()
            return
        }
        stopSensors()
        stopMotionTicker()
        resetMotionState()
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorRegistered = true
            DiagnosticsLog.add(
                "MotionPointer: rotation sensor registered game=$usingGameRotation"
            )
        }
        motionMode = if (calibrationValid && rotationSensor != null) {
            MotionMode.SCREEN
        } else {
            MotionMode.RELATIVE
        }
        if (motionMode == MotionMode.RELATIVE && rotationSensor == null && calibrationValid) {
            Toast.makeText(
                this,
                getString(R.string.motion_calibration_no_rotation),
                Toast.LENGTH_SHORT
            ).show()
        }
        if (motionMode == MotionMode.RELATIVE) {
            if (gyroSensor == null) {
                DiagnosticsLog.add("MotionPointer: gyro unavailable")
                Toast.makeText(this, getString(R.string.motion_pointer_no_gyro), Toast.LENGTH_SHORT)
                    .show()
                finish()
                return
            }
            sensorManager.registerListener(
                this,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            sensorRegistered = true
            DiagnosticsLog.add("MotionPointer: gyro registered")
        }
    }

    private fun stopSensors() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(this)
        sensorRegistered = false
        DiagnosticsLog.add("MotionPointer: sensors unregistered")
    }

    private fun updateAccessibilityGate() {
        val enabled = ControlAccessibilityService.isEnabled(this)
        binding.accessibilityGate.isVisible = !enabled
        binding.motionContent.alpha = if (enabled) 1f else 0.35f
        binding.motionInputArea.isEnabled = enabled
        binding.motionRecenter.isEnabled = enabled
        binding.calibrationCard.isEnabled = enabled
        binding.calibrationToggle.isEnabled = enabled
        binding.btnCalibrationReset.isEnabled = enabled
        binding.sliderQuickRange.isEnabled = enabled
        binding.sliderMotionSmoothing.isEnabled = enabled
        binding.sliderMotionDeadzone.isEnabled = enabled
    }

    private fun updateKeepScreenOn(visible: Boolean) {
        if (visible && SettingsStore.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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

    private fun baseScalePxPerRad(): Float {
        return resources.displayMetrics.density * MOTION_BASE_SCALE_DP
    }

    companion object {
        private const val FRAME_INTERVAL_NS = 16_000_000L
        private const val MOTION_TICK_MS = 16L
        private const val MOTION_BASE_SCALE_DP = 420f
        private const val MOTION_SENSITIVITY_MIN = 0.6f
        private const val MOTION_SENSITIVITY_MAX = 2.0f
        private const val MOTION_SMOOTHING_MIN = 0.05f
        private const val MOTION_SMOOTHING_MAX = 0.6f
        private const val MOTION_DEADZONE_MIN = 0.01f
        private const val MOTION_DEADZONE_MAX = 0.08f
        private const val MOTION_MIN_RANGE_DEG = 3f
        private const val MOTION_UV_SMOOTHING_MIN = 0.05f
        private const val MOTION_UV_SMOOTHING_MAX = 0.6f
        private const val MOTION_SNAP_ALPHA = 0.6f
        private const val MOTION_SNAP_DURATION_MS = 200L
        private const val MOTION_POSITION_EPS = 0.5f
        private const val TOUCH_SLOP_DP = 6f
        private val MOTION_MIN_RANGE_RAD =
            Math.toRadians(MOTION_MIN_RANGE_DEG.toDouble()).toFloat()
    }

    private enum class MotionMode {
        SCREEN,
        RELATIVE
    }

    private enum class TouchState {
        IDLE,
        TOUCHING,
        SCROLLING,
        DRAGGING
    }
}
