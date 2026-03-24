package com.kupstudio.ridiglass.phone.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kupstudio.ridiglass.phone.ble.BleServerService

/**
 * NotificationListenerService that monitors Ridibooks media sessions.
 * This gives us permission to access MediaSessionManager and read
 * the TTS text from Ridibooks' media metadata.
 */
class RidiMediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "RidiMedia"
        private const val RIDIBOOKS_PACKAGE = "com.ridi.books.onestore"
        var instance: RidiMediaListenerService? = null
            private set
    }

    private var bleService: BleServerService? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var lastSentText: String = ""
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

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            logAllMetadata(metadata)
            extractAndSendText(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "Playback state: ${state?.state}")
            // When playback starts/changes, re-check metadata
            activeController?.metadata?.let { extractAndSendText(it) }
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active sessions changed: ${controllers?.size}")
        findAndRegisterRidiSession(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")

        // Bind to BLE server
        val bleIntent = Intent(this, BleServerService::class.java)
        bindService(bleIntent, bleConnection, Context.BIND_AUTO_CREATE)

        // Start monitoring media sessions
        startMediaSessionMonitoring()
    }

    private fun startMediaSessionMonitoring() {
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val componentName = ComponentName(this, RidiMediaListenerService::class.java)

        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            Log.i(TAG, "Media session monitoring started, found ${controllers?.size ?: 0} sessions")
            findAndRegisterRidiSession(controllers)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start media session monitoring", e)
        }
    }

    private fun findAndRegisterRidiSession(controllers: List<MediaController>?) {
        controllers?.forEach { controller ->
            Log.d(TAG, "Session: pkg=${controller.packageName}, tag=${controller.tag}")
            if (controller.packageName == RIDIBOOKS_PACKAGE) {
                registerMediaController(controller)
            }
        }
    }

    private fun registerMediaController(controller: MediaController) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller
        controller.registerCallback(mediaCallback)

        // Read current metadata immediately
        controller.metadata?.let {
            logAllMetadata(it)
            extractAndSendText(it)
        }

        Log.i(TAG, "Registered Ridibooks media session callback (tag=${controller.tag})")
    }

    private fun logAllMetadata(metadata: MediaMetadata) {
        val keys = listOf(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
            MediaMetadata.METADATA_KEY_GENRE,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_WRITER,
            MediaMetadata.METADATA_KEY_COMPOSER,
            MediaMetadata.METADATA_KEY_COMPILATION,
            MediaMetadata.METADATA_KEY_MEDIA_ID,
            MediaMetadata.METADATA_KEY_MEDIA_URI,
        )
        for (key in keys) {
            val value = metadata.getString(key)
            if (!value.isNullOrBlank()) {
                Log.d(TAG, "Metadata[$key] = $value")
            }
        }
    }

    private fun extractAndSendText(metadata: MediaMetadata) {
        // Media session only has book title/author — not the reading text.
        // Actual text capture is handled by AccessibilityService.
        // This service exists only to keep NotificationListener permission active.
        Log.d(TAG, "Metadata received (no BLE send — handled by accessibility service)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != RIDIBOOKS_PACKAGE) return
        Log.d(TAG, "Ridibooks notification: ${sbn.notification.extras}")

        // Also try to get text from notification extras
        val extras = sbn.notification.extras
        val notifText = extras.getCharSequence("android.text")?.toString()
        val notifTitle = extras.getCharSequence("android.title")?.toString()
        val notifSubText = extras.getCharSequence("android.subText")?.toString()
        Log.d(TAG, "Notif title=$notifTitle, text=$notifText, subText=$notifSubText")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // no-op
    }

    override fun onListenerDisconnected() {
        instance = null
        activeController?.unregisterCallback(mediaCallback)
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        try {
            unbindService(bleConnection)
        } catch (_: Exception) {}
        super.onListenerDisconnected()
    }
}
