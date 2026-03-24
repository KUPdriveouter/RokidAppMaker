package com.kupstudio.ridiglass.glasses.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.kupstudio.ridiglass.common.BleProtocol

/**
 * BLE GATT Client for the glasses side.
 * Connects to the phone's GATT Server and receives text notifications.
 */
class BleClientManager(private val context: Context) {

    companion object {
        private const val TAG = "BleClient"
    }

    private var bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var isScanning = false

    var onTextReceived: ((String) -> Unit)? = null
    var onCommandReceived: ((Byte, ByteArray) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    // Chunk reassembly buffer
    private var chunkBuffer = mutableMapOf<Int, ByteArray>()
    private var expectedChunks = 0

    private fun hasPermission(perm: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                    hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
                    hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    fun startScan() {
        if (!hasBlePermissions() || isScanning) return

        scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        // Low power scan to save battery
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.i(TAG, "BLE scan started")
    }

    fun stopScan() {
        if (!hasBlePermissions() || !isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
    }

    fun disconnect() {
        if (!hasBlePermissions()) return
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /** Send a control command to the phone (e.g., volume up/down) */
    fun sendControl(cmd: Byte, payload: ByteArray = byteArrayOf()) {
        val g = gatt ?: return
        if (!hasBlePermissions()) return

        val service = g.getService(BleProtocol.SERVICE_UUID) ?: return
        val controlChar = service.getCharacteristic(BleProtocol.CHAR_CONTROL_UUID) ?: return

        controlChar.value = byteArrayOf(cmd) + payload
        controlChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        g.writeCharacteristic(controlChar)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasBlePermissions()) return
            stopScan()
            Log.i(TAG, "Found phone: ${result.device.address}")
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasBlePermissions()) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to phone")
                onConnectionChanged?.invoke(true)
                gatt.requestMtu(515) // Request large MTU for text
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from phone")
                onConnectionChanged?.invoke(false)
                // Auto-reconnect after a delay
                this@BleClientManager.gatt?.close()
                this@BleClientManager.gatt = null
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startScan()
                }, 3000)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!hasBlePermissions()) return
            Log.i(TAG, "MTU changed to $mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !hasBlePermissions()) return

            val service = gatt.getService(BleProtocol.SERVICE_UUID) ?: return

            // Subscribe to text notifications
            val textChar = service.getCharacteristic(BleProtocol.CHAR_TEXT_UUID)
            if (textChar != null) {
                gatt.setCharacteristicNotification(textChar, true)
                val desc = textChar.getDescriptor(BleProtocol.DESC_CCC_UUID)
                desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }

            // Subscribe to command notifications
            val cmdChar = service.getCharacteristic(BleProtocol.CHAR_COMMAND_UUID)
            if (cmdChar != null) {
                gatt.setCharacteristicNotification(cmdChar, true)
                val desc = cmdChar.getDescriptor(BleProtocol.DESC_CCC_UUID)
                desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                // Will write after first descriptor write completes
            }

            Log.i(TAG, "Subscribed to notifications")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (!hasBlePermissions()) return

            // After writing text descriptor, write command descriptor
            if (descriptor.characteristic.uuid == BleProtocol.CHAR_TEXT_UUID) {
                val service = gatt.getService(BleProtocol.SERVICE_UUID) ?: return
                val cmdChar = service.getCharacteristic(BleProtocol.CHAR_COMMAND_UUID) ?: return
                val cmdDesc = cmdChar.getDescriptor(BleProtocol.DESC_CCC_UUID)
                cmdDesc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cmdDesc)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return

            when (characteristic.uuid) {
                BleProtocol.CHAR_TEXT_UUID -> {
                    val (index, total, payload) = BleProtocol.decodeChunk(data)
                    Log.d(TAG, "Received chunk ${index + 1}/$total (${payload.size} bytes)")

                    if (total <= 1) {
                        // Single chunk — no reassembly needed
                        val text = BleProtocol.decodeText(payload)
                        onTextReceived?.invoke(text)
                    } else {
                        // Multi-chunk — reassemble
                        if (index == 0) {
                            chunkBuffer.clear()
                            expectedChunks = total
                        }
                        chunkBuffer[index] = payload

                        if (chunkBuffer.size >= expectedChunks) {
                            // All chunks received — assemble
                            val fullData = (0 until expectedChunks)
                                .mapNotNull { chunkBuffer[it] }
                                .fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                            chunkBuffer.clear()
                            val text = BleProtocol.decodeText(fullData)
                            Log.i(TAG, "Reassembled text: ${text.length} chars")
                            onTextReceived?.invoke(text)
                        }
                    }
                }
                BleProtocol.CHAR_COMMAND_UUID -> {
                    if (data.isNotEmpty()) {
                        onCommandReceived?.invoke(data[0], data.drop(1).toByteArray())
                    }
                }
            }
        }
    }
}
