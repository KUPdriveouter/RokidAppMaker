package com.kupstudio.btmanager

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.kupstudio.btmanager.bt.BtManager
import com.kupstudio.btmanager.databinding.ActivityMainBinding
import com.kupstudio.btmanager.ui.DebugLogFragment
import com.kupstudio.btmanager.ui.DeviceListFragment
import com.kupstudio.btmanager.util.DebugLog
import com.kupstudio.btmanager.util.PermissionHelper

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    var btManager: BtManager? = null
        private set

    private lateinit var permissionHelper: PermissionHelper
    private var currentTab = 0 // 0 = devices, 1 = logs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionHelper = PermissionHelper(this) { granted ->
            if (granted) {
                initBluetooth()
            } else {
                DebugLog.e(TAG, "Permissions denied - BT functions unavailable")
                updateStatus("No Permission")
            }
        }

        // Tab buttons
        binding.btnDevices.setOnClickListener { switchTab(0) }
        binding.btnLogs.setOnClickListener { switchTab(1) }

        // D-pad: when focused on tab buttons, invert colors
        listOf(binding.btnDevices, binding.btnLogs).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                val tv = v as android.widget.TextView
                if (hasFocus) {
                    tv.setTextColor(android.graphics.Color.BLACK)
                } else {
                    tv.setTextColor(android.graphics.Color.GREEN)
                }
            }
        }

        // Check permissions
        permissionHelper.checkAndRequest(this)

        // Default to devices tab
        if (savedInstanceState == null) {
            switchTab(0)
        }
    }

    private fun initBluetooth() {
        btManager = BtManager(this).also { it.init() }
        DebugLog.i(TAG, "Bluetooth initialized")
        // Refresh fragment if already showing
        if (currentTab == 0) switchTab(0)
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        val fragment: Fragment = when (tab) {
            0 -> DeviceListFragment()
            1 -> DebugLogFragment()
            else -> return
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    override fun onDestroy() {
        btManager?.release()
        super.onDestroy()
    }
}
