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

    private enum class Item { SERVICE, TOGGLE, SENS_X, SENS_Y, DW_TIME, DW_DLY, DW_RAD, SHAKE, CIRCLE }
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

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvTitle.text = "${getString(R.string.app_name)} v$versionName"

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
                    when (items[selectedIdx]) {
                        Item.SERVICE -> openAccessibilitySettings()
                        Item.TOGGLE -> {
                            TouchMouseService.instance?.toggleActive()
                            render()
                        }
                        Item.SHAKE -> {
                            TouchMouseService.instance?.let {
                                it.setShakeBackEnabled(!it.shakeBackEnabled)
                            }
                            render()
                        }
                        Item.CIRCLE -> {
                            TouchMouseService.instance?.let {
                                it.setCircleToggleEnabled(!it.circleToggleEnabled)
                            }
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
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun enterDepth2() {
        depth = 2
        val item = items[selectedIdx]
        if (item == Item.SENS_X || item == Item.SENS_Y) {
            val svc = TouchMouseService.instance
            wasActiveBeforeEdit = svc?.isActive() == true
            if (!wasActiveBeforeEdit) svc?.toggleActive()
        }
        render()
    }

    private fun exitDepth2() {
        val item = items[selectedIdx]
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
            Item.SERVICE -> {}
            Item.TOGGLE -> TouchMouseService.instance?.toggleActive()
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
            Item.DW_TIME -> {
                val svc = TouchMouseService.instance ?: return
                val newMs = (svc.dwellDuration + dir * 500L).coerceIn(1000L, 10000L)
                svc.updateDwellDuration(newMs)
            }
            Item.DW_DLY -> {
                val svc = TouchMouseService.instance ?: return
                val newMs = (svc.dwellDelay + dir * 250L).coerceIn(0L, 5000L)
                svc.updateDwellDelay(newMs)
            }
            Item.DW_RAD -> {
                val svc = TouchMouseService.instance ?: return
                val newPx = (svc.dwellRadius + dir * 5f).coerceIn(10f, 80f)
                svc.updateDwellRadius(newPx)
            }
            Item.SHAKE -> {
                TouchMouseService.instance?.let {
                    it.setShakeBackEnabled(!it.shakeBackEnabled)
                }
            }
            Item.CIRCLE -> {
                TouchMouseService.instance?.let {
                    it.setCircleToggleEnabled(!it.circleToggleEnabled)
                }
            }
        }
    }

    private fun render() {
        val enabled = isAccessibilityServiceEnabled()
        val svc = TouchMouseService.instance
        val active = svc?.isActive() == true
        val prefs = getSharedPreferences(TouchMouseService.PREFS_NAME, MODE_PRIVATE)
        val sensX = prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_X, 150f).toInt()
        val sensY = prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_Y, 3500f).toInt()
        val dwellSec = (svc?.dwellDuration ?: 3000L) / 1000f
        val dwellDlySec = (svc?.dwellDelay ?: 1000L) / 1000f
        val dwellRad = svc?.dwellRadius?.toInt() ?: 30
        val shakeOn = svc?.shakeBackEnabled == true
        val circleOn = svc?.circleToggleEnabled == true
        val sel = selectedIdx
        val editing = depth == 2

        fun marker(idx: Int): String = when {
            editing && sel == idx -> "*"
            sel == idx -> ">"
            else -> " "
        }

        binding.tvLabel0.text = "${marker(0)} SERVICE"
        binding.tvValue0.text = if (enabled) "ON" else "OFF"
        binding.tvLabel1.text = "${marker(1)} POWER"
        binding.tvValue1.text = if (active) "ON" else "OFF"
        binding.tvLabel2.text = "${marker(2)} H.SENS"
        binding.tvValue2.text = sensX.toString()
        binding.tvLabel3.text = "${marker(3)} V.SENS"
        binding.tvValue3.text = sensY.toString()
        binding.tvLabel4.text = "${marker(4)} DW.TIME"
        binding.tvValue4.text = "${dwellSec}s"
        binding.tvLabel5.text = "${marker(5)} DW.DLY"
        binding.tvValue5.text = "${dwellDlySec}s"
        binding.tvLabel6.text = "${marker(6)} DW.RAD"
        binding.tvValue6.text = "${dwellRad}px"
        binding.tvLabel7.text = "${marker(7)} SHAKE"
        binding.tvValue7.text = if (shakeOn) "ON" else "OFF"
        binding.tvLabel8.text = "${marker(8)} CIRCLE"
        binding.tvValue8.text = if (circleOn) "ON" else "OFF"

        val bright = getColor(R.color.hud_green_bright)
        val normal = getColor(R.color.hud_green)
        val dim = getColor(R.color.hud_green_dim)
        val red = getColor(R.color.hud_red)

        fun rowColor(idx: Int): Int = when {
            editing && sel == idx -> bright
            sel == idx -> normal
            else -> dim
        }

        binding.tvLabel0.setTextColor(rowColor(0))
        binding.tvValue0.setTextColor(if (enabled) rowColor(0) else red)
        binding.tvLabel1.setTextColor(rowColor(1)); binding.tvValue1.setTextColor(rowColor(1))
        binding.tvLabel2.setTextColor(rowColor(2)); binding.tvValue2.setTextColor(rowColor(2))
        binding.tvLabel3.setTextColor(rowColor(3)); binding.tvValue3.setTextColor(rowColor(3))
        binding.tvLabel4.setTextColor(rowColor(4)); binding.tvValue4.setTextColor(rowColor(4))
        binding.tvLabel5.setTextColor(rowColor(5)); binding.tvValue5.setTextColor(rowColor(5))
        binding.tvLabel6.setTextColor(rowColor(6)); binding.tvValue6.setTextColor(rowColor(6))
        binding.tvLabel7.setTextColor(rowColor(7)); binding.tvValue7.setTextColor(rowColor(7))
        binding.tvLabel8.setTextColor(rowColor(8)); binding.tvValue8.setTextColor(rowColor(8))
        binding.tvHint.text = if (editing) {
            "<  swipe: adjust  |  tap/back: done  >"
        } else {
            "<  swipe: select  |  tap: enter  |  back: exit  >\nToggle: swipe L L R R  or  circle x2\nNod down x2: dwell | Nod up x2: scroll"
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
