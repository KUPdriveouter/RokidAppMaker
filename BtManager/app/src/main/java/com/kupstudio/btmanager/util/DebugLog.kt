package com.kupstudio.btmanager.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
) {
    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun formatted(): String {
        val time = timeFormat.format(Date(timestamp))
        return "$time [$level] $tag: $message"
    }
}

object DebugLog {

    private const val MAX_ENTRIES = 500

    private val entries = LinkedList<LogEntry>()
    private val listeners = mutableListOf<(List<LogEntry>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun d(tag: String, msg: String) = log("D", tag, msg) { Log.d(tag, msg) }
    fun i(tag: String, msg: String) = log("I", tag, msg) { Log.i(tag, msg) }
    fun w(tag: String, msg: String) = log("W", tag, msg) { Log.w(tag, msg) }
    fun e(tag: String, msg: String) = log("E", tag, msg) { Log.e(tag, msg) }

    private inline fun log(level: String, tag: String, msg: String, androidLog: () -> Unit) {
        androidLog()
        val entry = LogEntry(System.currentTimeMillis(), level, tag, msg)
        synchronized(entries) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        notifyListeners()
    }

    fun getEntries(): List<LogEntry> {
        synchronized(entries) {
            return entries.toList()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
        notifyListeners()
    }

    fun addListener(listener: (List<LogEntry>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<LogEntry>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = getEntries()
        mainHandler.post {
            listeners.forEach { it(snapshot) }
        }
    }
}
