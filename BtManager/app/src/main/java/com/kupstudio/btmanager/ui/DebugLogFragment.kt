package com.kupstudio.btmanager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.kupstudio.btmanager.databinding.FragmentDebugLogBinding
import com.kupstudio.btmanager.util.DebugLog
import com.kupstudio.btmanager.util.LogEntry

class DebugLogFragment : Fragment() {

    private var _binding: FragmentDebugLogBinding? = null
    private val binding get() = _binding!!

    private lateinit var logAdapter: DebugLogAdapter

    private val logListener: (List<LogEntry>) -> Unit = { entries ->
        logAdapter.submitList(entries)
        // Auto-scroll to bottom
        if (entries.isNotEmpty()) {
            binding.rvLogs.scrollToPosition(entries.size - 1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logAdapter = DebugLogAdapter()

        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }

        binding.btnClearLog.setOnClickListener {
            DebugLog.clear()
        }

        // Load existing entries
        logAdapter.submitList(DebugLog.getEntries())

        // Listen for new entries
        DebugLog.addListener(logListener)

        binding.btnClearLog.requestFocus()
    }

    override fun onDestroyView() {
        DebugLog.removeListener(logListener)
        _binding = null
        super.onDestroyView()
    }
}
