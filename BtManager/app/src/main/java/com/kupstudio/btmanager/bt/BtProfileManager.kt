package com.kupstudio.btmanager.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.kupstudio.btmanager.util.DebugLog

@SuppressLint("MissingPermission")
class BtProfileManager(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "BtProfile"
        private const val HID_HOST = 4 // BluetoothProfile.HID_HOST
    }

    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    private var hidHost: BluetoothProfile? = null

    private var a2dpReady = false
    private var headsetReady = false
    private var hidReady = false

    fun init() {
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                a2dpReady = true
                DebugLog.d(TAG, "A2DP proxy connected")
            }
            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
                a2dpReady = false
            }
        }, BluetoothProfile.A2DP)

        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                headset = proxy as BluetoothHeadset
                headsetReady = true
                DebugLog.d(TAG, "Headset proxy connected")
            }
            override fun onServiceDisconnected(profile: Int) {
                headset = null
                headsetReady = false
            }
        }, BluetoothProfile.HEADSET)

        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hidHost = proxy
                hidReady = true
                DebugLog.d(TAG, "HID Host proxy connected")
            }
            override fun onServiceDisconnected(profile: Int) {
                hidHost = null
                hidReady = false
            }
        }, HID_HOST)
    }

    fun connect(device: BluetoothDevice): Boolean {
        DebugLog.i(TAG, "Connecting profiles for ${device.name ?: device.address}")
        var success = false

        // Try A2DP (audio output)
        a2dp?.let { proxy ->
            if (connectViaReflection(proxy, device, "A2DP")) success = true
        }

        // Try Headset/HFP
        headset?.let { proxy ->
            if (connectViaReflection(proxy, device, "Headset")) success = true
        }

        // Try HID
        hidHost?.let { proxy ->
            if (connectViaReflection(proxy, device, "HID")) success = true
        }

        if (!success) {
            DebugLog.w(TAG, "No profile connected for ${device.address}")
        }
        return success
    }

    fun disconnect(device: BluetoothDevice) {
        DebugLog.i(TAG, "Disconnecting profiles for ${device.name ?: device.address}")

        a2dp?.let { disconnectViaReflection(it, device, "A2DP") }
        headset?.let { disconnectViaReflection(it, device, "Headset") }
        hidHost?.let { disconnectViaReflection(it, device, "HID") }
    }

    fun getConnectedDevices(): List<BluetoothDevice> {
        val devices = mutableSetOf<BluetoothDevice>()
        try { a2dp?.connectedDevices?.let { devices.addAll(it) } } catch (_: Exception) {}
        try { headset?.connectedDevices?.let { devices.addAll(it) } } catch (_: Exception) {}
        try { hidHost?.connectedDevices?.let { devices.addAll(it) } } catch (_: Exception) {}
        return devices.toList()
    }

    private fun connectViaReflection(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        profileName: String
    ): Boolean {
        return try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val result = method.invoke(proxy, device) as Boolean
            DebugLog.d(TAG, "$profileName connect() -> $result")
            result
        } catch (e: Exception) {
            DebugLog.w(TAG, "$profileName connect failed: ${e.message}")
            false
        }
    }

    private fun disconnectViaReflection(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        profileName: String
    ) {
        try {
            val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            val result = method.invoke(proxy, device) as Boolean
            DebugLog.d(TAG, "$profileName disconnect() -> $result")
        } catch (e: Exception) {
            DebugLog.w(TAG, "$profileName disconnect failed: ${e.message}")
        }
    }

    fun release() {
        a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        hidHost?.let { adapter.closeProfileProxy(HID_HOST, it) }
        a2dp = null
        headset = null
        hidHost = null
    }
}
