package com.kupstudio.btmanager.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kupstudio.btmanager.databinding.ItemLogEntryBinding
import com.kupstudio.btmanager.util.LogEntry

class DebugLogAdapter : RecyclerView.Adapter<DebugLogAdapter.ViewHolder>() {

    private var entries = listOf<LogEntry>()

    fun submitList(list: List<LogEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemLogEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogEntry) {
            binding.tvLogEntry.text = entry.formatted()
            binding.tvLogEntry.setTextColor(
                when (entry.level) {
                    "E" -> Color.parseColor("#FF4444")
                    "W" -> Color.parseColor("#FFAA00")
                    "I" -> Color.parseColor("#44FF44")
                    else -> Color.GREEN
                }
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size
}
