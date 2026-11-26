package com.badshah222.rani.ai.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.domain.LiveModels
import com.badshah222.rani.ai.domain.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Gemini Live voice engine — the heart of the new RANI AI.
 *
 * A real-time WebSocket conversation with Google's Gemini Live API: the phone streams
 * microphone PCM (16 kHz) and the model streams back NATIVE audio (24 kHz) in one of
 * the prebuilt human voices (see LiveVoices). This is the same technology the reference
 * apps (LIA / MYRA) use — no robotic on-device text-to-speech anywhere in this path.
 * Barge-in works: when the user starts talking over the assistant, the server sends
 * "interrupted" and playback is cut instantly. If anything about Live is unavailable
 * (no Gemini key, no live model for the key, connection failure), [onUnavailable] fires
 * and the caller falls back to the classic listen→think→speak pipeline.
 */
class LiveSession(private val context: Context, private val store: SecureStore) {

    enum class State { Idle, Connecting, Listening, Thinking, Speaking, Error }

    private val _state = MutableStateFlow(State.Idle); val state: StateFlow<State> = _state
    private val _level = MutableStateFlow(0f); val level: StateFlow<Float> = _level
    private val _userText = MutableStateFlow(""); val userText: StateFlow<String> = _userText
    private val _assistantText = MutableStateFlow(""); val assistantText: StateFlow<String> = _assistantText
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    /** What the engine is trying right now ("Trying gemini-3.1-flash-live-preview · attempt 1 of 8") — shown on the call screen so a stuck call never looks silent. */
    private val _attemptInfo = MutableStateFlow(""); val attemptInfo: StateFlow<String> = _attemptInfo
    /** The last server/network reason a Live attempt failed — shown on the call screen and in Settings. */
    private val _lastError = MutableStateFlow<String?>(null); val lastError: StateFlow<String?> = _lastError
    val muted = MutableStateFlow(false)

    /** Fired once per completed exchange with (user said, assistant said). */
    var onTurn: ((user: String, assistant: String) -> Unit)? = null
    /** Fired when Live cannot run at all — the caller should use the classic voice mode. */
    var onUnavailable: ((reason: String) -> Unit)? = null

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    @Volatile private var running = false
    @Volatile private var setupDone = false
    @Volatile private var reconnects = 0
    /** One connection attempt: a model + a setup variant + an API version. The engine walks
     *  this list until the server accepts a setup — different Live models accept different
     *  setup fields (native-audio models reject an explicit languageCode, for example). */
    private data class Attempt(val model: String, val level: Int, val version: String)
    private var attempts: List<Attempt> = emptyList()
    @Volatile private var attempt = 0
    @Volatile private var attemptToken = 0
    @Volatile private var advancing = false
    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    @Volatile private var micThread: Thread? = null
    private var track: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playerRunning = false
    private var systemInstruction: String = ""
    private var sessionKey: String = ""
    /** Latest session-resumption handle from the server (MYRA-style long-call survival). */
    @Volatile private var resumeHandle: String? = null
    private var userBuf = StringBuilder()
    private var assistantBuf = StringBuilder()
    @Volatile private var turnOpen = false

    val isActive: Boolean get() = running

    /** When the current Live session started (ms) — shown in the Settings live-session panel (MYRA). */
    @Volatile var sessionStartedAtMs: Long = 0L
        private set

    fun start(systemInstruction: String) {
        if (running) return
        val key = store.apiKey(Provider.GEMINI)
        if (key == null) { onUnavailable?.invoke("Add your Google Gemini API key in Settings to use the real voice."); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onUnavailable?.invoke("Microphone permission is needed for the voice call."); return
        }
        this.systemInstruction = systemInstruction
        sessionKey = key
        running = true; setupDone = false; reconnects = 0; attempt = 0; advancing = false; resumeHandle = null
        sessionStartedAtMs = System.currentTimeMillis()
        _error.value = null; _userText.value = ""; _assistantText.value = ""
        setCommMode(true)
        // MYRA Live models (aria.py LIVE_MODELS): the selected model first
        // (default "3.1" — Gemini 3.1 Flash Live), then the other MYRA models
        // in MYRA's order as fallbacks. Each model is tried with the full
        // setup first, then a bare setup (native-audio models reject some fields).
        val selected = LiveModels.fromKey(store.liveModelKey)
        // The model that worked last time connects first (fast connect), then the
        // selected model, then the rest — every model in this list is real.
        val lastWorking = store.liveModel?.let { LiveModels.fromModelId(it) }
        val ordered = (listOfNotNull(lastWorking, selected) + LiveModels.all).distinctBy { it.key }
        attempts = ordered.flatMap { m ->
            listOf(Attempt(m.modelId, 0, m.version), Attempt(m.modelId, 3, m.version))
        }
        connect(key, attempts[0])
    }

    /** A connect attempt failed before the session was ready: try the next attempt (model/setup variant), or give up with the real reason. */
    private fun nextOrFail(key: String, reason: String) {
        _lastError.value = reason
        if (!running || setupDone || advancing) return
        advancing = true
        runCatching { ws?.close(1000, "retry") }
        if (attempt + 1 < attempts.size) {
            attempt++
            advancing = false
            connect(key, attempts[attempt])
        } else {
            fail(reason)
        }
    }

    private fun connect(key: String, att: Attempt) {
        _state.value = State.Connecting
        setupDone = false
        // Watchdog: a hung handshake must never leave the screen on "Connecting…" forever.
        _attemptInfo.value = "Trying ${att.model} · attempt ${attempt + 1} of ${attempts.size}"
        val tok = ++attemptToken
        thread(isDaemon = true) {
            Thread.sleep(10000)
            if (running && !setupDone && tok == attemptToken) {
                nextOrFail(key, "Live connection did not start in 10 seconds (model: ${att.model}). The internet is slow, or this model did not run on the key — test it in Settings → Live Voice.")
            }
        }
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.${att.version}.GenerativeService.BidiGenerateContent?key=$key"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sendSetup(att) }
            override fun onMessage(webSocket: WebSocket, text: String) { handleMessage(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running || tok != attemptToken) return
                if (!setupDone) { nextOrFail(key, "Live connection fail ho gaya (model: ${att.model}): ${t.message?.take(90) ?: "network error"}"); return }
                if (reconnects < 8) { reconnects++; Thread.sleep(minOf(600L * reconnects, 3000L)); if (running) connect(key, att) }
                else fail("Live voice connection failed. Check your internet and try again.")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!running || tok != attemptToken) return
                // Any close before the session is ready is a failed attempt — move on at
                // once instead of waiting for the watchdog (a 1000 close used to hang here).
                if (!setupDone) { nextOrFail(key, "Live band ho gaya (model: ${att.model}, code $code ${reason.take(60)})"); return }
                if (setupDone && reconnects < 8 && code != 1008) { reconnects++; if (running) connect(key, att) }
                else if (code == 1008 || code == 1007) fail("The live voice was refused for this key or model. Open Settings → Live Voice and press Test to pick a working model.")
            }
        })
    }

    private fun sendSetup(att: Attempt) {
        val voice = store.liveVoiceName
        if (att.level == 0) {
            // MYRA-style setup (the shape the open-source MYRA app connects with):
            // context-window compression + session resumption keep long calls alive,
            // snake_case generation/speech config, and NO languageCode anywhere —
            // native-audio models choose the language themselves and reject it.
            val resumption = JSONObject()
            resumeHandle?.let { resumption.put("handle", it) }
            val gen = JSONObject()
                .put("temperature", 0.7)
                .put("response_modalities", JSONArray().put("AUDIO"))
                .put("speech_config", JSONObject().put("voice_config",
                    JSONObject().put("prebuilt_voice_config", JSONObject().put("voice_name", voice))))
            val setup = JSONObject()
                .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                .put("sessionResumption", resumption)
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put("model", "models/${att.model}")
                .put("generation_config", gen)
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            ws?.send(JSONObject().put("setup", setup).toString())
            return
        }
        // Backup variants (camelCase): 1 = full with languageCode, 2 = no languageCode,
        // 3 = bare (no languageCode, no transcription requests).
        val speech = JSONObject()
            .put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))
        if (att.level == 1) speech.put("languageCode", store.language)
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speech)
        val setup = JSONObject()
            .put("model", "models/${att.model}")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
        if (att.level < 3) {
            setup.put("inputAudioTranscription", JSONObject())
            setup.put("outputAudioTranscription", JSONObject())
        }
        ws?.send(JSONObject().put("setup", setup).toString())
    }

    private fun handleMessage(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        root.optJSONObject("error")?.let { e ->
            val msg = "Live voice error: ${e.optString("message").take(140)}"
            // A rejection before the session is ready is just another failed attempt — climb the ladder.
            if (!setupDone) nextOrFail(sessionKey, msg) else fail(msg)
            return
        }
        if (root.has("setupComplete")) {
            setupDone = true
            store.lastLiveError = null
            // Remember the model, setup variant AND API version that actually worked,
            // so the next call connects first try.
            attempts.getOrNull(attempt)?.let { store.liveModel = it.model; store.liveSetupLevel = it.level; store.liveVersion = it.version }
            _state.value = State.Listening
            startMic(); startPlayer()
            return
        }
        // Session resumption: the server hands us a handle; reconnects reuse it so a
        // long call survives the server's session limits without losing context.
        root.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable")) u.optString("newHandle").takeIf { it.isNotEmpty() }?.let { resumeHandle = it }
        }
        if (root.has("goAway")) {
            // Server is about to close this session — reconnect at once with the handle.
            if (running && setupDone && reconnects < 8) {
                reconnects++
                val att = attempts.getOrNull(attempt) ?: return
                setupDone = false
                runCatching { ws?.close(1000, "goAway") }
                thread(isDaemon = true) { Thread.sleep(400); if (running) connect(sessionKey, att) }
            }
            return
        }
        val sc = root.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            userBuf.append(it); _userText.value = userBuf.toString(); turnOpen = true
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (_state.value != State.Speaking) _state.value = State.Thinking
            assistantBuf.append(it); _assistantText.value = assistantBuf.toString(); turnOpen = true
        }
        if (sc.optBoolean("interrupted")) {
            audioQueue.clear()
            track?.pause(); track?.flush(); track?.play()
            if (_state.value == State.Speaking) _state.value = State.Listening
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) {
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: continue
                    audioQueue.offer(bytes)
                    if (_state.value != State.Speaking) _state.value = State.Speaking
                }
            }
        }
        if (sc.optBoolean("turnComplete")) {
            // Wait for the playback queue to drain, then close the turn out.
            thread(isDaemon = true) {
                var waited = 0
                while (running && audioQueue.isNotEmpty() && waited < 8000) { Thread.sleep(120); waited += 120 }
                Thread.sleep(350)
                if (!running) return@thread
                if (_state.value == State.Speaking) _state.value = State.Listening
                val u = userBuf.toString().trim(); val a = assistantBuf.toString().trim()
                if (turnOpen && (u.isNotEmpty() || a.isNotEmpty())) onTurn?.invoke(u, a)
                userBuf = StringBuilder(); assistantBuf = StringBuilder()
                _userText.value = ""; _assistantText.value = ""
                turnOpen = false
            }
        }
    }

    // ---- microphone → Live ----
    private fun startMic() {
        if (micThread != null) return
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
        } catch (e: Exception) { fail("Microphone could not start."); return }
        recorder = rec
        rec.startRecording()
        micThread = thread(isDaemon = true) {
            val buf = ByteArray(2560) // 80 ms of 16 kHz PCM16
            while (running) {
                val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) { Thread.sleep(30); continue }
                var sum = 0.0
                var i = 0
                while (i + 1 < n) { val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8); sum += s * s; i += 2 }
                _level.value = (Math.sqrt(sum / (n / 2)) / 9000.0).toFloat().coerceIn(0f, 1f)
                if (muted.value) continue
                val chunk = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
                val msg = JSONObject().put("realtimeInput",
                    JSONObject().put("audio", JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", chunk)))
                ws?.send(msg.toString())
            }
        }
    }

    // ---- Live → speaker ----
    private fun startPlayer() {
        if (playerRunning) return
        val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t; t.play(); playerRunning = true
        thread(isDaemon = true) {
            while (running) {
                val chunk = try { audioQueue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
                if (chunk == null) continue
                var sum = 0.0
                var i = 0
                while (i + 1 < chunk.size) { val s = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8); sum += s * s; i += 2 }
                if (_state.value == State.Speaking) _level.value = (Math.sqrt(sum / (chunk.size / 2)) / 7000.0).toFloat().coerceIn(0f, 1f)
                try { t.write(chunk, 0, chunk.size) } catch (e: Exception) { break }
            }
            playerRunning = false
        }
    }

    fun setMuted(m: Boolean) { muted.value = m }

    fun stop() {
        running = false; setupDone = false
        micThread = null
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        audioQueue.clear()
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        ws?.close(1000, "user ended"); ws = null
        _level.value = 0f
        _attemptInfo.value = ""
        if (_state.value != State.Error) _state.value = State.Idle
        setCommMode(false)
    }

    private fun fail(message: String) {
        _error.value = message
        _lastError.value = message
        _attemptInfo.value = ""
        _state.value = State.Error
        val wasRunning = running
        stop()
        _state.value = State.Error
        if (wasRunning) onUnavailable?.invoke(message)
    }

    private var prevCallVolume = -1

    private fun setCommMode(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            // Boss wants the voice LOUD: push the call stream to max for the session.
            runCatching {
                prevCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            }
        } else {
            runCatching { am.mode = AudioManager.MODE_NORMAL }
            if (prevCallVolume >= 0) { runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevCallVolume, 0) }; prevCallVolume = -1 }
        }
    }
}
