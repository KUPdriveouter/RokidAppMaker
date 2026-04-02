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

    private var depth = 1  // 1: navigate, 2: edit
    private enum class Item { SERVICE, TOGGLE, SENS_X, SENS_Y, DW_TIME, DW_DLY, DW_RAD, SHAKE, CIRCLE }
    private val items = Item.entries.toList()
    private var selectedIdx = 0
    private var wasActiveBeforeEdit = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TouchMouseService.ACTION_STATUS_CHANGED) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvTitle.text = "${getString(R.string.app_name)} v$versionName"

        registerReceiver(statusReceiver, IntentFilter(TouchMouseService.ACTION_STATUS_CHANGED), RECEIVER_NOT_EXPORTED)
        render()
    }

    override fun onResume() {
        super.onResume()
        TouchMouseService.instance?.appInForeground = true
        render()
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

    // ── Key navigation ──

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (depth == 1) selectedIdx = (selectedIdx - 1 + items.size) % items.size
                else adjustValue(-1)
                render(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (depth == 1) selectedIdx = (selectedIdx + 1) % items.size
                else adjustValue(1)
                render(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (depth == 2) exitEdit()
                else onItemSelect()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (depth == 2) { exitEdit(); return true }
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onItemSelect() {
        when (items[selectedIdx]) {
            Item.SERVICE -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Item.TOGGLE -> { TouchMouseService.instance?.toggleActive(); render() }
            Item.SHAKE -> {
                TouchMouseService.instance?.let { it.setShakeBackEnabled(!it.shakeBackEnabled) }
                render()
            }
            Item.CIRCLE -> {
                TouchMouseService.instance?.let { it.setCircleToggleEnabled(!it.circleToggleEnabled) }
                render()
            }
            else -> enterEdit()
        }
    }

    private fun enterEdit() {
        depth = 2
        val item = items[selectedIdx]
        if (item == Item.SENS_X || item == Item.SENS_Y) {
            val svc = TouchMouseService.instance
            wasActiveBeforeEdit = svc?.isActive() == true
            if (!wasActiveBeforeEdit) svc?.toggleActive()
        }
        render()
    }

    private fun exitEdit() {
        val item = items[selectedIdx]
        if (item == Item.SENS_X || item == Item.SENS_Y) {
            if (!wasActiveBeforeEdit) TouchMouseService.instance?.let { if (it.isActive()) it.toggleActive() }
        }
        depth = 1
        render()
    }

    private fun adjustValue(dir: Int) {
        val prefs = getSharedPreferences(TouchMouseService.PREFS_NAME, MODE_PRIVATE)
        when (items[selectedIdx]) {
            Item.SERVICE, Item.TOGGLE -> {}
            Item.SENS_X -> {
                val v = (prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_X, 150f) + dir * 50f).coerceIn(50f, 2000f)
                prefs.edit().putFloat(TouchMouseService.PREF_SENSITIVITY_X, v).apply()
                TouchMouseService.instance?.updateSensitivityX(v)
            }
            Item.SENS_Y -> {
                val v = (prefs.getFloat(TouchMouseService.PREF_SENSITIVITY_Y, 3500f) + dir * 100f).coerceIn(100f, 8000f)
                prefs.edit().putFloat(TouchMouseService.PREF_SENSITIVITY_Y, v).apply()
                TouchMouseService.instance?.updateSensitivityY(v)
            }
            Item.DW_TIME -> TouchMouseService.instance?.let { it.updateDwellDuration(it.dwellDuration + dir * 500L) }
            Item.DW_DLY -> TouchMouseService.instance?.let { it.updateDwellDelay(it.dwellDelay + dir * 250L) }
            Item.DW_RAD -> TouchMouseService.instance?.let { it.updateDwellRadius(it.dwellRadius + dir * 5f) }
            Item.SHAKE -> TouchMouseService.instance?.let { it.setShakeBackEnabled(!it.shakeBackEnabled) }
            Item.CIRCLE -> TouchMouseService.instance?.let { it.setCircleToggleEnabled(!it.circleToggleEnabled) }
        }
    }

    // ── Render ──

    private val rows by lazy {
        listOf(binding.row0, binding.row1, binding.row2, binding.row3,
            binding.row4, binding.row5, binding.row6, binding.row7, binding.row8)
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
        val editing = depth == 2

        fun marker(idx: Int): String = when {
            editing && selectedIdx == idx -> "*"
            selectedIdx == idx -> ">"
            else -> " "
        }

        binding.tvLabel0.text = "${marker(0)} Accessibility";  binding.tvValue0.text = if (enabled) "ON" else "OFF"
        binding.tvLabel1.text = "${marker(1)} Cursor";          binding.tvValue1.text = if (active) "ON" else "OFF"
        binding.tvLabel2.text = "${marker(2)} Speed X";         binding.tvValue2.text = sensX.toString()
        binding.tvLabel3.text = "${marker(3)} Speed Y";         binding.tvValue3.text = sensY.toString()
        binding.tvLabel4.text = "${marker(4)} Hold Time";       binding.tvValue4.text = "${dwellSec}s"
        binding.tvLabel5.text = "${marker(5)} Cooldown";        binding.tvValue5.text = "${dwellDlySec}s"
        binding.tvLabel6.text = "${marker(6)} Range";           binding.tvValue6.text = "${dwellRad}px"
        binding.tvLabel7.text = "${marker(7)} Shake Back";      binding.tvValue7.text = if (shakeOn) "ON" else "OFF"
        binding.tvLabel8.text = "${marker(8)} Circle Toggle";   binding.tvValue8.text = if (circleOn) "ON" else "OFF"

        val bright = getColor(R.color.hud_green_bright)
        val normal = getColor(R.color.hud_green)
        val dim = getColor(R.color.hud_green_dim)
        val red = getColor(R.color.hud_red)
        val highlight = getColor(R.color.row_highlight)

        fun rowColor(idx: Int): Int = when {
            editing && selectedIdx == idx -> bright
            selectedIdx == idx -> normal
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

        // Row background highlight
        for ((i, row) in rows.withIndex()) {
            row.setBackgroundColor(if (i == selectedIdx) highlight else 0)
        }

        binding.tvHint.text = if (editing) {
            "< Swipe >  Adjust value\n[ Tap / Back ]  Confirm"
        } else {
            "< Swipe >  Navigate    [ Tap ]  Select\n\nNod Down x2  Scroll mode\nNod Up x2  Dwell mode\nL L R R  Toggle cursor"
        }
        binding.tvDepth.text = if (editing) "[EDIT]" else ""
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName) && s.contains("TouchMouseService")
    }
}
