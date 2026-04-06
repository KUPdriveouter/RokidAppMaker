package com.kupstudio.touchmouse.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.kupstudio.touchmouse.util.DebugLog
import kotlin.math.abs

/**
 * Tracks head rotation via Gyroscope directly.
 * No gimbal lock — gyro gives angular velocity in rad/s, we integrate per frame.
 * EMA low-pass filter to suppress jitter.
 *
 * Uses accelerometer to detect body motion (walking, vehicle) and adaptively
 * increases dead zone / reduces sensitivity to suppress false inputs.
 */
class HeadTracker(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HeadTracker"
        private const val SENSOR_RATE = SensorManager.SENSOR_DELAY_GAME

        // EMA smoothing: 0 = max smooth, 1 = no smooth
        private const val SMOOTHING = 0.4f

        // Dead zone in rad/s — ignore sensor noise below this
        private const val DEAD_ZONE = 0.015f

        // ── Motion detection (accelerometer) ──
        // Gravity magnitude ~9.8; we track variance of accel magnitude around gravity.
        private const val ACCEL_SMOOTHING = 0.1f            // EMA alpha for accel variance
        private const val MOTION_THRESHOLD_LOW = 1.5f      // below = stationary
        private const val MOTION_THRESHOLD_HIGH = 3.5f     // above = strong motion (vehicle)
        private const val MOTION_DEAD_ZONE_MAX = 0.08f     // max dead zone when in motion
        private const val MOTION_SENSITIVITY_SCALE = 0.5f  // min sensitivity multiplier
    }

    interface Listener {
        /** Returns which axes were clamped (hit screen edge) */
        fun onHeadMove(deltaX: Float, deltaY: Float): ClampResult
        /** Raw yaw angular velocity in rad/s (after smoothing) */
        fun onRawYaw(yaw: Float) {}
        /** Raw pitch angular velocity in rad/s (after smoothing) */
        fun onRawPitch(pitch: Float) {}
        /** Both axes together for combined detection */
        fun onRawGyro(yaw: Float, pitch: Float) {}
    }

    data class ClampResult(val clampedX: Boolean, val clampedY: Boolean)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var listener: Listener? = null
    var sensitivityX = 150f
    var sensitivityY = 3500f

    private var isTracking = false
    private var lastTimestamp = 0L

    // Smoothed angular velocities
    private var smoothX = 0f  // yaw (left/right)
    private var smoothY = 0f  // pitch (up/down)

    // ── Motion detection state ──
    private var accelVariance = 0f      // smoothed variance of accel magnitude
    private var motionFactor = 0f       // 0 = stationary, 1 = max motion

    fun start(): Boolean {
        if (gyroscope == null) {
            DebugLog.e(TAG, "Gyroscope not available")
            return false
        }
        resetState()
        isTracking = true
        sensorManager.registerListener(this, gyroscope, SENSOR_RATE)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        DebugLog.i(TAG, "Head tracking started (gyro + accel)")
        return true
    }

    /** Low-frequency mode for background gesture detection (shake to activate). */
    fun startPassive(): Boolean {
        if (gyroscope == null) return false
        resetState()
        isTracking = true
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        DebugLog.i(TAG, "Head tracking started (passive + accel)")
        return true
    }

    fun stop() {
        isTracking = false
        sensorManager.unregisterListener(this)
        DebugLog.i(TAG, "Head tracking stopped")
    }

    fun recenter() {
        smoothX = 0f
        smoothY = 0f
    }

    private fun resetState() {
        lastTimestamp = 0L
        smoothX = 0f
        smoothY = 0f
        accelVariance = 0f
        motionFactor = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isTracking) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
        }
    }

    private fun processAccelerometer(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        // Magnitude of acceleration; stationary ≈ 9.8
        val mag = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        // Deviation from gravity = how much the device is being shaken/bounced
        val deviation = abs(mag - 9.81f)

        // EMA smooth the deviation to get a stable motion estimate
        accelVariance = accelVariance + ACCEL_SMOOTHING * (deviation - accelVariance)

        // Map variance to 0..1 motion factor
        motionFactor = when {
            accelVariance <= MOTION_THRESHOLD_LOW -> 0f
            accelVariance >= MOTION_THRESHOLD_HIGH -> 1f
            else -> (accelVariance - MOTION_THRESHOLD_LOW) / (MOTION_THRESHOLD_HIGH - MOTION_THRESHOLD_LOW)
        }
    }

    private fun processGyroscope(event: SensorEvent) {
        val timestamp = event.timestamp
        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return
        }

        val dt = (timestamp - lastTimestamp) / 1_000_000_000f  // nanoseconds to seconds
        lastTimestamp = timestamp

        if (dt <= 0f || dt > 0.1f) return  // skip bad intervals

        // Gyroscope values: angular velocity in rad/s
        // event.values[0] = rotation around X axis (pitch — looking up/down)
        // event.values[1] = rotation around Y axis (yaw — looking left/right)
        // event.values[2] = rotation around Z axis (roll — not used)
        val gyroYaw = event.values[1]    // Y axis = left/right head turn
        val gyroPitch = event.values[0]  // X axis = up/down head tilt

        // EMA low-pass filter
        smoothX = smoothX + SMOOTHING * (gyroYaw - smoothX)
        smoothY = smoothY + SMOOTHING * (gyroPitch - smoothY)

        // Adaptive dead zone: increases with body motion
        val adaptiveDeadZone = DEAD_ZONE + (MOTION_DEAD_ZONE_MAX - DEAD_ZONE) * motionFactor

        // Dead zone — combined magnitude
        val mag = Math.sqrt((smoothX * smoothX + smoothY * smoothY).toDouble()).toFloat()
        if (mag < adaptiveDeadZone) return

        // Adaptive sensitivity: reduces with body motion
        val sensScale = 1f - (1f - MOTION_SENSITIVITY_SCALE) * motionFactor
        val effSensX = sensitivityX * sensScale
        val effSensY = sensitivityY * sensScale

        // Convert angular velocity to pixel displacement
        val dx = -smoothX * dt * effSensX
        val dy = -smoothY * dt * effSensY

        // Always report raw gyro for gesture detection
        listener?.onRawYaw(smoothX)
        listener?.onRawPitch(smoothY)
        listener?.onRawGyro(smoothX, smoothY)

        if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
            val result = listener?.onHeadMove(dx, dy)
            // Reset filter on clamped axes so reversing direction responds instantly
            if (result != null) {
                if (result.clampedX) smoothX = 0f
                if (result.clampedY) smoothY = 0f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
