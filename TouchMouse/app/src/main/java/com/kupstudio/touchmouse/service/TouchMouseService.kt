package com.kupstudio.touchmouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.kupstudio.touchmouse.util.DebugLog

class TouchMouseService : AccessibilityService(), HeadTracker.Listener {

    companion object {
        private const val TAG = "TouchMouseSvc"

        const val ACTION_TOGGLE = "com.kupstudio.touchmouse.TOGGLE"
        const val ACTION_STATUS_CHANGED = "com.kupstudio.touchmouse.STATUS_CHANGED"
        const val EXTRA_ACTIVE = "active"

        const val PREFS_NAME = "touch_mouse_prefs"
        const val PREF_SENSITIVITY_X = "head_sensitivity_x"
        const val PREF_SENSITIVITY_Y = "head_sensitivity_y"
        const val PREF_DWELL_ENABLED = "dwell_enabled"
        const val PREF_DWELL_DURATION = "dwell_duration"
        const val PREF_DWELL_RADIUS = "dwell_radius"
        const val PREF_DWELL_DELAY = "dwell_delay"
        const val PREF_SHAKE_BACK_ENABLED = "shake_back_enabled"
        const val PREF_RECENTER_ENABLED = "recenter_enabled"

        private const val DEFAULT_SENSITIVITY_X = 150f
        private const val DEFAULT_SENSITIVITY_Y = 3500f
        private const val DEFAULT_DWELL_DURATION = 3000L  // 3 seconds
        private const val DEFAULT_DWELL_RADIUS = 30f
        private const val DEFAULT_DWELL_DELAY = 1000L

        // Head shake → back button
        // Yaw angular velocity threshold (rad/s) to register a direction crossing
        private const val SHAKE_THRESHOLD = 1.5f
        // Time window for 2 shakes (4 alternating crossings)
        private const val SHAKE_WINDOW_MS = 1200L
        // Required alternating crossings for 1 shake (recenter) / 2 shakes (back)
        private const val SHAKE_1_CROSSINGS = 2
        private const val SHAKE_2_CROSSINGS = 4
        // Cooldown after triggering action
        private const val SHAKE_COOLDOWN_MS = 1500L
        // Recenter casting duration (ms)
        private const val RECENTER_CAST_MS = 800L

        // Konami-style command: LEFT LEFT RIGHT RIGHT
        private const val COMMAND_TIMEOUT_MS = 2000L

        var instance: TouchMouseService? = null
            private set
    }

    private lateinit var headTracker: HeadTracker
    private lateinit var cursorOverlay: CursorOverlayManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cursorX = 240f
    private var cursorY = 320f
    private var screenWidth = 480
    private var screenHeight = 640
    private var isActive = false

    // Dwell click state
    var dwellEnabled = false
        private set
    var dwellDuration = DEFAULT_DWELL_DURATION
        private set
    var dwellRadius = DEFAULT_DWELL_RADIUS
        private set
    var dwellDelay = DEFAULT_DWELL_DELAY
        private set
    private var dwellAnchorX = 0f
    private var dwellAnchorY = 0f
    private var dwellStartTime = 0L
    private var dwellFired = false
    private var dwellCooldownUntil = 0L
    private val dwellHandler = Handler(Looper.getMainLooper())
    private val dwellTickRunnable = object : Runnable {
        override fun run() {
            if (!isActive || !dwellEnabled) return
            updateDwellProgress()
            dwellHandler.postDelayed(this, 50)  // update ~20fps
        }
    }

    // Head shake → back / recenter
    var shakeBackEnabled = false
        private set
    var recenterEnabled = false
        private set
    private val shakeCrossings = mutableListOf<Long>()
    private var shakeLastDirection = 0  // +1 = right, -1 = left, 0 = none
    private var shakeCooldownUntil = 0L

    // Recenter casting state
    private var recenterCasting = false
    private var recenterCastStart = 0L
    private val recenterHandler = Handler(Looper.getMainLooper())
    private val recenterTickRunnable = object : Runnable {
        override fun run() {
            if (!recenterCasting) return
            val elapsed = System.currentTimeMillis() - recenterCastStart
            val progress = (elapsed.toFloat() / RECENTER_CAST_MS).coerceIn(0f, 1f)
            cursorOverlay.setRecenterProgress(progress)
            if (elapsed >= RECENTER_CAST_MS) {
                completeRecenter()
            } else {
                recenterHandler.postDelayed(this, 30)
            }
        }
    }

    // Command sequence detection: L L R R
    private val commandSequence = listOf(
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )
    private val inputBuffer = mutableListOf<Int>()
    private var lastInputTime = 0L

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TOGGLE) {
                toggleActive()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        headTracker = HeadTracker(this)
        headTracker.listener = this
        headTracker.sensitivityX = prefs.getFloat(PREF_SENSITIVITY_X, DEFAULT_SENSITIVITY_X)
        headTracker.sensitivityY = prefs.getFloat(PREF_SENSITIVITY_Y, DEFAULT_SENSITIVITY_Y)

        cursorOverlay = CursorOverlayManager(this)

        dwellEnabled = prefs.getBoolean(PREF_DWELL_ENABLED, false)
        dwellDuration = prefs.getLong(PREF_DWELL_DURATION, DEFAULT_DWELL_DURATION)
        dwellRadius = prefs.getFloat(PREF_DWELL_RADIUS, DEFAULT_DWELL_RADIUS)
        dwellDelay = prefs.getLong(PREF_DWELL_DELAY, DEFAULT_DWELL_DELAY)
        shakeBackEnabled = prefs.getBoolean(PREF_SHAKE_BACK_ENABLED, false)
        recenterEnabled = prefs.getBoolean(PREF_RECENTER_ENABLED, false)
        registerReceiver(toggleReceiver, IntentFilter(ACTION_TOGGLE), RECEIVER_NOT_EXPORTED)
        DebugLog.i(TAG, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DebugLog.i(TAG, "Accessibility service connected")

        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        DebugLog.i(TAG, "Screen: ${screenWidth}x${screenHeight}")
        broadcastStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isActive) {
            headTracker.stop()
            cursorOverlay.hide()
        }
        try {
            unregisterReceiver(toggleReceiver)
        } catch (_: Exception) {}
        instance = null
        DebugLog.i(TAG, "Service destroyed")
    }

    fun toggleActive() {
        isActive = !isActive
        if (isActive) {
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            cursorOverlay.show(cursorX, cursorY)
            headTracker.start()
            resetDwell()
            if (dwellEnabled) dwellHandler.post(dwellTickRunnable)
            DebugLog.i(TAG, "HEAD MOUSE ON")
        } else {
            headTracker.stop()
            dwellHandler.removeCallbacks(dwellTickRunnable)
            cancelRecenter()
            cursorOverlay.setDwellProgress(0f)
            cursorOverlay.hide()
            DebugLog.i(TAG, "HEAD MOUSE OFF")
        }
        broadcastStatus()
    }

    fun isActive(): Boolean = isActive

    fun updateSensitivityX(value: Float) {
        headTracker.sensitivityX = value
        prefs.edit().putFloat(PREF_SENSITIVITY_X, value).apply()
    }

    fun updateSensitivityY(value: Float) {
        headTracker.sensitivityY = value
        prefs.edit().putFloat(PREF_SENSITIVITY_Y, value).apply()
    }

    fun getSensitivityX(): Float = headTracker.sensitivityX
    fun getSensitivityY(): Float = headTracker.sensitivityY

    fun setDwellEnabled(enabled: Boolean) {
        dwellEnabled = enabled
        prefs.edit().putBoolean(PREF_DWELL_ENABLED, enabled).apply()
        if (isActive) {
            if (enabled) {
                resetDwell()
                dwellHandler.post(dwellTickRunnable)
            } else {
                dwellHandler.removeCallbacks(dwellTickRunnable)
                cursorOverlay.setDwellProgress(0f)
            }
        }
    }

    fun updateDwellDuration(ms: Long) {
        dwellDuration = ms.coerceIn(1000L, 10000L)
        prefs.edit().putLong(PREF_DWELL_DURATION, dwellDuration).apply()
        resetDwell()
    }

    fun updateDwellRadius(px: Float) {
        dwellRadius = px.coerceIn(10f, 80f)
        prefs.edit().putFloat(PREF_DWELL_RADIUS, dwellRadius).apply()
        resetDwell()
    }

    fun updateDwellDelay(ms: Long) {
        dwellDelay = ms.coerceIn(0L, 5000L)
        prefs.edit().putLong(PREF_DWELL_DELAY, dwellDelay).apply()
        resetDwell()
    }

    fun setShakeBackEnabled(enabled: Boolean) {
        shakeBackEnabled = enabled
        prefs.edit().putBoolean(PREF_SHAKE_BACK_ENABLED, enabled).apply()
    }

    fun setRecenterEnabled(enabled: Boolean) {
        recenterEnabled = enabled
        prefs.edit().putBoolean(PREF_RECENTER_ENABLED, enabled).apply()
    }

    // ── Head shake detection ──
    //  1 shake (2 crossings) + recenter enabled → start casting → recenter
    //  2 shakes (4 crossings) + shake back enabled → cancel casting → back

    override fun onRawYaw(yaw: Float) {
        if (!isActive) return
        if (!shakeBackEnabled && !recenterEnabled) return

        val now = System.currentTimeMillis()
        if (now < shakeCooldownUntil) return

        val dir = when {
            yaw > SHAKE_THRESHOLD -> +1
            yaw < -SHAKE_THRESHOLD -> -1
            else -> 0
        }

        if (dir != 0 && dir != shakeLastDirection) {
            shakeLastDirection = dir
            shakeCrossings.add(now)
            shakeCrossings.removeAll { now - it > SHAKE_WINDOW_MS }

            // 2 shakes (4 crossings) → back (takes priority, cancels recenter)
            if (shakeBackEnabled && shakeCrossings.size >= SHAKE_2_CROSSINGS) {
                shakeCrossings.clear()
                shakeLastDirection = 0
                shakeCooldownUntil = now + SHAKE_COOLDOWN_MS
                cancelRecenter()
                DebugLog.i(TAG, "Head shake x2 → BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            // 1 shake (2 crossings) → start recenter casting
            if (recenterEnabled && !recenterCasting && shakeCrossings.size >= SHAKE_1_CROSSINGS) {
                startRecenterCast()
            }
        }
    }

    private fun startRecenterCast() {
        recenterCasting = true
        recenterCastStart = System.currentTimeMillis()
        cursorOverlay.setRecenterProgress(0f)
        recenterHandler.post(recenterTickRunnable)
        DebugLog.d(TAG, "Recenter casting started")
    }

    private fun cancelRecenter() {
        if (!recenterCasting) return
        recenterCasting = false
        recenterHandler.removeCallbacks(recenterTickRunnable)
        cursorOverlay.setRecenterProgress(0f)
    }

    private fun completeRecenter() {
        recenterCasting = false
        recenterHandler.removeCallbacks(recenterTickRunnable)
        cursorOverlay.setRecenterProgress(0f)
        shakeCrossings.clear()
        shakeLastDirection = 0
        shakeCooldownUntil = System.currentTimeMillis() + SHAKE_COOLDOWN_MS

        // Recenter cursor
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
        cursorOverlay.updatePosition(cursorX, cursorY)
        headTracker.recenter()
        resetDwell()
        DebugLog.i(TAG, "Head shake x1 → RECENTER")
    }

    // ── Dwell click logic ──

    private fun resetDwell() {
        dwellAnchorX = cursorX
        dwellAnchorY = cursorY
        dwellStartTime = System.currentTimeMillis()
        dwellFired = false
        cursorOverlay.setDwellProgress(0f)
    }

    private fun updateDwellProgress() {
        if (!dwellEnabled || !isActive || dwellFired) return

        val now = System.currentTimeMillis()

        // Still in cooldown after last dwell click
        if (now < dwellCooldownUntil) return

        val dx = cursorX - dwellAnchorX
        val dy = cursorY - dwellAnchorY
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist > dwellRadius) {
            // Cursor moved — reset anchor
            resetDwell()
            return
        }

        val elapsed = now - dwellStartTime
        // Grace period: don't show progress or count until cursor has been still long enough
        if (elapsed < dwellDelay) return

        val activeElapsed = elapsed - dwellDelay
        val progress = (activeElapsed.toFloat() / dwellDuration).coerceIn(0f, 1f)
        cursorOverlay.setDwellProgress(progress)

        if (activeElapsed >= dwellDuration) {
            dwellFired = true
            dwellCooldownUntil = now + dwellDelay
            DebugLog.i(TAG, "Dwell click at (${cursorX.toInt()}, ${cursorY.toInt()})")
            performClick()
            mainHandler.postDelayed({ resetDwell() }, dwellDelay)
        }
    }

    // ── HeadTracker.Listener ──

    override fun onHeadMove(deltaX: Float, deltaY: Float): HeadTracker.ClampResult {
        if (!isActive) return HeadTracker.ClampResult(false, false)

        val newX = (cursorX + deltaX).coerceIn(0f, screenWidth - 1f)
        val newY = (cursorY + deltaY).coerceIn(0f, screenHeight - 1f)

        val clampedX = newX != cursorX + deltaX
        val clampedY = newY != cursorY + deltaY

        cursorX = newX
        cursorY = newY
        cursorOverlay.updatePosition(cursorX, cursorY)

        return HeadTracker.ClampResult(clampedX, clampedY)
    }

    // ── Key event handling ──

    // Let the app handle its own key events when it's in foreground
    var appInForeground = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // When our app is in foreground, pass all keys through to the Activity
        if (appInForeground) return false

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            if (isActive && event.action == KeyEvent.ACTION_UP) {
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> true
                    else -> false
                }
            }
            return false
        }

        // ── Command sequence detection (works always) ──
        val now = System.currentTimeMillis()
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Reset buffer if too much time passed
            if (now - lastInputTime > COMMAND_TIMEOUT_MS) {
                inputBuffer.clear()
            }
            lastInputTime = now
            inputBuffer.add(keyCode)

            // Check if last N inputs match the command
            if (inputBuffer.size >= commandSequence.size) {
                val tail = inputBuffer.takeLast(commandSequence.size)
                if (tail == commandSequence) {
                    inputBuffer.clear()
                    toggleActive()
                    return true
                }
            }

            // Trim buffer to prevent memory growth
            if (inputBuffer.size > 20) {
                inputBuffer.removeAt(0)
            }
        }

        // ── When active: tap = click, touch events pass through ──
        if (isActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    performClick()
                    resetDwell()
                    return true
                }
            }

        }

        // Let all other keys pass through to system
        return false
    }

    private fun performClick() {
        DebugLog.d(TAG, "Click at (${cursorX.toInt()}, ${cursorY.toInt()})")

        val path = Path()
        path.moveTo(cursorX, cursorY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                DebugLog.d(TAG, "Click OK")
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                DebugLog.w(TAG, "Click cancelled")
            }
        }, null)

        cursorOverlay.flashClick()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        DebugLog.w(TAG, "Service interrupted")
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVE, isActive)
        }
        sendBroadcast(intent)
    }
}
