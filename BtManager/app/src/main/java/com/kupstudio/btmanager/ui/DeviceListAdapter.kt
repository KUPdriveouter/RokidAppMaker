package com.kupstudio.btmanager.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kupstudio.btmanager.bt.DeviceInfo
import com.kupstudio.btmanager.databinding.ItemDeviceBinding

class DeviceListAdapter(
    private val onClick: (DeviceInfo) -> Unit,
    private val onLongClick: (DeviceInfo) -> Boolean
) : ListAdapter<DeviceInfo, DeviceListAdapter.ViewHolder>(DiffCallback) {

    private object DiffCallback : DiffUtil.ItemCallback<DeviceInfo>() {
        override fun areItemsTheSame(old: DeviceInfo, new: DeviceInfo) =
            old.address == new.address

        override fun areContentsTheSame(old: DeviceInfo, new: DeviceInfo) =
            old.name == new.name && old.bondState == new.bondState && old.isConnected == new.isConnected
    }

    inner class ViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(info: DeviceInfo) {
            binding.tvDeviceName.text = info.name
            binding.tvDeviceAddress.text = info.address
            binding.tvDeviceStatus.text = info.statusText

            // When focused (D-pad), invert text colors for readability
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val textColor = if (hasFocus) Color.BLACK else Color.GREEN
                val dimColor = if (hasFocus) Color.DKGRAY else Color.parseColor("#008800")
                binding.tvDeviceName.setTextColor(textColor)
                binding.tvDeviceAddress.setTextColor(dimColor)
                binding.tvDeviceStatus.setTextColor(textColor)
            }

            binding.root.setOnClickListener { onClick(info) }
            binding.root.setOnLongClickListener { onLongClick(info) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
