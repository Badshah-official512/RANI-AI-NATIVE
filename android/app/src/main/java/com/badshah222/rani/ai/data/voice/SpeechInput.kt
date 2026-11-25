package com.badshah222.rani.ai.data.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice recognition via Android's SpeechRecognizer. Must be used from the main thread.
 *
 * Reliability rules (learned from real devices):
 *  - Prefer the ON-DEVICE recognizer (API 31+) — it never hits the "server disconnected"
 *    (code 11) class of failures and works offline for downloaded languages.
 *  - Transient service errors (busy / network / server / disconnected) are retried
 *    automatically a few times with a short pause instead of surfacing as raw errors.
 */
class SpeechInput(private val context: Context) {
    private var rec: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false); val listening: StateFlow<Boolean> = _listening
    private val _level = MutableStateFlow(0f); val level: StateFlow<Float> = _level   // 0..1 microphone loudness

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retries = 0
    private var useOnDevice = false
    private var languageTag = "en-US"
    private var cbPartial: (String) -> Unit = {}
    private var cbResult: (String) -> Unit = {}
    private var cbError: (String) -> Unit = {}

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        if (!isAvailable()) { onError("This phone has no speech-recognition service. Install or enable Google (Speech Services) and try again."); return }
        this.languageTag = languageTag; this.cbPartial = onPartial; this.cbResult = onResult; this.cbError = onError
        retries = 0
        useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        startInternal()
    }

    private fun startInternal() {
        destroyRec()
        val r = if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { _level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { _listening.value = false; _level.value = 0f }
            override fun onError(error: Int) {
                _listening.value = false; _level.value = 0f
                val transient = error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_SERVER ||
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                if (transient && retries < 3) {
                    retries++
                    if (useOnDevice && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) useOnDevice = false  // fall back to the cloud recognizer
                    destroyRec()
                    val task = Runnable { startInternal() }
                    retryRunnable = task
                    handler.postDelayed(task, 700)
                    return
                }
                cbError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything. Tap the mic and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed. Allow it in Permissions."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet for this language."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again in a moment."
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_SERVER -> "The speech service hiccuped. Tap the mic and try again."
                    else -> "Speech recognition failed (code $error)."
                })
            }
            override fun onResults(res: Bundle?) { _listening.value = false; _level.value = 0f
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isBlank()) cbError("I did not catch that. Try again.") else cbResult(t) }
            override fun onPartialResults(p: Bundle?) { p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(cbPartial) }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        runCatching {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
            })
        }.onFailure { cbError("Speech recognition could not start. Tap the mic and try again.") }
    }

    private fun destroyRec() { rec?.runCatching { cancel(); destroy() }; rec = null }

    fun stop() {
        retryRunnable?.let { handler.removeCallbacks(it) }; retryRunnable = null
        destroyRec(); _listening.value = false; _level.value = 0f
    }
}
