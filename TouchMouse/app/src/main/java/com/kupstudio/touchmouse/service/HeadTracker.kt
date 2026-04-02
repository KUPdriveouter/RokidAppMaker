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
 */
class HeadTracker(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HeadTracker"
        private const val SENSOR_RATE = SensorManager.SENSOR_DELAY_GAME

        // EMA smoothing: 0 = max smooth, 1 = no smooth
        private const val SMOOTHING = 0.4f

        // Dead zone in rad/s — ignore sensor noise below this
        private const val DEAD_ZONE = 0.015f
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

    var listener: Listener? = null
    var sensitivityX = 150f
    var sensitivityY = 3500f

    private var isTracking = false
    private var lastTimestamp = 0L

    // Smoothed angular velocities
    private var smoothX = 0f  // yaw (left/right)
    private var smoothY = 0f  // pitch (up/down)

    fun start(): Boolean {
        if (gyroscope == null) {
            DebugLog.e(TAG, "Gyroscope not available")
            return false
        }
        lastTimestamp = 0L
        smoothX = 0f
        smoothY = 0f
        isTracking = true
        sensorManager.registerListener(this, gyroscope, SENSOR_RATE)
        DebugLog.i(TAG, "Head tracking started (gyro)")
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

    override fun onSensorChanged(event: SensorEvent) {
        if (!isTracking) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

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

        // Dead zone — combined magnitude
        val mag = Math.sqrt((smoothX * smoothX + smoothY * smoothY).toDouble()).toFloat()
        if (mag < DEAD_ZONE) return

        // Convert angular velocity to pixel displacement
        // angular_velocity (rad/s) * dt (s) * sensitivity (px/rad) = pixels
        val dx = -smoothX * dt * sensitivityX
        val dy = -smoothY * dt * sensitivityY

        // Always report raw gyro for gesture detection (even in dead zone)
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
