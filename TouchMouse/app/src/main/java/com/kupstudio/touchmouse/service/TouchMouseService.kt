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

        private const val DEFAULT_SENSITIVITY_X = 150f
        private const val DEFAULT_SENSITIVITY_Y = 3500f

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
            DebugLog.i(TAG, "HEAD MOUSE ON")
        } else {
            headTracker.stop()
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
