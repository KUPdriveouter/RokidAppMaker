package com.kupstudio.btmanager.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

@SuppressLint("MissingPermission")
data class DeviceInfo(
    val name: String,
    val address: String,
    val bondState: Int,
    val type: Int,
    val isConnected: Boolean,
    val device: BluetoothDevice
) {
    companion object {
        @SuppressLint("MissingPermission")
        fun from(device: BluetoothDevice): DeviceInfo {
            val connected = try {
                val method = device.javaClass.getMethod("isConnected")
                method.invoke(device) as Boolean
            } catch (_: Exception) {
                false
            }
            return DeviceInfo(
                name = device.name ?: "Unknown",
                address = device.address,
                bondState = device.bondState,
                type = device.type,
                isConnected = connected,
                device = device
            )
        }
    }

    val statusText: String
        get() = when {
            isConnected -> "Connected"
            bondState == BluetoothDevice.BOND_BONDED -> "Paired"
            bondState == BluetoothDevice.BOND_BONDING -> "Pairing..."
            else -> ""
        }
}
