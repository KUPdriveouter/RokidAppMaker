package com.kupstudio.btmanager.ui

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.kupstudio.btmanager.MainActivity
import com.kupstudio.btmanager.bt.BtManager
import com.kupstudio.btmanager.bt.DeviceInfo
import com.kupstudio.btmanager.databinding.FragmentDeviceListBinding
import com.kupstudio.btmanager.util.DebugLog

class DeviceListFragment : Fragment() {

    companion object {
        private const val TAG = "DeviceList"
    }

    private var _binding: FragmentDeviceListBinding? = null
    private val binding get() = _binding!!

    private lateinit var pairedAdapter: DeviceListAdapter
    private lateinit var discoveredAdapter: DeviceListAdapter

    private val btManager: BtManager?
        get() = (activity as? MainActivity)?.btManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pairedAdapter = DeviceListAdapter(
            onClick = { onDeviceClick(it) },
            onLongClick = { onDeviceLongClick(it) }
        )

        discoveredAdapter = DeviceListAdapter(
            onClick = { onDeviceClick(it) },
            onLongClick = { onDeviceLongClick(it) }
        )

        binding.rvPaired.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = pairedAdapter
        }

        binding.rvDiscovered.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = discoveredAdapter
        }

        // Scan button
        binding.btnScan.setOnClickListener {
            val bt = btManager ?: return@setOnClickListener
            if (bt.scanner.isScanning) {
                bt.stopScan()
            } else {
                bt.startScan()
            }
        }

        // Wire up callbacks
        btManager?.let { bt ->
            bt.scanner.onDevicesChanged = { devices ->
                discoveredAdapter.submitList(devices)
            }
            bt.scanner.onScanStateChanged = { scanning ->
                binding.btnScan.text = if (scanning) "STOP" else "SCAN"
                binding.tvScanStatus.text = if (scanning) "Scanning..." else "Idle"
                (activity as? MainActivity)?.updateStatus(
                    if (scanning) "Scanning..." else "Idle"
                )
            }
            bt.onBondedDevicesChanged = { devices ->
                pairedAdapter.submitList(devices)
            }

            // Load initial bonded devices
            pairedAdapter.submitList(bt.getBondedDevices())
        }

        // Initial focus on scan button
        binding.btnScan.requestFocus()
    }

    private fun onDeviceClick(info: DeviceInfo) {
        val bt = btManager ?: return

        when {
            info.isConnected -> {
                DebugLog.i(TAG, "Disconnecting ${info.name}")
                bt.disconnect(info.device)
            }
            info.bondState == BluetoothDevice.BOND_BONDED -> {
                DebugLog.i(TAG, "Connecting to ${info.name}")
                bt.connect(info.device)
            }
            else -> {
                DebugLog.i(TAG, "Pairing with ${info.name}")
                bt.pair(info.device)
            }
        }
    }

    private fun onDeviceLongClick(info: DeviceInfo): Boolean {
        if (info.bondState == BluetoothDevice.BOND_BONDED) {
            DebugLog.i(TAG, "Unpairing ${info.name}")
            btManager?.unpair(info.device)
            return true
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
