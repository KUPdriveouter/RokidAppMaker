package com.kupstudio.touchmouse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.kupstudio.touchmouse.databinding.ActivityMainBinding
import com.kupstudio.touchmouse.service.TouchMouseService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Depth 1: navigate items, Depth 2: edit selected item
    private var depth = 1

    private enum class Item { SERVICE, TOGGLE, SENS_X, SENS_Y }
    private val items = Item.entries.toList()
    private var selectedIdx = 0

    private var wasActiveBeforeEdit = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TouchMouseService.ACTION_STATUS_CHANGED) {
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerReceiver(
            statusReceiver,
            IntentFilter(TouchMouseService.ACTION_STATUS_CHANGED),
            RECEIVER_NOT_EXPORTED
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        TouchMouseService.instance?.appInForeground = true
        render()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onPause() {
        super.onPause()
        TouchMouseService.instance?.appInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        TouchMouseService.instance?.appInForeground = false
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (depth == 1) {
                    selectedIdx = (selectedIdx - 1 + items.size) % items.size
                } else {
                    adjustDepth2(-1)
                }
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (depth == 1) {
                    selectedIdx = (selectedIdx + 1) % items.size
                } else {
                    adjustDepth2(1)
                }
                render()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (depth == 2) {
                    exitDepth2()
                } else {
                    // Depth 1
                    when (items[selectedIdx]) {
                        Item.SERVICE -> openAccessibilitySettings()
                        Item.TOGGLE -> {
                            TouchMouseService.instance?.toggleActive()
                            render()
                        }
                        else -> enterDepth2()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (depth == 2) {
                    exitDepth2()
                    return true
                }
                // depth 1: normal back = exit app
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun enterDepth2() {
        depth = 2
        val item = items[selectedIdx]
        // Show pointer for sensitivity preview
        if (item == Item.SENS_X || item == Item.SENS_Y) {
            val svc = TouchMouseService.instance
            wasActiveBeforeEdit = svc?.isActive() == true
            if (!wasActiveBeforeEdit) {
                svc?.toggleActive()
            }
        }
        render()
    }

    private fun exitDepth2() {
        val item = items[selectedIdx]
        // Restore pointer state if it was off
        if (item == Item.SENS_X || item == Item.SENS_Y) {
            if (!wasActiveBeforeEdit) {
                TouchMouseService.instance?.let {
                    if (it.isActive()) it.toggleActive()
                }
            }
        }
        depth = 1
        render()
    }

    private fun adjustDepth2(dir: Int) {
        val prefs = getSharedPreferences(TouchMouseService.PREFS_NAME, MODE_PRIVATE)
        when (items[selectedIdx]) {
            Item.SERVICE -> {} // no adjustment
            Item.TOGGLE -> {
                // Left/right toggles on/off
                TouchMouseService.instance?.toggleActive()
            }
            Item.SENS_X -> {
                val cur = prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_X, 150f)
                val new = (cur + dir * 50f).coerceIn(50f, 2000f)
                prefs.edit().putFloat(TouchMouseService.PREF_SENSITIVITY_X, new).apply()
                TouchMouseService.instance?.updateSensitivityX(new)
            }
            Item.SENS_Y -> {
                val cur = prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_Y, 3500f)
                val new = (cur + dir * 100f).coerceIn(100f, 8000f)
                prefs.edit().putFloat(TouchMouseService.PREF_SENSITIVITY_Y, new).apply()
                TouchMouseService.instance?.updateSensitivityY(new)
            }
        }
    }

    private fun render() {
        val enabled = isAccessibilityServiceEnabled()
        val active = TouchMouseService.instance?.isActive() == true
        val prefs = getSharedPreferences(TouchMouseService.PREFS_NAME, MODE_PRIVATE)
        val sensX = prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_X, 150f).toInt()
        val sensY = prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_Y, 3500f).toInt()

        val sel = selectedIdx
        val editing = depth == 2

        // Row markers: > = selected, * = editing
        val m0 = when { sel == 0 -> ">"; else -> " " }
        val m1 = when { editing && sel == 1 -> "*"; sel == 1 -> ">"; else -> " " }
        val m2 = when { editing && sel == 2 -> "*"; sel == 2 -> ">"; else -> " " }
        val m3 = when { editing && sel == 3 -> "*"; sel == 3 -> ">"; else -> " " }

        binding.tvLabel0.text = "$m0 SERVICE"
        binding.tvValue0.text = if (enabled) "ON" else "OFF"
        binding.tvLabel1.text = "$m1 POWER"
        binding.tvValue1.text = if (active) "ON" else "OFF"
        binding.tvLabel2.text = "$m2 H.SENS"
        binding.tvValue2.text = sensX.toString()
        binding.tvLabel3.text = "$m3 V.SENS"
        binding.tvValue3.text = sensY.toString()

        // Highlight colors
        val bright = getColor(R.color.hud_green_bright)
        val normal = getColor(R.color.hud_green)
        val dim = getColor(R.color.hud_green_dim)
        val red = getColor(R.color.hud_red)

        val c0 = when { sel == 0 -> normal; else -> dim }
        val c1 = when { editing && sel == 1 -> bright; sel == 1 -> normal; else -> dim }
        val c2 = when { editing && sel == 2 -> bright; sel == 2 -> normal; else -> dim }
        val c3 = when { editing && sel == 3 -> bright; sel == 3 -> normal; else -> dim }

        // SERVICE row: red if disabled
        binding.tvLabel0.setTextColor(c0)
        binding.tvValue0.setTextColor(if (enabled) c0 else red)
        binding.tvLabel1.setTextColor(c1); binding.tvValue1.setTextColor(c1)
        binding.tvLabel2.setTextColor(c2); binding.tvValue2.setTextColor(c2)
        binding.tvLabel3.setTextColor(c3); binding.tvValue3.setTextColor(c3)

        binding.tvHint.text = if (editing) {
            "<  swipe: adjust  |  tap/back: done  >"
        } else {
            "<  swipe: select  |  tap: enter  |  back: exit  >\nToggle anywhere: swipe L L R R"
        }
        binding.tvDepth.text = if (editing) "[EDIT]" else ""
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains(packageName) && s.contains("TouchMouseService")
    }
}
