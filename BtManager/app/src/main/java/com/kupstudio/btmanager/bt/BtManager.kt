package com.kupstudio.btmanager.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.kupstudio.btmanager.util.DebugLog

@SuppressLint("MissingPermission")
class BtManager(private val context: Context) {

    companion object {
        private const val TAG = "BtManager"
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter = btManager.adapter
    val scanner = BtScanner(context, adapter)
    val profileManager = BtProfileManager(context, adapter)
    private val handler = Handler(Looper.getMainLooper())

    var onBondedDevicesChanged: ((List<DeviceInfo>) -> Unit)? = null

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    val prevState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1
                    )
                    val newState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, -1
                    )
                    DebugLog.i(TAG, "Bond changed: ${device?.name} $prevState -> $newState")
                    // Delay slightly to let the system update
                    handler.postDelayed({ notifyBondedChanged() }, 500)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    DebugLog.i(TAG, "ACL connected: ${device?.name ?: device?.address}")
                    handler.postDelayed({ notifyBondedChanged() }, 500)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    DebugLog.i(TAG, "ACL disconnected: ${device?.name ?: device?.address}")
                    handler.postDelayed({ notifyBondedChanged() }, 500)
                }
            }
        }
    }

    private var receiverRegistered = false

    fun init() {
        DebugLog.i(TAG, "Initializing BtManager")
        profileManager.init()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(bondReceiver, filter)
        receiverRegistered = true

        DebugLog.d(TAG, "BT enabled: ${adapter.isEnabled}")
        DebugLog.d(TAG, "Bonded devices: ${adapter.bondedDevices?.size ?: 0}")
    }

    fun getBondedDevices(): List<DeviceInfo> {
        return adapter.bondedDevices
            ?.map { DeviceInfo.from(it) }
            ?.sortedWith(compareByDescending<DeviceInfo> { it.isConnected }.thenBy { it.name })
            ?: emptyList()
    }

    fun pair(device: BluetoothDevice) {
        DebugLog.i(TAG, "Pairing with ${device.name ?: device.address}")
        device.createBond()
    }

    fun unpair(device: BluetoothDevice) {
        DebugLog.i(TAG, "Unpairing ${device.name ?: device.address}")
        try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Unpair failed: ${e.message}")
        }
    }

    fun connect(device: BluetoothDevice) {
        // Ensure paired first
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            DebugLog.w(TAG, "Device not paired, pairing first")
            pair(device)
            return
        }
        profileManager.connect(device)
    }

    fun disconnect(device: BluetoothDevice) {
        profileManager.disconnect(device)
    }

    fun startScan() = scanner.startScan()
    fun stopScan() = scanner.stopScan()

    private fun notifyBondedChanged() {
        onBondedDevicesChanged?.invoke(getBondedDevices())
    }

    fun release() {
        scanner.release()
        profileManager.release()
        if (receiverRegistered) {
            context.unregisterReceiver(bondReceiver)
            receiverRegistered = false
        }
        onBondedDevicesChanged = null
    }
}
