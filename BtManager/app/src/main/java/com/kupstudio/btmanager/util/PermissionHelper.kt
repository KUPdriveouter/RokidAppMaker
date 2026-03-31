package com.kupstudio.btmanager.util

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionHelper(
    activity: ComponentActivity,
    private val onResult: (allGranted: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "PermissionHelper"

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            DebugLog.i(TAG, "All BT permissions granted")
        } else {
            val denied = results.filter { !it.value }.keys
            DebugLog.w(TAG, "Permissions denied: $denied")
        }
        onResult(allGranted)
    }

    fun checkAndRequest(activity: ComponentActivity): Boolean {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            DebugLog.d(TAG, "All permissions already granted")
            onResult(true)
            true
        } else {
            DebugLog.d(TAG, "Requesting permissions: $missing")
            launcher.launch(missing.toTypedArray())
            false
        }
    }
}
