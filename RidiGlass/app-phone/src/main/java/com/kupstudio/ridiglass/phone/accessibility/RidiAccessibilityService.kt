package com.kupstudio.ridiglass.phone.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kupstudio.ridiglass.phone.ble.BleServerService

/**
 * Captures visible text from Ridibooks EPub reader WebView.
 * Sends truncated page text to glasses, refreshes on content changes
 * and periodically to keep glasses display in sync.
 */
class RidiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RidiA11y"
        private const val RIDIBOOKS_PACKAGE = "com.ridi.books.onestore"
        private const val MAX_SEND_CHARS = 400
        private const val PERIODIC_SCAN_MS = 4000L
        var instance: RidiAccessibilityService? = null
            private set
    }

    private var bleService: BleServerService? = null
    private var lastSentText: String = ""
    private var isReaderActive = false
    private val handler = Handler(Looper.getMainLooper())
    var onTextCaptured: ((String) -> Unit)? = null

    private val bleConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BleServerService.LocalBinder).service
            Log.i(TAG, "BLE service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
        }
    }

    private val periodicScan = object : Runnable {
        override fun run() {
            if (isReaderActive) {
                scanAndSend(force = false)
                handler.postDelayed(this, PERIODIC_SCAN_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf(RIDIBOOKS_PACKAGE)
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }

        val bleIntent = Intent(this, BleServerService::class.java)
        bindService(bleIntent, bleConnection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Service started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != RIDIBOOKS_PACKAGE) return

        val className = event.className?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (className.contains("EPubReader") || className.contains("WebView")) {
                    startReaderMode()
                } else if (className.contains("Activity") && !className.contains("Reader")) {
                    stopReaderMode()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // WebView content changed — TTS scrolled or page turned
                if (isReaderActive) {
                    // Debounce: scan 300ms after last content change
                    handler.removeCallbacks(contentChangeScan)
                    handler.postDelayed(contentChangeScan, 300)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Page scrolled — likely TTS advancing
                if (isReaderActive) {
                    handler.removeCallbacks(contentChangeScan)
                    handler.postDelayed(contentChangeScan, 200)
                }
            }
        }
    }

    private val contentChangeScan = Runnable {
        if (isReaderActive) scanAndSend(force = true)
    }

    private fun startReaderMode() {
        if (!isReaderActive) {
            isReaderActive = true
            lastSentText = ""
            Log.i(TAG, "Reader active")
            handler.removeCallbacks(periodicScan)
            handler.post(periodicScan)
        }
    }

    private fun stopReaderMode() {
        isReaderActive = false
        handler.removeCallbacks(periodicScan)
        handler.removeCallbacks(contentChangeScan)
        Log.i(TAG, "Reader inactive")
    }

    /**
     * @param force if true, send even if text hasn't changed (for periodic refresh)
     */
    private fun scanAndSend(force: Boolean) {
        val root = try { rootInActiveWindow } catch (_: Exception) { return } ?: return

        // Step 1: Find WebView node
        val webView = findWebView(root)
        if (webView == null) {
            Log.d(TAG, "No WebView found")
            return
        }

        // Step 2: Collect only text inside the WebView
        val paragraphs = mutableListOf<String>()
        collectTextFromNode(webView, paragraphs)

        if (paragraphs.isEmpty()) return

        // Step 3: Take paragraphs up to MAX_SEND_CHARS
        val sb = StringBuilder()
        for (p in paragraphs) {
            if (sb.length + p.length > MAX_SEND_CHARS) {
                if (sb.isEmpty()) sb.append(p.take(MAX_SEND_CHARS))
                break
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(p)
        }

        val text = sb.toString().trim()
        if (text.isBlank()) return

        val changed = text != lastSentText
        if (changed || force) {
            if (changed) lastSentText = text
            bleService?.sendText(text)
            onTextCaptured?.invoke(text)
            if (changed) Log.i(TAG, "NEW text (${text.length}c): ${text.take(60)}...")
        }
    }

    /** Find the first WebView node in the tree */
    private fun findWebView(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.webkit.WebView") return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = findWebView(child)
            if (found != null) return found
        }
        return null
    }

    /** Collect all text nodes inside a WebView (book content only) */
    private fun collectTextFromNode(node: AccessibilityNodeInfo, paragraphs: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 5) {
            paragraphs.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            collectTextFromNode(child, paragraphs)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        stopReaderMode()
        try { unbindService(bleConnection) } catch (_: Exception) {}
        super.onDestroy()
    }
}
