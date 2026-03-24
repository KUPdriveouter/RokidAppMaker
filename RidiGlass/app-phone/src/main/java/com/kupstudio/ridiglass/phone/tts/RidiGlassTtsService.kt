package com.kupstudio.ridiglass.phone.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.os.IBinder
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.kupstudio.ridiglass.phone.ble.BleServerService

/**
 * Custom TTS Engine that intercepts text from Ridibooks (or any app using TTS),
 * forwards it to the Rokid Glasses via BLE, and delegates actual speech synthesis
 * to the device's default TTS engine.
 */
class RidiGlassTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "RidiGlassTTS"
        private const val SAMPLE_RATE = 24000
    }

    private var bleService: BleServerService? = null
    private var delegateTts: TextToSpeech? = null
    private var delegateReady = false

    private val bleConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BleServerService.LocalBinder).service
            Log.i(TAG, "BLE service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Bind to BLE server
        val bleIntent = Intent(this, BleServerService::class.java)
        bindService(bleIntent, bleConnection, Context.BIND_AUTO_CREATE)

        // Initialize delegate TTS for actual speech output
        initDelegateTts()
    }

    override fun onDestroy() {
        delegateTts?.shutdown()
        try {
            unbindService(bleConnection)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun initDelegateTts() {
        // Find a TTS engine other than ourselves to delegate to
        delegateTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                delegateReady = true
                Log.i(TAG, "Delegate TTS ready")
            } else {
                Log.e(TAG, "Delegate TTS init failed: $status")
            }
        }

        // Pick an engine that is NOT us
        val engines = delegateTts?.engines ?: emptyList()
        val otherEngine = engines.firstOrNull {
            it.name != "com.kupstudio.ridiglass.phone"
        }
        if (otherEngine != null) {
            delegateTts?.shutdown()
            delegateTts = TextToSpeech(this, { status ->
                delegateReady = status == TextToSpeech.SUCCESS
            }, otherEngine.name)
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // Support Korean primarily, but accept any language
        return if (lang == "kor" || lang == "ko") {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("ko", "KR", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        delegateTts?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: request.text ?: return

        // 1. Send text to glasses via BLE
        bleService?.sendText(text)
        Log.d(TAG, "Forwarded to glasses: ${text.take(50)}...")

        // 2. Delegate actual speech synthesis
        if (delegateReady && delegateTts != null) {
            // Use the delegate TTS to speak — we synthesize silence and let delegate handle audio
            delegateTts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ridiglass_${System.currentTimeMillis()}")
        }

        // 3. Return silence to the calling app (Ridibooks) since delegate handles audio
        callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        // Send a small chunk of silence so the callback completes
        val silence = ByteArray(SAMPLE_RATE * 2) // 1 second of silence
        // Estimate duration based on text length (~150ms per character for Korean TTS)
        val estimatedDurationMs = (text.length * 150L).coerceIn(500, 30000)
        val silenceChunks = (estimatedDurationMs * SAMPLE_RATE * 2 / 1000).toInt()
        val chunkSize = SAMPLE_RATE * 2 // 1 second chunks
        var remaining = silenceChunks
        while (remaining > 0) {
            val size = minOf(remaining, chunkSize)
            val chunk = ByteArray(size)
            callback.audioAvailable(chunk, 0, size)
            remaining -= size
        }
        callback.done()
    }
}
