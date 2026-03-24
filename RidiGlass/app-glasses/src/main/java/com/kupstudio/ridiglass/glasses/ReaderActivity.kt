package com.kupstudio.ridiglass.glasses

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kupstudio.ridiglass.common.BleProtocol
import com.kupstudio.ridiglass.glasses.ble.BleClientManager

/**
 * Main reader display for Rokid Glasses.
 *
 * Design principles for battery & readability:
 * - Pure black background (= transparent on waveguide, pixels OFF)
 * - White text only (maximum contrast on green monochrome display)
 * - No animations, no gradients, no unnecessary redraws
 * - Screen updates ONLY when new text arrives via BLE
 * - Adjustable font size via touchpad (saved to preferences)
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "ridiglass_reader"
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_BG_MODE = "bg_mode" // 0 = transparent (black), 1 = green
        private const val BG_MODE_TRANSPARENT = 0
        private const val BG_MODE_GREEN = 1
        private const val DEFAULT_FONT_SIZE = 28f
        private const val MIN_FONT_SIZE = 16f
        private const val MAX_FONT_SIZE = 56f
        private const val FONT_SIZE_STEP = 2f
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var textView: TextView
    private lateinit var statusView: TextView
    private lateinit var fontSizeIndicator: TextView
    private lateinit var bleClient: BleClientManager
    private lateinit var prefs: SharedPreferences

    private var currentFontSize = DEFAULT_FONT_SIZE
    private var bgMode = BG_MODE_TRANSPARENT
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentFontSize = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        bgMode = prefs.getInt(KEY_BG_MODE, BG_MODE_TRANSPARENT)

        rootLayout = findViewById(R.id.root_layout)
        textView = findViewById(R.id.reader_text)
        statusView = findViewById(R.id.status_text)
        fontSizeIndicator = findViewById(R.id.font_size_indicator)
        volumeIndicator = findViewById(R.id.volume_indicator)

        applyFontSize()
        applyBgMode()
        hideSystemUI()
        acquireWakeLock()

        bleClient = BleClientManager(this)

        bleClient.onTextReceived = { text ->
            runOnUiThread {
                textView.text = text
                statusView.visibility = View.GONE
            }
        }

        bleClient.onCommandReceived = { cmd, payload ->
            runOnUiThread {
                when (cmd) {
                    BleProtocol.CMD_CLEAR -> {
                        textView.text = ""
                        statusView.visibility = View.VISIBLE
                    }
                    BleProtocol.CMD_FONT_SIZE_UP -> adjustFontSize(FONT_SIZE_STEP)
                    BleProtocol.CMD_FONT_SIZE_DOWN -> adjustFontSize(-FONT_SIZE_STEP)
                    BleProtocol.CMD_SET_FONT_SIZE -> {
                        if (payload.isNotEmpty()) {
                            currentFontSize = payload[0].toFloat().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                            applyFontSize()
                        }
                    }
                    BleProtocol.CMD_VOLUME_LEVEL -> {
                        if (payload.isNotEmpty()) {
                            val level = payload[0].toInt() and 0xFF
                            showVolumeIndicator(level)
                        }
                    }
                    BleProtocol.CMD_BG_TOGGLE -> toggleBgMode()
                }
            }
        }

        bleClient.onConnectionChanged = { connected ->
            runOnUiThread {
                statusView.text = if (connected)
                    getString(R.string.waiting)
                else
                    getString(R.string.status_scanning)
            }
        }

        requestPermissionsAndConnect()
    }

    override fun onDestroy() {
        wakeLock?.release()
        bleClient.disconnect()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "RidiGlass:Reader"
        )
        wakeLock?.acquire()
    }

    /**
     * Handle Rokid Glasses touchpad input:
     * - DPAD_UP: increase font size
     * - DPAD_DOWN: decrease font size
     * - DPAD_RIGHT / Volume Up: volume up (sent to phone)
     * - DPAD_LEFT / Volume Down: volume down (sent to phone)
     * - DPAD_CENTER / Enter long press: toggle background mode (transparent / green)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                adjustFontSize(FONT_SIZE_STEP)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                adjustFontSize(-FONT_SIZE_STEP)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                bleClient.sendControl(BleProtocol.CTL_VOLUME_UP)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                bleClient.sendControl(BleProtocol.CTL_VOLUME_DOWN)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }


    private fun adjustFontSize(delta: Float) {
        currentFontSize = (currentFontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        applyFontSize()
        saveFontSize()
        showFontSizeIndicator()
    }

    private fun applyFontSize() {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize)
    }

    private fun saveFontSize() {
        prefs.edit().putFloat(KEY_FONT_SIZE, currentFontSize).apply()
    }

    private fun toggleBgMode() {
        bgMode = if (bgMode == BG_MODE_TRANSPARENT) BG_MODE_GREEN else BG_MODE_TRANSPARENT
        applyBgMode()
        prefs.edit().putInt(KEY_BG_MODE, bgMode).apply()
        showBgModeIndicator()
    }

    private fun applyBgMode() {
        if (bgMode == BG_MODE_GREEN) {
            rootLayout.setBackgroundColor(Color.parseColor("#003300"))
            textView.setTextColor(Color.WHITE)
        } else {
            rootLayout.setBackgroundColor(Color.BLACK)
            textView.setTextColor(Color.WHITE)
        }
    }

    private fun showBgModeIndicator() {
        val label = if (bgMode == BG_MODE_GREEN) "BG: GREEN" else "BG: OFF"
        fontSizeIndicator.text = label
        fontSizeIndicator.visibility = View.VISIBLE
        fontSizeIndicator.removeCallbacks(hideFontSizeRunnable)
        fontSizeIndicator.postDelayed(hideFontSizeRunnable, 1500)
    }

    private fun showFontSizeIndicator() {
        fontSizeIndicator.text = "${currentFontSize.toInt()}sp"
        fontSizeIndicator.visibility = View.VISIBLE
        fontSizeIndicator.removeCallbacks(hideFontSizeRunnable)
        fontSizeIndicator.postDelayed(hideFontSizeRunnable, 1500)
    }

    private val hideFontSizeRunnable = Runnable {
        fontSizeIndicator.visibility = View.GONE
    }

    private lateinit var volumeIndicator: TextView

    private fun showVolumeIndicator(level: Int) {
        volumeIndicator.text = "VOL $level%"
        volumeIndicator.visibility = View.VISIBLE
        volumeIndicator.removeCallbacks(hideVolumeRunnable)
        volumeIndicator.postDelayed(hideVolumeRunnable, 1500)
    }

    private val hideVolumeRunnable = Runnable {
        volumeIndicator.visibility = View.GONE
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun requestPermissionsAndConnect() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 200)
        } else {
            bleClient.startScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            bleClient.startScan()
        }
    }
}
