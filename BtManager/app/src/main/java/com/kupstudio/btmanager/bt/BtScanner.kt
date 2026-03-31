package com.kupstudio.btmanager.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.kupstudio.btmanager.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BtScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "BtScanner"
        private const val SCAN_TIMEOUT_MS = 30_000L
    }

    private val discoveredDevices = ConcurrentHashMap<String, DeviceInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private var bleScanner: BluetoothLeScanner? = null

    var isScanning = false
        private set

    var onDevicesChanged: ((List<DeviceInfo>) -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null

    // Classic BT discovery receiver
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    ) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val info = DeviceInfo.from(device)
                    discoveredDevices[device.address] = info
                    DebugLog.d(TAG, "Found: ${info.name} [${info.address}] RSSI=$rssi")
                    notifyDevicesChanged()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    DebugLog.d(TAG, "Classic discovery finished")
                }
            }
        }
    }

    // BLE scan callback
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val info = DeviceInfo.from(device)
            val isNew = !discoveredDevices.containsKey(device.address)
            discoveredDevices[device.address] = info
            if (isNew) {
                DebugLog.d(TAG, "BLE found: ${info.name} [${info.address}] RSSI=${result.rssi}")
                notifyDevicesChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            DebugLog.e(TAG, "BLE scan failed: error=$errorCode")
        }
    }

    private var receiverRegistered = false

    fun startScan() {
        if (isScanning) return
        isScanning = true
        discoveredDevices.clear()
        DebugLog.i(TAG, "Starting scan (Classic + BLE)")

        // Register classic BT receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)
        receiverRegistered = true

        // Start classic discovery
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
        DebugLog.d(TAG, "Classic discovery started")

        // Start BLE scan
        bleScanner = adapter.bluetoothLeScanner
        bleScanner?.let { scanner ->
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, bleScanCallback)
            DebugLog.d(TAG, "BLE scan started")
        } ?: DebugLog.w(TAG, "BLE scanner not available")

        onScanStateChanged?.invoke(true)

        // Auto-stop after timeout
        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        handler.removeCallbacksAndMessages(null)

        // Stop classic discovery
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        // Stop BLE scan
        bleScanner?.let { scanner ->
            try {
                scanner.stopScan(bleScanCallback)
            } catch (e: Exception) {
                DebugLog.w(TAG, "Error stopping BLE scan: ${e.message}")
            }
        }

        if (receiverRegistered) {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
        }

        DebugLog.i(TAG, "Scan stopped. Found ${discoveredDevices.size} devices")
        onScanStateChanged?.invoke(false)
    }

    fun getDiscoveredDevices(): List<DeviceInfo> {
        // Refresh connected state
        return discoveredDevices.values
            .map { DeviceInfo.from(it.device) }
            .sortedWith(compareByDescending<DeviceInfo> { it.isConnected }
                .thenByDescending { it.bondState == BluetoothDevice.BOND_BONDED }
                .thenBy { it.name })
    }

    private fun notifyDevicesChanged() {
        handler.post {
            onDevicesChanged?.invoke(getDiscoveredDevices())
        }
    }

    fun release() {
        stopScan()
        onDevicesChanged = null
        onScanStateChanged = null
    }
}
