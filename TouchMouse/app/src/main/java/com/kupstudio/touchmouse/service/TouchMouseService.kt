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
import android.os.PowerManager
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
        const val PREF_DWELL_DURATION = "dwell_duration"
        const val PREF_DWELL_RADIUS = "dwell_radius"
        const val PREF_DWELL_DELAY = "dwell_delay"
        const val PREF_SHAKE_BACK_ENABLED = "shake_back_enabled"
        const val PREF_CIRCLE_TOGGLE_ENABLED = "circle_toggle_enabled"

        private const val DEFAULT_SENSITIVITY_X = 150f
        private const val DEFAULT_SENSITIVITY_Y = 3500f
        private const val DEFAULT_DWELL_DURATION = 3000L
        private const val DEFAULT_DWELL_RADIUS = 50f
        private const val DEFAULT_DWELL_DELAY = 1000L

        // Head shake → back
        private const val SHAKE_THRESHOLD = 1.5f
        private const val SHAKE_WINDOW_MS = 1200L
        private const val SHAKE_2_CROSSINGS = 4
        private const val SHAKE_COOLDOWN_MS = 600L

        // Nod detection (pitch axis) — peak-based
        private const val NOD_PEAK_THRESHOLD = 0.5f   // rad/s to register a peak
        private const val NOD_RETURN_THRESHOLD = 0.15f // must drop below this to confirm peak ended
        private const val NOD_WINDOW_MS = 2000L        // time window for 2 peaks
        private const val NOD_COOLDOWN_MS = 800L

        // Joystick scroll (SCROLLING state) — virtual displacement based
        private const val SCROLL_DEAD_X = 80f          // px — horizontal dead zone
        private const val SCROLL_DEAD_Y = 120f         // px — vertical dead zone (taller screen)
        private const val SCROLL_REPEAT_MS = 250L
        private const val SCROLL_MIN_PX = 60f
        private const val SCROLL_ACCEL_PER_SEC = 80f
        private const val SCROLL_MAX_PX = 300f
        private const val SCROLL_GESTURE_MS = 120L

        // Konami-style command: LEFT LEFT RIGHT RIGHT
        private const val COMMAND_TIMEOUT_MS = 2000L

        // Circle gesture → toggle cursor
        private const val CIRCLE_SAMPLE_MS = 50L            // cursor sampling interval
        private const val CIRCLE_ANGLE_THRESHOLD = 5.5f     // ~315° in radians (allow slack)
        private const val CIRCLE_MIN_SPAN = 120f            // min diameter of circle in px
        private const val CIRCLE_MAX_TIME_MS = 3000L        // max time for a single circle
        private const val CIRCLE_2X_WINDOW_MS = 6000L       // window to complete 2 circles
        private const val CIRCLE_COOLDOWN_MS = 2000L        // cooldown after toggle

        var instance: TouchMouseService? = null
            private set
    }

    private lateinit var headTracker: HeadTracker
    private lateinit var cursorOverlay: CursorOverlayManager
    private lateinit var helpOverlay: HelpOverlayManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    private var cursorX = 240f
    private var cursorY = 320f
    private var screenWidth = 480
    private var screenHeight = 640
    private var isActive = false

    // Dwell — toggled by nod-down x2
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
            // Dwell runs when dwellEnabled OR in scroll mode
            if (!dwellEnabled && scrollState == ScrollState.NORMAL) return
            updateDwellProgress()
            dwellHandler.postDelayed(this, 50)
        }
    }

    // Circle toggle (passive gyro when cursor OFF)
    var circleToggleEnabled = true
        private set
    private var circleVirtualX = 240f
    private var circleVirtualY = 320f

    // Head shake → back
    var shakeBackEnabled = false
        private set
    private val shakeCrossings = mutableListOf<Long>()
    private var shakeLastDirection = 0
    private var shakeCooldownUntil = 0L

    // Nod detection — peak-based
    private data class NodPeak(val time: Long, val dir: Int)  // dir: +1=down, -1=up
    private val nodPeaks = mutableListOf<NodPeak>()
    private var nodInPeak = false       // currently above threshold
    private var nodPeakDir = 0          // direction of current peak
    private var nodCooldownUntil = 0L

    // Scroll mode
    enum class ScrollState { NORMAL, SCROLL_READY, SCROLLING }
    var scrollState = ScrollState.NORMAL
        private set
    private var dwellWasEnabled = false
    // Joystick scroll state (SCROLLING) — virtual displacement based
    private var scrollVirtualX = 0f
    private var scrollVirtualY = 0f
    private var scrollDirX = 0f
    private var scrollDirY = 0f
    private var scrollMaxX = 0f
    private var scrollMaxY = 0f
    private var scrollHoldStart = 0L
    private var scrollLastDispatch = 0L
    private var scrollNeutralTime = 0L    // when neutral was entered — lock new dir for a bit
    private var scrollEverSwiped = false  // did at least one swipe happen
    private var scrollAnchorTime = 0L     // when SCROLLING started

    // Circle gesture detection
    private data class CPoint(val x: Float, val y: Float, val time: Long)
    private val circlePoints = mutableListOf<CPoint>()
    private var circleAccAngle = 0f
    private val circleCompletions = mutableListOf<Long>()
    private var circleCooldownUntil = 0L

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
            if (intent.action == ACTION_TOGGLE) toggleActive()
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
        helpOverlay = HelpOverlayManager(this)

        dwellDuration = prefs.getLong(PREF_DWELL_DURATION, DEFAULT_DWELL_DURATION)
        dwellRadius = prefs.getFloat(PREF_DWELL_RADIUS, DEFAULT_DWELL_RADIUS)
        dwellDelay = prefs.getLong(PREF_DWELL_DELAY, DEFAULT_DWELL_DELAY)
        shakeBackEnabled = prefs.getBoolean(PREF_SHAKE_BACK_ENABLED, false)
        circleToggleEnabled = prefs.getBoolean(PREF_CIRCLE_TOGGLE_ENABLED, true)
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

        // Start passive gyro for circle gesture if enabled and cursor not active
        if (circleToggleEnabled && !isActive) {
            circleVirtualX = screenWidth / 2f
            circleVirtualY = screenHeight / 2f
            headTracker.startPassive()
        }

        broadcastStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        headTracker.stop()  // Stop regardless (active or passive)
        if (isActive) {
            cursorOverlay.hide()
            helpOverlay.hide()
        }
        releaseWakeLock()
        try { unregisterReceiver(toggleReceiver) } catch (_: Exception) {}
        instance = null
        DebugLog.i(TAG, "Service destroyed")
    }

    fun toggleActive() {
        isActive = !isActive
        resetCircleDetector()
        if (isActive) {
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            cursorOverlay.show(cursorX, cursorY)
            // Switch from passive (if running) to full-rate tracking
            headTracker.stop()
            headTracker.start()
            resetDwell()
            startDwellTicker()
            acquireWakeLock()
            DebugLog.i(TAG, "HEAD MOUSE ON")
        } else {
            headTracker.stop()
            dwellHandler.removeCallbacks(dwellTickRunnable)
            exitScrollMode()
            setDwellMode(false)
            cursorOverlay.setDwellProgress(0f)
            cursorOverlay.hide()
            helpOverlay.hide()
            releaseWakeLock()
            // Start passive tracking for circle gesture if enabled
            if (circleToggleEnabled) {
                circleVirtualX = screenWidth / 2f
                circleVirtualY = screenHeight / 2f
                headTracker.startPassive()
            }
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

    fun setCircleToggleEnabled(enabled: Boolean) {
        circleToggleEnabled = enabled
        prefs.edit().putBoolean(PREF_CIRCLE_TOGGLE_ENABLED, enabled).apply()
        // Start/stop passive tracking based on new setting and current state
        if (!isActive) {
            if (enabled) {
                circleVirtualX = screenWidth / 2f
                circleVirtualY = screenHeight / 2f
                resetCircleDetector()
                headTracker.startPassive()
            } else {
                headTracker.stop()
            }
        }
    }

    // ── Sync overlay icons to current state ──

    private fun syncOverlay() {
        cursorOverlay.setDwellMode(dwellEnabled)
        cursorOverlay.setScrollMode(scrollState != ScrollState.NORMAL)
        cursorOverlay.setDwellProgress(0f)

        when {
            scrollState == ScrollState.SCROLLING ->
                helpOverlay.show("SCROLLING", "tilt: scroll | stop + dwell: release anchor")
            scrollState == ScrollState.SCROLL_READY ->
                helpOverlay.show("SCROLL MODE", "move to target | dwell: anchor | nod x2: off")
            dwellEnabled ->
                helpOverlay.show("DWELL MODE", "nod x2: off")
            else ->
                helpOverlay.hide()
        }
    }

    // ── Dwell mode (nod toggle) ──

    private fun setDwellMode(on: Boolean) {
        dwellEnabled = on
        if (on) {
            resetDwell()
            startDwellTicker()
        } else {
            if (scrollState == ScrollState.NORMAL) {
                dwellHandler.removeCallbacks(dwellTickRunnable)
            }
        }
        syncOverlay()
        DebugLog.i(TAG, "Dwell mode ${if (on) "ON" else "OFF"}")
        broadcastStatus()
    }

    private fun startDwellTicker() {
        dwellHandler.removeCallbacks(dwellTickRunnable)
        dwellHandler.post(dwellTickRunnable)
    }

    // ── Scroll mode (nod toggle) ──

    private fun enterScrollReady() {
        // Save and disable dwell mode — scroll has its own dwell logic
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
        // Restore dwell mode if it was on before scroll
        if (dwellWasEnabled) {
            dwellEnabled = true
            resetDwell()
            startDwellTicker()
        }
        dwellWasEnabled = false
        syncOverlay()
        DebugLog.i(TAG, "Scroll OFF")
    }

    // ── Nod detection — distinguishes nod-down vs nod-up by first crossing direction ──

    override fun onRawPitch(pitch: Float) {
        if (!isActive) return

        val now = System.currentTimeMillis()
        if (now < nodCooldownUntil) return
        // Block nod during SCROLLING (exit via dwell). Allow during SCROLL_READY (exit via nod).
        if (scrollState == ScrollState.SCROLLING) return

        val absPitch = kotlin.math.abs(pitch)

        if (!nodInPeak) {
            // Waiting for a peak to start
            if (absPitch > NOD_PEAK_THRESHOLD) {
                nodInPeak = true
                nodPeakDir = if (pitch > 0) +1 else -1
            }
        } else {
            // In a peak — wait for it to end (velocity drops back down)
            if (absPitch < NOD_RETURN_THRESHOLD) {
                // Peak ended — record it
                nodInPeak = false
                nodPeaks.removeAll { now - it.time > NOD_WINDOW_MS }
                nodPeaks.add(NodPeak(now, nodPeakDir))
                DebugLog.d(TAG, "Nod peak #${nodPeaks.size} dir=${nodPeakDir}")

                // Need 2 peaks with alternating directions = 1 nod cycle
                // 4 peaks with alternating directions = 2 nod cycles
                if (nodPeaks.size >= 4) {
                    val last4 = nodPeaks.takeLast(4)
                    // Check alternating: d0 != d1, d1 != d2, d2 != d3
                    val alternating = (0 until 3).all { last4[it].dir != last4[it + 1].dir }
                    if (alternating) {
                        // First peak direction determines nod type
                        val firstDir = last4[0].dir
                        nodPeaks.clear()
                        nodCooldownUntil = now + NOD_COOLDOWN_MS

                        DebugLog.i(TAG, "Nod x2 detected, firstDir=$firstDir")
                        // SCROLL_READY: any nod x2 exits, regardless of direction
                        if (scrollState == ScrollState.SCROLL_READY) {
                            exitScrollMode()
                        } else if (firstDir == +1) {
                            // Down-first nod → toggle scroll (blocked if dwell mode on)
                            if (dwellEnabled) {
                                DebugLog.d(TAG, "Scroll blocked — dwell mode active")
                            } else if (scrollState == ScrollState.NORMAL) {
                                enterScrollReady()
                            } else {
                                exitScrollMode()
                            }
                        } else {
                            // Up-first nod → toggle dwell (blocked if scroll mode on)
                            if (scrollState != ScrollState.NORMAL) {
                                DebugLog.d(TAG, "Dwell blocked — scroll mode active")
                            } else {
                                setDwellMode(!dwellEnabled)
                            }
                        }
                    }
                }
                nodPeakDir = 0
            }
        }
    }

    // ── Joystick scroll for SCROLLING state ──
    // Virtual cursor displacement from anchor determines scroll direction

    // Virtual displacement joystick with max-distance tracking.
    // Direction change requires returning past the max distance reached → forces neutral first.

    private fun updateJoystickScroll() {
        val now = System.currentTimeMillis()

        // Normalized displacement: how far in each axis relative to its dead zone
        val normX = scrollVirtualX / SCROLL_DEAD_X
        val normY = scrollVirtualY / SCROLL_DEAD_Y
        val absNX = kotlin.math.abs(normX)
        val absNY = kotlin.math.abs(normY)

        // Track max displacement per axis (in normalized units)
        // Cap max at 2.0 normalized units — prevents huge return distances from long holds
        if (scrollDirX != 0f) scrollMaxX = maxOf(scrollMaxX, absNX).coerceAtMost(2.0f)
        if (scrollDirY != 0f) scrollMaxY = maxOf(scrollMaxY, absNY).coerceAtMost(2.0f)

        // Currently scrolling — check if we should go neutral
        if (scrollDirX != 0f || scrollDirY != 0f) {
            // Neutral condition: returned past max distance in the dominant axis
            val shouldNeutral = if (scrollDirX != 0f) {
                // Horizontal: must return to within 15% of origin on opposite side
                val returnThreshold = -scrollMaxX * 0.15f
                (scrollDirX > 0 && normX < returnThreshold) || (scrollDirX < 0 && normX > -returnThreshold)
            } else {
                val returnThreshold = -scrollMaxY * 0.15f
                (scrollDirY > 0 && normY < returnThreshold) || (scrollDirY < 0 && normY > -returnThreshold)
            }

            if (shouldNeutral) {
                scrollDirX = 0f
                scrollDirY = 0f
                scrollMaxX = 0f
                scrollMaxY = 0f
                // Reset virtual position to 0 so new direction starts fresh
                scrollVirtualX = 0f
                scrollVirtualY = 0f
                scrollNeutralTime = now
                cursorOverlay.setScrollDir(0f, 0f)
                DebugLog.d(TAG, "Scroll neutral (returned past max)")
            } else {
                // Still in active scroll direction — dispatch swipes
                if (now - scrollLastDispatch >= SCROLL_REPEAT_MS) {
                    val holdSec = (now - scrollHoldStart) / 1000f
                    val dist = (SCROLL_MIN_PX + SCROLL_ACCEL_PER_SEC * holdSec)
                        .coerceAtMost(SCROLL_MAX_PX)
                    dispatchSwipe(-scrollDirX * dist, -scrollDirY * dist)
                    scrollLastDispatch = now
                    scrollEverSwiped = true
                    resetDwell()
                }
            }
            return
        }

        // Currently neutral — check if we should activate a direction
        // Must wait 500ms after going neutral (head still returning to center)
        if (now - scrollNeutralTime < 500L) {
            // Still in cooldown — keep resetting virtual position to prevent accumulation
            scrollVirtualX = 0f
            scrollVirtualY = 0f
            return
        }

        val maxNorm = maxOf(absNX, absNY)
        if (maxNorm >= 1.0f) {
            // Past dead zone — commit direction
            if (absNX > absNY) {
                scrollDirX = if (scrollVirtualX > 0) 1f else -1f
                scrollDirY = 0f
            } else {
                scrollDirX = 0f
                scrollDirY = if (scrollVirtualY > 0) 1f else -1f
            }
            scrollMaxX = absNX
            scrollMaxY = absNY
            scrollHoldStart = now
            scrollLastDispatch = 0L
            cursorOverlay.setScrollDir(scrollDirX, scrollDirY)
            resetDwell()
            DebugLog.d(TAG, "Scroll dir=(${scrollDirX},${scrollDirY})")
        }
    }

    // ── Head shake → back ──

    override fun onRawYaw(yaw: Float) {
        if (!isActive || !shakeBackEnabled) return

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

            if (shakeCrossings.size >= SHAKE_2_CROSSINGS) {
                shakeCrossings.clear()
                shakeLastDirection = 0
                shakeCooldownUntil = now + SHAKE_COOLDOWN_MS
                DebugLog.i(TAG, "Shake x2 → BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    // ── Dwell logic ──

    private fun resetDwell() {
        dwellAnchorX = cursorX
        dwellAnchorY = cursorY
        dwellStartTime = System.currentTimeMillis()
        dwellFired = false
        cursorOverlay.setDwellProgress(0f)
    }

    private fun isDwellActive(): Boolean {
        // Dwell runs for: dwell mode, scroll-ready (anchor), or scrolling (exit via dwell when neutral)
        return dwellEnabled || scrollState != ScrollState.NORMAL
    }

    private fun updateDwellProgress() {
        if (!isDwellActive() || !isActive || dwellFired) return

        val now = System.currentTimeMillis()
        if (now < dwellCooldownUntil) return

        val dx = cursorX - dwellAnchorX
        val dy = cursorY - dwellAnchorY
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist > dwellRadius) {
            resetDwell()
            return
        }

        // SCROLLING: dwell only runs when neutral AND eligible to exit
        if (scrollState == ScrollState.SCROLLING) {
            // Not neutral (actively scrolling) → no dwell
            if (scrollDirX != 0f || scrollDirY != 0f) return
            // Neutral but not eligible yet (no swipe done AND < 3s since anchor)
            val eligibleToExit = scrollEverSwiped || (now - scrollAnchorTime >= 3000L)
            if (!eligibleToExit) return
        }

        val elapsed = now - dwellStartTime
        if (elapsed < dwellDelay) return

        val activeElapsed = elapsed - dwellDelay
        val progress = (activeElapsed.toFloat() / dwellDuration).coerceIn(0f, 1f)
        cursorOverlay.setDwellProgress(progress)

        if (activeElapsed >= dwellDuration) {
            dwellFired = true
            dwellCooldownUntil = now + dwellDelay

            when (scrollState) {
                ScrollState.SCROLL_READY -> {
                    DebugLog.i(TAG, "Dwell → anchor at (${cursorX.toInt()}, ${cursorY.toInt()})")
                    enterScrolling()
                    mainHandler.postDelayed({ resetDwell() }, dwellDelay)
                }
                ScrollState.SCROLLING -> {
                    DebugLog.i(TAG, "Dwell → exit scroll (anchor released)")
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

    // ── HeadTracker.Listener ──

    override fun onHeadMove(deltaX: Float, deltaY: Float): HeadTracker.ClampResult {
        if (!isActive) {
            // Passive mode: track virtual position for circle detection only
            if (circleToggleEnabled) {
                circleVirtualX = (circleVirtualX + deltaX).coerceIn(0f, screenWidth - 1f)
                circleVirtualY = (circleVirtualY + deltaY).coerceIn(0f, screenHeight - 1f)
                feedCircleDetector(circleVirtualX, circleVirtualY)
            }
            return HeadTracker.ClampResult(false, false)
        }

        // SCROLL_READY: cursor moves freely, dwell anchors
        // (falls through to normal cursor movement below)

        // SCROLLING: cursor locked, track virtual position for joystick
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

        cursorX = newX
        cursorY = newY
        cursorOverlay.updatePosition(cursorX, cursorY)

        // Feed circle gesture detector (only in normal mode)
        if (scrollState == ScrollState.NORMAL) {
            feedCircleDetector(cursorX, cursorY)
        }

        return HeadTracker.ClampResult(clampedX, clampedY)
    }

    private fun dispatchSwipe(dx: Float, dy: Float) {
        // Offset start 60px in swipe direction so touch-down avoids tapping anchor element
        val offsetX = if (dx != 0f) dx / kotlin.math.abs(dx) * 60f else 0f
        val offsetY = if (dy != 0f) dy / kotlin.math.abs(dy) * 60f else 0f
        val sx = (cursorX + offsetX).coerceIn(20f, screenWidth - 20f)
        val sy = (cursorY + offsetY).coerceIn(20f, screenHeight - 20f)
        val endX = (sx + dx).coerceIn(10f, screenWidth - 10f)
        val endY = (sy + dy).coerceIn(10f, screenHeight - 10f)

        val dist = Math.sqrt(((endX - sx) * (endX - sx) + (endY - sy) * (endY - sy)).toDouble())
        if (dist < 10.0) return

        DebugLog.d(TAG, "Swipe (${sx.toInt()},${sy.toInt()})→(${endX.toInt()},${endY.toInt()}) dist=${dist.toInt()}")

        val path = Path()
        path.moveTo(sx, sy)
        path.lineTo(endX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_GESTURE_MS))
            .build()

        dispatchGesture(gesture, null, null)
    }

    // ── Circle gesture detection ──

    private fun feedCircleDetector(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now < circleCooldownUntil) return

        // Sample at intervals
        if (circlePoints.isNotEmpty() && now - circlePoints.last().time < CIRCLE_SAMPLE_MS) return

        circlePoints.add(CPoint(x, y, now))

        // Expire old points — single circle must complete within max time
        if (circlePoints.size > 1) {
            val oldest = circlePoints.first().time
            if (now - oldest > CIRCLE_MAX_TIME_MS) {
                // Drop oldest point and reduce accumulated angle proportionally
                circlePoints.removeAt(0)
                // Conservative: reset angle if too old to avoid drift
                if (circlePoints.size < 3) circleAccAngle = 0f
            }
        }

        if (circlePoints.size < 3) return

        // Compute turning angle from last 3 points
        val p0 = circlePoints[circlePoints.size - 3]
        val p1 = circlePoints[circlePoints.size - 2]
        val p2 = circlePoints[circlePoints.size - 1]

        val v1x = p1.x - p0.x
        val v1y = p1.y - p0.y
        val v2x = p2.x - p1.x
        val v2y = p2.y - p1.y

        val len1 = Math.sqrt((v1x * v1x + v1y * v1y).toDouble()).toFloat()
        val len2 = Math.sqrt((v2x * v2x + v2y * v2y).toDouble()).toFloat()

        if (len1 < 2f || len2 < 2f) return  // skip tiny movements

        val cross = v1x * v2y - v1y * v2x
        val dot = v1x * v2x + v1y * v2y
        val angle = Math.atan2(cross.toDouble(), dot.toDouble()).toFloat()

        circleAccAngle += angle

        // Check if we completed a circle
        if (kotlin.math.abs(circleAccAngle) >= CIRCLE_ANGLE_THRESHOLD) {
            val span = computeCircleSpan()
            if (span >= CIRCLE_MIN_SPAN) {
                circleCompletions.add(now)
                circleCompletions.removeAll { now - it > CIRCLE_2X_WINDOW_MS }
                DebugLog.i(TAG, "Circle #${circleCompletions.size} detected (span=${span.toInt()}px)")

                if (circleCompletions.size >= 2) {
                    circleCompletions.clear()
                    resetCircleDetector()
                    circleCooldownUntil = now + CIRCLE_COOLDOWN_MS
                    DebugLog.i(TAG, "Circle x2 → TOGGLE")
                    toggleActive()
                    return
                }
            }
            // Reset for next circle
            circleAccAngle = 0f
            circlePoints.clear()
        }
    }

    private fun computeCircleSpan(): Float {
        if (circlePoints.size < 2) return 0f
        var maxDistSq = 0f
        val step = maxOf(1, circlePoints.size / 12)
        for (i in circlePoints.indices step step) {
            for (j in i + step until circlePoints.size step step) {
                val dx = circlePoints[i].x - circlePoints[j].x
                val dy = circlePoints[i].y - circlePoints[j].y
                val dSq = dx * dx + dy * dy
                if (dSq > maxDistSq) maxDistSq = dSq
            }
        }
        return Math.sqrt(maxDistSq.toDouble()).toFloat()
    }

    private fun resetCircleDetector() {
        circlePoints.clear()
        circleAccAngle = 0f
    }

    // ── Key event handling ──

    var appInForeground = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
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

        val now = System.currentTimeMillis()
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (now - lastInputTime > COMMAND_TIMEOUT_MS) inputBuffer.clear()
            lastInputTime = now
            inputBuffer.add(keyCode)

            if (inputBuffer.size >= commandSequence.size) {
                val tail = inputBuffer.takeLast(commandSequence.size)
                if (tail == commandSequence) {
                    inputBuffer.clear()
                    toggleActive()
                    return true
                }
            }
            if (inputBuffer.size > 20) inputBuffer.removeAt(0)
        }

        if (isActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    performClick()
                    resetDwell()
                    return true
                }
            }
        }

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

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GazeMou:HeadMouse"
            )
        }
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVE, isActive)
        }
        sendBroadcast(intent)
    }
}
