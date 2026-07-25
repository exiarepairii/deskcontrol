package com.deskcontrol

import android.content.Context
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import kotlin.math.PI
import kotlin.math.hypot

class RayMouseController(
    context: Context,
    private val onPointChanged: (PointF) -> Unit,
    private val onOrientationReady: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val deviceDisplay = context.display
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val currentRotationMatrix = FloatArray(9)
    private val baselineRotationMatrix = FloatArray(9)
    private val localAngleChange = FloatArray(3)
    private var displayInfo: DisplaySessionManager.ExternalDisplayInfo? = null
    private var hasCurrentRotationMatrix = false
    private var horizontalAnchor = 0f
    private var verticalAnchor = 0f
    private var smoothedX = Float.NaN
    private var smoothedY = Float.NaN
    private var lastEmitX = Float.NaN
    private var lastEmitY = Float.NaN
    private var lastEmitMs = 0L
    private var horizontalRangeDeg = DEFAULT_HORIZONTAL_RANGE_DEG
    private var verticalRangeDeg = DEFAULT_VERTICAL_RANGE_DEG
    private var smoothing = DEFAULT_SMOOTHING
    private var minEmitIntervalMs = DEFAULT_MIN_EMIT_INTERVAL_MS
    private var minEmitDistancePx = DEFAULT_MIN_EMIT_DISTANCE_PX

    val isAvailable: Boolean
        get() = sensor != null

    var isCalibrated: Boolean = false
        private set
    var hasOrientationSample: Boolean = false
        private set

    fun start() {
        val activeSensor = sensor ?: return
        hasOrientationSample = false
        hasCurrentRotationMatrix = false
        sensorManager.registerListener(
            this,
            activeSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun calibrate(info: DisplaySessionManager.ExternalDisplayInfo) {
        if (!hasCurrentRotationMatrix) return
        displayInfo = info
        currentRotationMatrix.copyInto(baselineRotationMatrix)
        horizontalAnchor = 0f
        verticalAnchor = 0f
        smoothedX = info.width / 2f
        smoothedY = info.height / 2f
        isCalibrated = true
        onPointChanged(PointF(smoothedX, smoothedY))
    }

    fun rebaseToPoint(
        info: DisplaySessionManager.ExternalDisplayInfo,
        point: PointF
    ) {
        if (!hasCurrentRotationMatrix) return
        val x = point.x.coerceIn(0f, info.width.toFloat())
        val y = point.y.coerceIn(0f, info.height.toFloat())
        if (!x.isFinite() || !y.isFinite()) return
        displayInfo = info
        currentRotationMatrix.copyInto(baselineRotationMatrix)
        horizontalAnchor = ((x - info.width / 2f) / (info.width / 2f)).coerceIn(-1f, 1f)
        verticalAnchor = ((y - info.height / 2f) / (info.height / 2f)).coerceIn(-1f, 1f)
        smoothedX = x
        smoothedY = y
        lastEmitX = x
        lastEmitY = y
        lastEmitMs = SystemClock.uptimeMillis()
        isCalibrated = true
    }

    fun resetCalibration() {
        displayInfo = null
        smoothedX = Float.NaN
        smoothedY = Float.NaN
        lastEmitX = Float.NaN
        lastEmitY = Float.NaN
        lastEmitMs = 0L
        horizontalAnchor = 0f
        verticalAnchor = 0f
        isCalibrated = false
    }

    fun updateTuning(
        horizontalRangeDeg: Float = this.horizontalRangeDeg,
        verticalRangeDeg: Float = this.verticalRangeDeg,
        smoothing: Float = this.smoothing,
        minEmitIntervalMs: Long = this.minEmitIntervalMs,
        minEmitDistancePx: Float = this.minEmitDistancePx
    ) {
        this.horizontalRangeDeg = horizontalRangeDeg.coerceIn(8f, 70f)
        this.verticalRangeDeg = verticalRangeDeg.coerceIn(8f, 70f)
        this.smoothing = smoothing.coerceIn(0.05f, 0.85f)
        this.minEmitIntervalMs = minEmitIntervalMs.coerceIn(0L, 64L)
        this.minEmitDistancePx = minEmitDistancePx.coerceIn(0f, 12f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val matrixForOrientation = when (deviceDisplay.rotation) {
            Surface.ROTATION_90 -> remapRotationMatrix(
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X
            )
            Surface.ROTATION_180 -> remapRotationMatrix(
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y
            )
            Surface.ROTATION_270 -> remapRotationMatrix(
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X
            )
            else -> rotationMatrix
        }
        matrixForOrientation.copyInto(currentRotationMatrix)
        if (currentRotationMatrix.any { !it.isFinite() }) return
        hasCurrentRotationMatrix = true
        if (!hasOrientationSample) {
            hasOrientationSample = true
            onOrientationReady()
        }
        val info = displayInfo ?: return
        if (!isCalibrated) return

        SensorManager.getAngleChange(
            localAngleChange,
            currentRotationMatrix,
            baselineRotationMatrix
        )
        // The calibrated phone screen is the local XY plane. Rotation around its
        // normal (local Z) controls horizontal movement; rotation around local X
        // controls vertical movement. Absolute world yaw/pitch is intentionally ignored.
        val localZDelta = localAngleChange[0]
        val localXDelta = localAngleChange[1]
        if (!localZDelta.isFinite() || !localXDelta.isFinite()) return
        val targetX = (
            info.width / 2f +
                (horizontalAnchor + localZDelta / rangeRad(horizontalRangeDeg)) *
                info.width / 2f
            )
            .coerceIn(0f, info.width.toFloat())
        val targetY = (
            info.height / 2f +
                (verticalAnchor + localXDelta / rangeRad(verticalRangeDeg)) *
                info.height / 2f
            )
            .coerceIn(0f, info.height.toFloat())
        if (!targetX.isFinite() || !targetY.isFinite()) return

        smoothedX = if (smoothedX.isNaN()) targetX else smoothedX + (targetX - smoothedX) * smoothing
        smoothedY = if (smoothedY.isNaN()) targetY else smoothedY + (targetY - smoothedY) * smoothing
        if (!smoothedX.isFinite() || !smoothedY.isFinite()) return
        if (!shouldEmit(smoothedX, smoothedY)) return
        onPointChanged(PointF(smoothedX, smoothedY))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun remapRotationMatrix(xAxis: Int, yAxis: Int): FloatArray {
        return if (SensorManager.remapCoordinateSystem(
                rotationMatrix,
                xAxis,
                yAxis,
                remappedRotationMatrix
            )
        ) {
            remappedRotationMatrix
        } else {
            rotationMatrix
        }
    }

    private fun shouldEmit(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val now = SystemClock.uptimeMillis()
        if (lastEmitX.isNaN() || lastEmitY.isNaN()) {
            lastEmitX = x
            lastEmitY = y
            lastEmitMs = now
            return true
        }
        if (now - lastEmitMs < minEmitIntervalMs) return false
        val distance = hypot((x - lastEmitX).toDouble(), (y - lastEmitY).toDouble())
        if (distance < minEmitDistancePx) return false
        lastEmitX = x
        lastEmitY = y
        lastEmitMs = now
        return true
    }

    private fun rangeRad(degrees: Float): Float {
        return (degrees * PI / 180.0).toFloat().coerceAtLeast(0.01f)
    }

    companion object {
        const val DEFAULT_HORIZONTAL_RANGE_DEG = 20f
        const val DEFAULT_VERTICAL_RANGE_DEG = 10f
        const val DEFAULT_SMOOTHING = 0.5f
        const val DEFAULT_MIN_EMIT_INTERVAL_MS = 8L
        const val DEFAULT_MIN_EMIT_DISTANCE_PX = 1.5f
    }
}
