package com.kupstudio.ridiglass.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kupstudio.ridiglass.common.BleProtocol
import com.kupstudio.ridiglass.phone.ble.BleServerService

class MainActivity : AppCompatActivity() {

    private var bleService: BleServerService? = null
    private var bound = false
    private var bgIsGreen = false

    private lateinit var statusText: TextView
    private lateinit var guideText: TextView
    private lateinit var lastTextView: TextView
    private lateinit var toggleButton: Button
    private lateinit var ttsSettingsButton: Button
    private lateinit var btnFontUp: Button
    private lateinit var btnFontDown: Button
    private lateinit var btnVolUp: Button
    private lateinit var btnVolDown: Button
    private lateinit var btnBgToggle: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BleServerService.LocalBinder).service
            bound = true

            bleService?.onConnectionChanged = { connected ->
                runOnUiThread {
                    statusText.text = if (connected)
                        getString(R.string.status_connected)
                    else
                        getString(R.string.status_advertising)
                }
            }

            bleService?.onTextSent = { text ->
                runOnUiThread {
                    lastTextView.text = text
                }
            }

            statusText.text = getString(R.string.status_advertising)
            toggleButton.text = getString(R.string.btn_stop)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        guideText = findViewById(R.id.guide_text)
        lastTextView = findViewById(R.id.last_text)
        toggleButton = findViewById(R.id.toggle_button)
        ttsSettingsButton = findViewById(R.id.tts_settings_button)
        btnFontUp = findViewById(R.id.btn_font_up)
        btnFontDown = findViewById(R.id.btn_font_down)
        btnVolUp = findViewById(R.id.btn_vol_up)
        btnVolDown = findViewById(R.id.btn_vol_down)
        btnBgToggle = findViewById(R.id.btn_bg_toggle)

        toggleButton.setOnClickListener {
            if (bound) {
                stopBleServer()
            } else {
                requestPermissionsAndStart()
            }
        }

        ttsSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Glasses controls
        btnFontUp.setOnClickListener {
            bleService?.sendCommand(BleProtocol.CMD_FONT_SIZE_UP)
        }

        btnFontDown.setOnClickListener {
            bleService?.sendCommand(BleProtocol.CMD_FONT_SIZE_DOWN)
        }

        btnVolUp.setOnClickListener {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }

        btnVolDown.setOnClickListener {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }

        btnBgToggle.setOnClickListener {
            bleService?.sendCommand(BleProtocol.CMD_BG_TOGGLE)
            bgIsGreen = !bgIsGreen
            btnBgToggle.text = if (bgIsGreen) "안경 배경: 초록" else "안경 배경: 투명"
        }

        requestPermissionsAndStart()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            startBleServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleServer()
        }
    }

    private fun startBleServer() {
        val intent = Intent(this, BleServerService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopBleServer() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, BleServerService::class.java))
        statusText.text = getString(R.string.status_disconnected)
        toggleButton.text = getString(R.string.btn_start)
        bleService = null
    }
}
