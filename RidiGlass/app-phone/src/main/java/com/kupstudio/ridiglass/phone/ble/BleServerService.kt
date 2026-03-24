package com.kupstudio.ridiglass.phone.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.kupstudio.ridiglass.common.BleProtocol
import java.util.concurrent.ConcurrentLinkedQueue

class BleServerService : Service() {

    companion object {
        private const val TAG = "BleServer"
        private const val CHANNEL_ID = "ridiglass_ble"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var textCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var isAdvertising = false

    private var audioManager: AudioManager? = null

    // Queued notification system for reliable BLE delivery
    private data class BleNotification(val characteristic: BluetoothGattCharacteristic, val data: ByteArray)
    private val notificationQueue = ConcurrentLinkedQueue<BleNotification>()
    private var isSending = false

    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onTextSent: ((String) -> Unit)? = null
    var onVolumeChanged: ((Int) -> Unit)? = null

    inner class LocalBinder : Binder() {
        val service: BleServerService get() = this@BleServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startGattServer()
        startAdvertising()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        gattServer?.close()
        super.onDestroy()
    }

    private fun hasPermission(perm: String): Boolean {
        return ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                    hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
                    hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    private fun startGattServer() {
        if (!hasBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions")
            return
        }

        val service = BluetoothGattService(
            BleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        textCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_TEXT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                BleProtocol.DESC_CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }

        commandCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                BleProtocol.DESC_CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }

        val controlCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(textCharacteristic)
        service.addCharacteristic(commandCharacteristic)
        service.addCharacteristic(controlCharacteristic)

        gattServer = bluetoothManager?.openGattServer(this, gattCallback)
        gattServer?.addService(service)
        Log.i(TAG, "GATT Server started")
    }

    private fun startAdvertising() {
        if (!hasBlePermissions()) return

        val adapter = bluetoothManager?.adapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!hasBlePermissions()) return
        val advertiser = bluetoothManager?.adapter?.bluetoothLeAdvertiser ?: return
        advertiser.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }

    /**
     * Queue-based BLE notification sender.
     * Waits for onNotificationSent callback before sending next chunk.
     */
    private fun enqueueNotification(char: BluetoothGattCharacteristic, data: ByteArray) {
        notificationQueue.add(BleNotification(char, data))
        if (!isSending) {
            sendNextNotification()
        }
    }

    private fun sendNextNotification() {
        val device = connectedDevice ?: run {
            isSending = false
            notificationQueue.clear()
            return
        }
        val next = notificationQueue.poll() ?: run {
            isSending = false
            return
        }
        isSending = true
        if (!hasBlePermissions()) {
            isSending = false
            return
        }
        next.characteristic.value = next.data
        val sent = gattServer?.notifyCharacteristicChanged(device, next.characteristic, false) ?: false
        if (!sent) {
            Log.w(TAG, "notifyCharacteristicChanged returned false, retrying in 50ms")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                sendNextNotification()
            }, 50)
        }
    }

    fun sendText(text: String) {
        if (connectedDevice == null || textCharacteristic == null) return
        if (!hasBlePermissions()) return

        val char = textCharacteristic!!
        val chunks = BleProtocol.encodeText(text)
        for (chunk in chunks) {
            enqueueNotification(char, chunk)
        }

        onTextSent?.invoke(text)
        Log.d(TAG, "Queued text (${text.length} chars, ${chunks.size} chunks)")
    }

    fun sendCommand(cmd: Byte, payload: ByteArray = byteArrayOf()) {
        if (connectedDevice == null) {
            Log.w(TAG, "sendCommand: no device connected")
            return
        }
        val char = commandCharacteristic ?: run {
            Log.w(TAG, "sendCommand: command characteristic null")
            return
        }
        if (!hasBlePermissions()) return

        enqueueNotification(char, byteArrayOf(cmd) + payload)
        Log.d(TAG, "Queued command: 0x${String.format("%02X", cmd)}")
    }

    fun getVolumePercent(): Int {
        val am = audioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (cur * 100 / max) else 0
    }

    private fun handleVolumeCommand(cmd: Byte, payload: ByteArray) {
        val am = audioManager ?: return
        when (cmd) {
            BleProtocol.CTL_VOLUME_UP -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            }
            BleProtocol.CTL_VOLUME_DOWN -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            BleProtocol.CTL_VOLUME_SET -> {
                if (payload.isNotEmpty()) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (payload[0].toInt() and 0xFF).coerceIn(0, 100)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target * max / 100, 0)
                }
            }
        }
        val pct = getVolumePercent()
        onVolumeChanged?.invoke(pct)
        sendCommand(BleProtocol.CMD_VOLUME_LEVEL, byteArrayOf(pct.toByte()))
        Log.d(TAG, "Volume: $pct%")
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                onConnectionChanged?.invoke(true)
                Log.i(TAG, "Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                notificationQueue.clear()
                isSending = false
                onConnectionChanged?.invoke(false)
                Log.i(TAG, "Device disconnected")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // Flow control: send next queued notification
            sendNextNotification()
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded && hasBlePermissions()) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (hasBlePermissions()) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.value ?: byteArrayOf()
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == BleProtocol.CHAR_CONTROL_UUID && value != null && value.isNotEmpty()) {
                val cmd = value[0]
                val payload = if (value.size > 1) value.copyOfRange(1, value.size) else byteArrayOf()
                handleVolumeCommand(cmd, payload)
            }

            if (responseNeeded && hasBlePermissions()) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.kupstudio.ridiglass.phone.R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(com.kupstudio.ridiglass.phone.R.string.notification_title))
            .setContentText(getString(com.kupstudio.ridiglass.phone.R.string.notification_text))
            .setOngoing(true)
            .build()
    }
}
