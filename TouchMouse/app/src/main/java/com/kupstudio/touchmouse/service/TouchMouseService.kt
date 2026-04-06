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
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kupstudio.touchmouse.util.DebugLog

class TouchMouseService : AccessibilityService(), HeadTracker.Listener {

    companion object {
        private const val TAG = "TouchMouseSvc"

        const val ACTION_TOGGLE = "com.kupstudio.touchmouse.TOGGLE"
        const val ACTION_LAUNCHER_SCROLL = "com.kupstudio.touchmouse.LAUNCHER_SCROLL"
        const val EXTRA_DIRECTION = "direction" // "forward" or "backward"
        const val ACTION_STATUS_CHANGED = "com.kupstudio.touchmouse.STATUS_CHANGED"
        const val EXTRA_ACTIVE = "active"

        const val PREFS_NAME = "touch_mouse_prefs"
        const val PREF_SENSITIVITY_X = "head_sensitivity_x"
        const val PREF_SENSITIVITY_Y = "head_sensitivity_y"
        const val PREF_DWELL_DURATION = "dwell_duration"
        const val PREF_DWELL_RADIUS = "dwell_radius"
        const val PREF_DWELL_DELAY = "dwell_delay"
        const val PREF_SHAKE_BACK_ENABLED = "shake_back_enabled"
        const val PREF_AWAKE_ENABLED = "awake_enabled"
        const val PREF_AWAKE_AUTO_CURSOR = "awake_auto_cursor"
        const val PREF_AUTO_FOCUS = "auto_focus"

        // Auto focus (drift to center when idle)
        private const val AUTO_FOCUS_IDLE_MS = 600L     // start after 600ms idle
        private const val AUTO_FOCUS_SPEED = 1.5f       // pixels per tick (50ms) ≈ 30px/sec

        private const val DEFAULT_SENSITIVITY_X = 150f
        private const val DEFAULT_SENSITIVITY_Y = 3500f
        private const val DEFAULT_DWELL_DURATION = 3000L
        private const val DEFAULT_DWELL_RADIUS = 50f
        private const val DEFAULT_DWELL_DELAY = 1000L

        // Head shake detection — moderate deliberate motion
        private const val SHAKE_THRESHOLD = 2.0f        // moderate head turn (not walking, not extreme)
        private const val SHAKE_WINDOW_MS = 2500L       // allow relaxed pace
        private const val SHAKE_2_CROSSINGS = 3          // 1.5 shakes: left-right-left
        private const val SHAKE_COOLDOWN_MS = 800L
        private const val SHAKE_MIN_INTERVAL_MS = 250L  // ignore rapid micro-oscillations (walking/car)

        // Nod detection (pitch axis) — peak-based
        private const val NOD_PEAK_THRESHOLD = 0.5f
        private const val NOD_RETURN_THRESHOLD = 0.15f
        private const val NOD_WINDOW_MS = 2000L
        private const val NOD_COOLDOWN_MS = 800L

        // Joystick scroll
        private const val SCROLL_DEAD_X = 80f
        private const val SCROLL_DEAD_Y = 120f
        private const val SCROLL_REPEAT_MS = 250L
        private const val SCROLL_MIN_PX = 60f
        private const val SCROLL_ACCEL_PER_SEC = 80f
        private const val SCROLL_MAX_PX = 300f
        private const val SCROLL_GESTURE_MS = 120L

        // Konami-style command: L L R R
        private const val COMMAND_TIMEOUT_MS = 2000L

        // Selection mode — casting duration to confirm choice
        private const val SELECTION_CAST_MS = 1500L
        private const val SELECTION_DEAD_ZONE = 60f

        var instance: TouchMouseService? = null
            private set
    }

    // -- Core --

    private lateinit var headTracker: HeadTracker
    private lateinit var cursorOverlay: CursorOverlayManager
    private lateinit var helpOverlay: HelpOverlayManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cursorX = 240f
    private var cursorY = 320f
    private var screenWidth = 480
    private var screenHeight = 640
    private var isActive = false
    var appInForeground = false

    // -- Dwell --

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
            if (!isActive) return
            if (!dwellEnabled && !autoFocusEnabled && scrollState == ScrollState.NORMAL) return
            updateDwellProgress()
            updateAutoFocus()
            dwellHandler.postDelayed(this, 50)
        }
    }

    // -- Scroll mode --

    enum class ScrollState { NORMAL, SCROLL_READY, SCROLLING }
    var scrollState = ScrollState.NORMAL
        private set
    private var dwellWasEnabled = false
    private var scrollVirtualX = 0f
    private var scrollVirtualY = 0f
    private var scrollDirX = 0f
    private var scrollDirY = 0f
    private var scrollMaxX = 0f
    private var scrollMaxY = 0f
    private var scrollHoldStart = 0L
    private var scrollLastDispatch = 0L
    private var scrollNeutralTime = 0L
    private var scrollEverSwiped = false
    private var scrollAnchorTime = 0L

    // -- Selection mode --

    var selectionMode = false
        private set
    private var selectionVirtualX = 0f
    private var selectionVirtualY = 0f
    private var selectionDir = 0          // 0=none, 1=up, 2=down, 3=left, 4=right
    private var selectionDirStart = 0L    // when current dir was first held
    private var selectionEnteredAt = 0L   // cooldown: ignore input right after entering
    private val selectionTickRunnable = object : Runnable {
        override fun run() {
            if (!selectionMode) return
            updateSelectionCast()
            mainHandler.postDelayed(this, 50)
        }
    }

    // -- Head shake --

    var shakeBackEnabled = false
        private set
    var awakeEnabled = false
        private set
    var awakeAutoCursor = false
        private set
    var autoFocusEnabled = false
        private set
    private var lastCursorMoveTime = 0L
    private val shakeCrossings = mutableListOf<Long>()
    private var shakeLastDirection = 0
    private var shakeCooldownUntil = 0L

    // -- Nod detection --

    private data class NodPeak(val time: Long, val dir: Int)
    private val nodPeaks = mutableListOf<NodPeak>()
    private var nodInPeak = false
    private var nodPeakDir = 0
    private var nodCooldownUntil = 0L

    // -- Konami command: L L R R --

    private val commandSequence = listOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT
    )
    private val inputBuffer = mutableListOf<Int>()
    private var lastInputTime = 0L

    // -- Screen state --

    private var screenOff = false
    private var wasActiveBeforeScreenOff = false
    private var wasPassiveBeforeScreenOff = false

    // -- Receivers --

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> toggleActive()
                ACTION_LAUNCHER_SCROLL -> {
                    val dir = intent.getStringExtra(EXTRA_DIRECTION) ?: "forward"
                    scrollLauncherPage(dir == "forward")
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    // ==============================
    //  Lifecycle
    // ==============================

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        headTracker = HeadTracker(this)
        headTracker.listener = this
        headTracker.sensitivityX = prefs.getFloat(PREF_SENSITIVITY_X, DEFAULT_SENSITIVITY_X)
        headTracker.sensitivityY = prefs.getFloat(PREF_SENSITIVITY_Y, DEFAULT_SENSITIVITY_Y)

        cursorOverlay = CursorOverlayManager(this)
        helpOverlay = HelpOverlayManager(this)

        dwellDuration = prefs.getLong(PREF_DWELL_DURATION, DEFAULT_DWELL_DURATION)
        dwellRadius = prefs.getFloat(PREF_DWELL_RADIUS, DEFAULT_DWELL_RADIUS)
        dwellDelay = prefs.getLong(PREF_DWELL_DELAY, DEFAULT_DWELL_DELAY)
        shakeBackEnabled = prefs.getBoolean(PREF_SHAKE_BACK_ENABLED, false)
        awakeEnabled = prefs.getBoolean(PREF_AWAKE_ENABLED, false)
        awakeAutoCursor = prefs.getBoolean(PREF_AWAKE_AUTO_CURSOR, false)
        autoFocusEnabled = prefs.getBoolean(PREF_AUTO_FOCUS, false)
        registerReceiver(toggleReceiver, IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_LAUNCHER_SCROLL)
        }, RECEIVER_NOT_EXPORTED)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        DebugLog.i(TAG, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ensure flags are set programmatically
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        DebugLog.i(TAG, "Accessibility service connected, flags=0x${serviceInfo.flags.toString(16)}")

        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
        DebugLog.i(TAG, "Screen: ${screenWidth}x${screenHeight}")
        // Start passive sensor so shake-to-activate works when cursor is off
        if (!isActive) headTracker.startPassive()
        broadcastStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        headTracker.stop()
        if (isActive) {
            cursorOverlay.hide()
            helpOverlay.hide()
        }
        try { unregisterReceiver(toggleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        instance = null
        DebugLog.i(TAG, "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { DebugLog.w(TAG, "Service interrupted") }

    // ==============================
    //  Public API
    // ==============================

    fun toggleActive() {
        isActive = !isActive
        if (isActive) {
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            cursorOverlay.show(cursorX, cursorY)
            headTracker.stop()
            headTracker.start()
            resetDwell()
            startDwellTicker()
            DebugLog.i(TAG, "HEAD MOUSE ON")
        } else {
            headTracker.stop()
            dwellHandler.removeCallbacks(dwellTickRunnable)
            exitSelectionMode()
            exitScrollMode()
            setDwellMode(false)
            cursorOverlay.setDwellProgress(0f)
            cursorOverlay.hide()
            helpOverlay.hide()
            // Resume passive sensor so shake-to-activate works
            headTracker.startPassive()
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

    fun setAwakeEnabled(enabled: Boolean) {
        awakeEnabled = enabled
        prefs.edit().putBoolean(PREF_AWAKE_ENABLED, enabled).apply()
    }

    fun setAwakeAutoCursor(enabled: Boolean) {
        awakeAutoCursor = enabled
        prefs.edit().putBoolean(PREF_AWAKE_AUTO_CURSOR, enabled).apply()
    }

    fun setAutoFocus(enabled: Boolean) {
        autoFocusEnabled = enabled
        prefs.edit().putBoolean(PREF_AUTO_FOCUS, enabled).apply()
    }

    // ==============================
    //  Overlay sync
    // ==============================

    private fun syncOverlay() {
        cursorOverlay.setDwellMode(dwellEnabled)
        cursorOverlay.setScrollMode(scrollState != ScrollState.NORMAL)
        cursorOverlay.setSelectionMode(selectionMode)
        cursorOverlay.setDwellProgress(0f)
        when {
            selectionMode ->
                helpOverlay.show("SELECT MODE", "L:scroll  R:dwell  U:off  D:exit")
            scrollState == ScrollState.SCROLLING ->
                helpOverlay.show("SCROLLING", "tilt: scroll | stop + dwell: release | nod x2: exit")
            scrollState == ScrollState.SCROLL_READY ->
                helpOverlay.show("SCROLL MODE", "move to target | dwell: anchor | nod x2: exit")
            dwellEnabled ->
                helpOverlay.show("DWELL MODE", "nod x2: exit")
            else ->
                helpOverlay.hide()
        }
    }

    // ==============================
    //  Dwell mode
    // ==============================

    private fun setDwellMode(on: Boolean) {
        dwellEnabled = on
        if (on) {
            resetDwell()
            startDwellTicker()
        } else if (scrollState == ScrollState.NORMAL && !autoFocusEnabled) {
            dwellHandler.removeCallbacks(dwellTickRunnable)
        }
        syncOverlay()
        DebugLog.i(TAG, "Dwell mode ${if (on) "ON" else "OFF"}")
        broadcastStatus()
    }

    private fun startDwellTicker() {
        dwellHandler.removeCallbacks(dwellTickRunnable)
        dwellHandler.post(dwellTickRunnable)
    }

    private fun resetDwell() {
        dwellAnchorX = cursorX
        dwellAnchorY = cursorY
        dwellStartTime = System.currentTimeMillis()
        dwellFired = false
        cursorOverlay.setDwellProgress(0f)
    }

    private fun updateDwellProgress() {
        if (!isActive || dwellFired) return
        if (!dwellEnabled && scrollState == ScrollState.NORMAL) return

        val now = System.currentTimeMillis()
        if (now < dwellCooldownUntil) return

        val dx = cursorX - dwellAnchorX
        val dy = cursorY - dwellAnchorY
        if (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() > dwellRadius) {
            resetDwell()
            return
        }

        if (scrollState == ScrollState.SCROLLING) {
            if (scrollDirX != 0f || scrollDirY != 0f) return
            if (!scrollEverSwiped && now - scrollAnchorTime < 3000L) return
        }

        val elapsed = now - dwellStartTime
        if (elapsed < dwellDelay) return

        val activeElapsed = elapsed - dwellDelay
        cursorOverlay.setDwellProgress((activeElapsed.toFloat() / dwellDuration).coerceIn(0f, 1f))

        if (activeElapsed >= dwellDuration) {
            dwellFired = true
            dwellCooldownUntil = now + dwellDelay
            when (scrollState) {
                ScrollState.SCROLL_READY -> {
                    DebugLog.i(TAG, "Dwell -> anchor at (${cursorX.toInt()}, ${cursorY.toInt()})")
                    enterScrolling()
                    mainHandler.postDelayed({ resetDwell() }, dwellDelay)
                }
                ScrollState.SCROLLING -> {
                    DebugLog.i(TAG, "Dwell -> exit scroll (anchor released)")
                    exitScrollMode()
                }
                ScrollState.NORMAL -> {
                    DebugLog.i(TAG, "Dwell click at (${cursorX.toInt()}, ${cursorY.toInt()})")
                    performClick()
                    mainHandler.postDelayed({ resetDwell() }, dwellDelay)
                }
            }
        }
    }

    // ==============================
    //  Auto focus (drift to center when idle)
    // ==============================

    private fun updateAutoFocus() {
        if (!autoFocusEnabled || !isActive) return
        if (dwellEnabled || selectionMode || scrollState != ScrollState.NORMAL) return

        val now = System.currentTimeMillis()
        if (now - lastCursorMoveTime < AUTO_FOCUS_IDLE_MS) return

        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val dx = cx - cursorX
        val dy = cy - cursorY
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < 2f) return  // close enough

        val step = AUTO_FOCUS_SPEED.coerceAtMost(dist)
        cursorX += dx / dist * step
        cursorY += dy / dist * step
        cursorOverlay.updatePosition(cursorX, cursorY)
    }

    // ==============================
    //  Scroll mode
    // ==============================

    private fun enterScrollReady() {
        dwellWasEnabled = dwellEnabled
        if (dwellEnabled) {
            dwellEnabled = false
            dwellHandler.removeCallbacks(dwellTickRunnable)
            cursorOverlay.setDwellProgress(0f)
        }
        scrollState = ScrollState.SCROLL_READY
        resetDwell()
        startDwellTicker()
        syncOverlay()
        DebugLog.i(TAG, "SCROLL READY — move cursor to target, dwell to anchor")
    }

    private fun enterScrolling() {
        scrollState = ScrollState.SCROLLING
        scrollVirtualX = 0f
        scrollVirtualY = 0f
        scrollDirX = 0f
        scrollDirY = 0f
        scrollMaxX = 0f
        scrollMaxY = 0f
        scrollHoldStart = 0L
        scrollLastDispatch = 0L
        scrollNeutralTime = 0L
        scrollEverSwiped = false
        scrollAnchorTime = System.currentTimeMillis()
        syncOverlay()
        DebugLog.i(TAG, "SCROLLING — tilt and return to swipe")
    }

    private fun exitScrollMode() {
        if (scrollState == ScrollState.NORMAL) return
        scrollState = ScrollState.NORMAL
        scrollDirX = 0f
        scrollDirY = 0f
        if (dwellWasEnabled) dwellEnabled = true
        dwellWasEnabled = false
        if (dwellEnabled || autoFocusEnabled) {
            resetDwell()
            startDwellTicker()
        }
        syncOverlay()
        DebugLog.i(TAG, "Scroll OFF")
    }

    private fun updateJoystickScroll() {
        val now = System.currentTimeMillis()
        val normX = scrollVirtualX / SCROLL_DEAD_X
        val normY = scrollVirtualY / SCROLL_DEAD_Y
        val absNX = kotlin.math.abs(normX)
        val absNY = kotlin.math.abs(normY)

        if (scrollDirX != 0f) scrollMaxX = maxOf(scrollMaxX, absNX).coerceAtMost(2.0f)
        if (scrollDirY != 0f) scrollMaxY = maxOf(scrollMaxY, absNY).coerceAtMost(2.0f)

        // Active scroll — check neutral condition
        if (scrollDirX != 0f || scrollDirY != 0f) {
            val shouldNeutral = if (scrollDirX != 0f) {
                val t = -scrollMaxX * 0.15f
                (scrollDirX > 0 && normX < t) || (scrollDirX < 0 && normX > -t)
            } else {
                val t = -scrollMaxY * 0.15f
                (scrollDirY > 0 && normY < t) || (scrollDirY < 0 && normY > -t)
            }
            if (shouldNeutral) {
                scrollDirX = 0f; scrollDirY = 0f
                scrollMaxX = 0f; scrollMaxY = 0f
                scrollVirtualX = 0f; scrollVirtualY = 0f
                scrollNeutralTime = now
                cursorOverlay.setScrollDir(0f, 0f)
                DebugLog.d(TAG, "Scroll neutral (returned past max)")
            } else if (now - scrollLastDispatch >= SCROLL_REPEAT_MS) {
                val holdSec = (now - scrollHoldStart) / 1000f
                val dist = (SCROLL_MIN_PX + SCROLL_ACCEL_PER_SEC * holdSec).coerceAtMost(SCROLL_MAX_PX)
                dispatchSwipe(-scrollDirX * dist, -scrollDirY * dist)
                scrollLastDispatch = now
                scrollEverSwiped = true
                resetDwell()
            }
            return
        }

        // Neutral — check direction activation (500ms cooldown)
        if (now - scrollNeutralTime < 500L) {
            scrollVirtualX = 0f; scrollVirtualY = 0f
            return
        }
        if (maxOf(absNX, absNY) >= 1.0f) {
            if (absNX > absNY) {
                scrollDirX = if (scrollVirtualX > 0) 1f else -1f; scrollDirY = 0f
            } else {
                scrollDirX = 0f; scrollDirY = if (scrollVirtualY > 0) 1f else -1f
            }
            scrollMaxX = absNX; scrollMaxY = absNY
            scrollHoldStart = now; scrollLastDispatch = 0L
            cursorOverlay.setScrollDir(scrollDirX, scrollDirY)
            resetDwell()
            DebugLog.d(TAG, "Scroll dir=(${scrollDirX},${scrollDirY})")
        }
    }

    private fun dispatchSwipe(dx: Float, dy: Float) {
        val offsetX = if (dx != 0f) dx / kotlin.math.abs(dx) * 60f else 0f
        val offsetY = if (dy != 0f) dy / kotlin.math.abs(dy) * 60f else 0f
        val sx = (cursorX + offsetX).coerceIn(20f, screenWidth - 20f)
        val sy = (cursorY + offsetY).coerceIn(20f, screenHeight - 20f)
        val endX = (sx + dx).coerceIn(10f, screenWidth - 10f)
        val endY = (sy + dy).coerceIn(10f, screenHeight - 10f)

        val dist = Math.sqrt(((endX - sx) * (endX - sx) + (endY - sy) * (endY - sy)).toDouble())
        if (dist < 10.0) return

        DebugLog.d(TAG, "Swipe (${sx.toInt()},${sy.toInt()})->(${endX.toInt()},${endY.toInt()}) dist=${dist.toInt()}")
        val path = Path().apply { moveTo(sx, sy); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_GESTURE_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ==============================
    //  Selection mode
    // ==============================

    private fun enterSelectionMode() {
        if (selectionMode || screenOff) return
        selectionMode = true
        // Pause dwell while in selection
        dwellHandler.removeCallbacks(dwellTickRunnable)
        cursorOverlay.setDwellProgress(0f)
        // Center cursor (slightly above center for better visibility)
        cursorX = screenWidth / 2f
        cursorY = screenHeight * 0.35f
        cursorOverlay.updatePosition(cursorX, cursorY)
        // Reset selection state
        selectionVirtualX = 0f
        selectionVirtualY = 0f
        selectionDir = 0
        selectionDirStart = 0L
        selectionEnteredAt = System.currentTimeMillis()
        // Start ticker
        mainHandler.post(selectionTickRunnable)
        syncOverlay()
        DebugLog.i(TAG, "SELECTION MODE — choose: L=scroll R=dwell U=off D=exit")
    }

    private fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        mainHandler.removeCallbacks(selectionTickRunnable)
        cursorOverlay.setSelectionMode(false)
        cursorOverlay.setSelectionDir(0, 0f)
        syncOverlay()
        // Resume dwell/autoFocus tick
        if (dwellEnabled || autoFocusEnabled) startDwellTicker()
        DebugLog.i(TAG, "Selection mode OFF")
    }

    private fun updateSelectionCast() {
        if (!selectionMode) return
        val now = System.currentTimeMillis()

        // Ignore input for 800ms after entering to let nod motion settle
        if (now - selectionEnteredAt < 800L) {
            selectionVirtualX = 0f
            selectionVirtualY = 0f
            return
        }

        val absX = kotlin.math.abs(selectionVirtualX)
        val absY = kotlin.math.abs(selectionVirtualY)

        // Determine which direction is dominant
        val newDir = if (maxOf(absX, absY) < SELECTION_DEAD_ZONE) {
            0 // neutral
        } else if (absX > absY) {
            if (selectionVirtualX < 0) 3 else 4 // left or right
        } else {
            if (selectionVirtualY < 0) 1 else 2 // up or down
        }

        if (newDir != selectionDir) {
            selectionDir = newDir
            selectionDirStart = if (newDir != 0) now else 0L
        }

        // Calculate cast progress
        val progress = if (selectionDir != 0 && selectionDirStart > 0) {
            ((now - selectionDirStart).toFloat() / SELECTION_CAST_MS).coerceIn(0f, 1f)
        } else 0f

        cursorOverlay.setSelectionDir(selectionDir, progress)

        // If cast completed, execute the selection
        if (progress >= 1f) {
            val dir = selectionDir
            exitSelectionMode()
            when (dir) {
                1 -> { // Up = cursor off
                    DebugLog.i(TAG, "Selection: UP -> cursor OFF")
                    toggleActive()
                }
                2 -> { // Down = exit selection (back to normal cursor)
                    DebugLog.i(TAG, "Selection: DOWN -> exit (normal cursor)")
                    // Already exited above
                }
                3 -> { // Left = scroll mode
                    DebugLog.i(TAG, "Selection: LEFT -> scroll mode")
                    enterScrollReady()
                }
                4 -> { // Right = dwell mode
                    DebugLog.i(TAG, "Selection: RIGHT -> dwell mode")
                    setDwellMode(true)
                }
            }
        }
    }

    // ==============================
    //  Nod detection
    // ==============================

    override fun onRawPitch(pitch: Float) {
        if (!isActive || screenOff) return
        val now = System.currentTimeMillis()
        if (now < nodCooldownUntil) return

        val absPitch = kotlin.math.abs(pitch)
        if (!nodInPeak) {
            if (absPitch > NOD_PEAK_THRESHOLD) {
                nodInPeak = true
                nodPeakDir = if (pitch > 0) +1 else -1
            }
        } else if (absPitch < NOD_RETURN_THRESHOLD) {
            nodInPeak = false
            nodPeaks.removeAll { now - it.time > NOD_WINDOW_MS }
            nodPeaks.add(NodPeak(now, nodPeakDir))
            DebugLog.d(TAG, "Nod peak #${nodPeaks.size} dir=${nodPeakDir}")

            if (nodPeaks.size >= 4) {
                val last4 = nodPeaks.takeLast(4)
                // Must start with down(-1): down, up, down, up
                val pattern = last4[0].dir == -1 && last4[1].dir == +1 &&
                              last4[2].dir == -1 && last4[3].dir == +1
                if (pattern) {
                    nodPeaks.clear()
                    nodCooldownUntil = now + NOD_COOLDOWN_MS
                    DebugLog.i(TAG, "Nod x2 detected")

                    when {
                        selectionMode -> {
                            // Nod in selection mode: ignore (use direction casting instead)
                        }
                        scrollState != ScrollState.NORMAL -> {
                            // In scroll mode: nod exits to normal
                            exitScrollMode()
                        }
                        dwellEnabled -> {
                            // In dwell mode: nod exits to normal
                            setDwellMode(false)
                        }
                        else -> {
                            // Normal mode: nod enters selection mode
                            enterSelectionMode()
                        }
                    }
                }
            }
            nodPeakDir = 0
        }
    }

    // ==============================
    //  Head shake
    // ==============================

    override fun onRawYaw(yaw: Float) {
        val now = System.currentTimeMillis()
        if (now < shakeCooldownUntil) return

        val dir = when {
            yaw > SHAKE_THRESHOLD -> +1
            yaw < -SHAKE_THRESHOLD -> -1
            else -> 0
        }
        if (dir == 0 || dir == shakeLastDirection) return

        // Ignore if too soon after last crossing (filters walking/vibration)
        if (shakeCrossings.isNotEmpty() && now - shakeCrossings.last() < SHAKE_MIN_INTERVAL_MS) return

        shakeLastDirection = dir
        shakeCrossings.add(now)
        shakeCrossings.removeAll { now - it > SHAKE_WINDOW_MS }
        if (shakeCrossings.size < SHAKE_2_CROSSINGS) return

        // Shake detected!
        shakeCrossings.clear()
        shakeLastDirection = 0
        shakeCooldownUntil = now + SHAKE_COOLDOWN_MS

        // Screen OFF + awake enabled → wake screen + go to apps
        if (screenOff && awakeEnabled) {
            DebugLog.i(TAG, "Shake x2 -> AWAKE")
            performAwake()
            return
        }

        // Cursor OFF → cursor ON
        if (!isActive) {
            DebugLog.i(TAG, "Shake x2 -> CURSOR ON")
            toggleActive()
            return
        }

        // Cursor ON + shake back → BACK
        if (shakeBackEnabled) {
            DebugLog.i(TAG, "Shake x2 -> BACK")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ==============================
    //  HeadTracker.Listener — cursor movement
    // ==============================

    override fun onHeadMove(deltaX: Float, deltaY: Float): HeadTracker.ClampResult {
        if (!isActive) {
            return HeadTracker.ClampResult(false, false)
        }

        // Selection mode: accumulate virtual position (cursor stays centered)
        if (selectionMode) {
            selectionVirtualX = (selectionVirtualX + deltaX).coerceIn(-300f, 300f)
            selectionVirtualY = (selectionVirtualY + deltaY).coerceIn(-300f, 300f)
            return HeadTracker.ClampResult(false, false)
        }

        if (scrollState == ScrollState.SCROLLING) {
            scrollVirtualX = (scrollVirtualX + deltaX).coerceIn(-300f, 300f)
            scrollVirtualY = (scrollVirtualY + deltaY).coerceIn(-300f, 300f)
            updateJoystickScroll()
            return HeadTracker.ClampResult(false, false)
        }

        val newX = (cursorX + deltaX).coerceIn(0f, screenWidth - 1f)
        val newY = (cursorY + deltaY).coerceIn(0f, screenHeight - 1f)
        val clampedX = newX != cursorX + deltaX
        val clampedY = newY != cursorY + deltaY
        if (deltaX != 0f || deltaY != 0f) lastCursorMoveTime = System.currentTimeMillis()
        cursorX = newX
        cursorY = newY
        cursorOverlay.updatePosition(cursorX, cursorY)

        return HeadTracker.ClampResult(clampedX, clampedY)
    }

    // ==============================
    //  Key event handling
    // ==============================

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (appInForeground) return false

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            if (isActive && event.action == KeyEvent.ACTION_UP) {
                return event.keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A
                )
            }
            return false
        }

        val now = System.currentTimeMillis()
        val keyCode = event.keyCode

        // Konami command: L L R R -> toggle
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (now - lastInputTime > COMMAND_TIMEOUT_MS) inputBuffer.clear()
            lastInputTime = now
            inputBuffer.add(keyCode)
            if (inputBuffer.size >= commandSequence.size &&
                inputBuffer.takeLast(commandSequence.size) == commandSequence) {
                inputBuffer.clear()
                toggleActive()
                return true
            }
            if (inputBuffer.size > 20) inputBuffer.removeAt(0)
        }

        // Click via center/enter
        if (isActive && keyCode in intArrayOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            performClick()
            resetDwell()
            return true
        }

        return false
    }

    // ==============================
    //  Click
    // ==============================

    private fun performClick() {
        DebugLog.d(TAG, "Click at (${cursorX.toInt()}, ${cursorY.toInt()})")
        val path = Path().apply { moveTo(cursorX, cursorY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { DebugLog.d(TAG, "Click OK") }
            override fun onCancelled(gestureDescription: GestureDescription) { DebugLog.w(TAG, "Click cancelled") }
        }, null)
        cursorOverlay.flashClick()
    }

    // ==============================
    //  Screen on/off — pause sensors when display off
    // ==============================

    private fun onScreenOff() {
        if (screenOff) return
        screenOff = true
        wasActiveBeforeScreenOff = isActive
        wasPassiveBeforeScreenOff = !isActive
        headTracker.stop()
        // Clear nod/shake state to prevent stale triggers on wake
        nodPeaks.clear()
        nodInPeak = false
        shakeCrossings.clear()
        shakeLastDirection = 0
        if (awakeEnabled) {
            // Always start passive for shake-to-awake detection
            headTracker.startPassive()
            DebugLog.i(TAG, "Screen OFF — passive sensor ON for awake")
        }
        dwellHandler.removeCallbacks(dwellTickRunnable)
        mainHandler.removeCallbacks(selectionTickRunnable)
        DebugLog.i(TAG, "Screen OFF — awake=$awakeEnabled (wasActive=$wasActiveBeforeScreenOff)")
    }

    private fun onScreenOn() {
        if (!screenOff) return
        screenOff = false
        if (wasActiveBeforeScreenOff) {
            headTracker.start()
            if (selectionMode) mainHandler.post(selectionTickRunnable)
            else if (dwellEnabled || autoFocusEnabled || scrollState != ScrollState.NORMAL) startDwellTicker()
            DebugLog.i(TAG, "Screen ON — sensors resumed (active)")
        } else if (wasPassiveBeforeScreenOff) {
            headTracker.startPassive()
            DebugLog.i(TAG, "Screen ON — sensors resumed (passive)")
        }
    }

    // ==============================
    //  Awake (shake to wake + go to apps)
    // ==============================

    private fun performAwake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "TouchMouse:awake"
        )
        wl.acquire(5000L)

        // Open app drawer after screen wakes
        mainHandler.postDelayed({
            val intent = Intent(this, com.kupstudio.touchmouse.apps.AppDrawerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            DebugLog.i(TAG, "Awake: opened AppDrawer")

            if (awakeAutoCursor) {
                mainHandler.postDelayed({
                    if (!isActive) toggleActive()
                    // Enter dwell mode
                    if (!dwellEnabled) setDwellMode(true)
                    DebugLog.i(TAG, "Awake: cursor ON + dwell mode")
                }, 500)
            }
        }, 800)
    }

    // ==============================
    //  Launcher page scroll
    // ==============================

    fun scrollLauncherPage(forward: Boolean) {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                DebugLog.w(TAG, "scrollLauncherPage: rootInActiveWindow is null")
                // Try via getWindows()
                val launcherRoot = windows
                    ?.flatMap { listOfNotNull(it.root) }
                    ?.firstOrNull { it.packageName == "com.rokid.os.sprite.launcher" }
                if (launcherRoot != null) {
                    doScrollNode(launcherRoot, forward)
                } else {
                    DebugLog.w(TAG, "scrollLauncherPage: launcher window not found")
                }
                return
            }

            if (root.packageName != "com.rokid.os.sprite.launcher") {
                DebugLog.w(TAG, "scrollLauncherPage: foreground is ${root.packageName}, not launcher")
                root.recycle()
                return
            }

            doScrollNode(root, forward)
        } catch (e: Exception) {
            DebugLog.e(TAG, "scrollLauncherPage error: ${e.message}")
        }
    }

    private fun doScrollNode(root: AccessibilityNodeInfo, forward: Boolean) {
        root.recycle()
        // Accessibility scroll doesn't actually work due to setUserInputEnabled(false)
        // Kept for potential future use
        DebugLog.w(TAG, "doScrollNode: not implemented (launcher restriction)")
    }

    // ==============================
    //  Broadcast
    // ==============================

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVE, isActive)
        }
        sendBroadcast(intent)
    }
}
