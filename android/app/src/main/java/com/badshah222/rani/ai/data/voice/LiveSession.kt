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
import com.badshah222.rani.ai.data.worker.WorkerApi
import com.badshah222.rani.ai.domain.LiveModels
import com.badshah222.rani.ai.domain.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
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
class LiveSession(private val context: Context, private val store: SecureStore, private val worker: WorkerApi? = null) {

    enum class State { Idle, Connecting, Listening, Thinking, Speaking, Error }

    private val _state = MutableStateFlow(State.Idle); val state: StateFlow<State> = _state
    private val _level = MutableStateFlow(0f); val level: StateFlow<Float> = _level
    private val _userText = MutableStateFlow(""); val userText: StateFlow<String> = _userText
    private val _assistantText = MutableStateFlow(""); val assistantText: StateFlow<String> = _assistantText
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    /** What the engine is trying right now ("Trying gemini-3.8-live · attempt 1 of 6") — shown on the call screen so a stuck call never looks silent. */
    private val _attemptInfo = MutableStateFlow(""); val attemptInfo: StateFlow<String> = _attemptInfo
    /** The last server/network reason a Live attempt failed — shown on the call screen and in Settings. */
    private val _lastError = MutableStateFlow<String?>(null); val lastError: StateFlow<String?> = _lastError
    val muted = MutableStateFlow(false)

    /** Fired once per completed exchange with (user said, assistant said). */
    var onTurn: ((user: String, assistant: String) -> Unit)? = null
    /** Fired when Live cannot run at all — the caller should use the classic voice mode. */
    var onUnavailable: ((reason: String) -> Unit)? = null

    // v1.2.3 — NO OkHttp for the Live socket. OkHttp's WebSocket failed on this
    // phone three times (v1.1.0 direct, v1.2.0 proxy+direct, v1.2.2 proxy with a
    // browser-identical handshake — every time the same silent 15 s hang), while
    // the browser's WebSocket and Python's both work on the same phone + key.
    // So the engine is now org.java-websocket: a raw java.net.Socket that sends
    // a plain HTTP/1.1 upgrade — no OkHttp HTTP stack, no HTTP/2, no connection
    // pool. OkHttp is still used for the app's HTTP calls (WorkerApi); only the
    // Live WebSocket moved off it.
    @Volatile private var running = false
    @Volatile private var setupDone = false
    @Volatile private var reconnects = 0
    /** One connection attempt: a model + a setup variant + an API version.
     *  v1.2.2 (boss's order) removed the ladder — there is exactly ONE attempt
     *  (gemini-3.8-live via the Worker proxy), so `level` is always 0 (full
     *  setup) and `proxy` is always true. The fields stay so the setup code
     *  and the "what worked" record keep working unchanged.
     *  v1.2.3: the socket engine is org.java-websocket (raw socket), NOT
     *  OkHttp — OkHttp's WebSocket hung silently on this phone three times
     *  (v1.1.0, v1.2.0, v1.2.2) while the browser's and Python's both work.
     *  The Worker proxy (wss://badshah222-ai.pages.dev/api/live, key in ?key=,
     *  browser-identical handshake) still holds the Google side. */
    private data class Attempt(val model: String, val level: Int, val version: String, val proxy: Boolean = false)
    private var attempts: List<Attempt> = emptyList()
    @Volatile private var attempt = 0
    @Volatile private var attemptToken = 0
    @Volatile private var advancing = false
    private var ws: WebSocketClient? = null
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onUnavailable?.invoke("Microphone permission is needed for the voice call."); return
        }
        // Claim the session synchronously so a double-tap cannot start two calls.
        running = true
        setupDone = false; reconnects = 0; attempt = 0; advancing = false; resumeHandle = null
        sessionStartedAtMs = System.currentTimeMillis()
        _error.value = null; _userText.value = ""; _assistantText.value = ""
        setCommMode(true)
        this.systemInstruction = systemInstruction
        // BADSHAH system: the Live key is fetched FRESH at call time from the
        // owner's pool (/api/live-key, Firebase-login gated) — exactly how the
        // BADSHAH web app's Live works and speaks first. The user's own Gemini
        // key (Settings) is the fallback when the pool is unreachable or the
        // user is not signed in. The fetch runs on a worker thread (≤9 s) so
        // the call screen never freezes.
        thread(isDaemon = true) {
            val pooled = runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(9_000) { worker?.liveKey() }
                }
            }.getOrNull()
            val key = pooled ?: store.apiKey(Provider.GEMINI)
            if (key == null) {
                running = false
                setCommMode(false)
                onUnavailable?.invoke("Add your Google Gemini API key in Settings to use the real voice (or sign in so the pooled key can be used).")
                return@thread
            }
            // The user may have ended the call while the key was being fetched.
            if (!running) { setCommMode(false); return@thread }
            sessionKey = key
            // v1.2.2 — BOSS'S ORDER (2026-11-26 20:17 PKT): ONLY gemini-3.8-live,
            // ONE request, no ladder, no other models. The ladder (10 attempts
            // across 3 models) wasted up to 2.5 minutes on models the key does
            // not even offer (2.5 GA is not on his key; extended-thinking needs
            // thinkingLevel HIGH) while the call never connected. 3.8-live is
            // the default, the key offers it (Settings: "Live ready — Gemini
            // 3.8 Live (Default) is available"), and it is Google's recommended
            // Live model. So there is exactly ONE attempt: 3.8-live via the
            // Worker proxy (browser-identical handshake, ?key=) — the path
            // verified end-to-end (setupComplete in 0.2 s, RANI spoke). If it
            // fails, the error shows at once and the call ends; the boss taps
            // call again to retry. No silent cycling through dead models.
            val model38 = LiveModels.fromKey(LiveModels.DEFAULT_KEY)
            attempts = listOf(Attempt(model38.modelId, 0, model38.version, proxy = true))
            connect(key, attempts[0])
        }
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
        // 15 s — the boss's 4G is slow, so a working model gets room to finish its setup.
        _attemptInfo.value = "Trying ${att.model} via Cloudflare · attempt ${attempt + 1} of ${attempts.size}"
        val tok = ++attemptToken
        thread(isDaemon = true) {
            Thread.sleep(15000)
            if (running && !setupDone && tok == attemptToken) {
                nextOrFail(key, "Live connection did not start in 15 seconds (model: ${att.model}). The internet is slow, or this model did not run on the key — test it in Settings → Live Voice.")
            }
        }
        // v1.2.3 — JAVA-WEBSOCKET ENGINE, proxy only, single attempt (3.8-live).
        // v1.2.2 (OkHttp, browser-identical handshake) ALSO failed with the same
        // silent 15 s hang — the user confirmed the voice he hears is the
        // chat+TTS fallback, not Gemini's real Live voice. So the headers were
        // NOT the cause. OkHttp's WebSocket engine itself cannot open a socket
        // on this phone (to Google AND to Cloudflare), while the browser's
        // WebSocket and Python's both work. This engine (org.java-websocket)
        // opens a raw java.net.Socket and sends a plain HTTP/1.1 upgrade —
        // no OkHttp HTTP stack, no HTTP/2, no connection pool. The handshake
        // stays browser-identical (key in ?key=, Chrome UA + Origin + the
        // headers Chrome sends, no custom headers). The Worker relay at
        // badshah222-ai.pages.dev/api/live still holds the Google side.
        // Single attempt, gemini-3.8-live only (boss's order, v1.2.2) — if it
        // fails, the error shows at once and the call ends.
        val url = "wss://badshah222-ai.pages.dev/api/live?key=${java.net.URLEncoder.encode(key, "UTF-8")}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; TECNO KM4k) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Origin" to "https://badshah222-ai.pages.dev",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        val socket = object : WebSocketClient(URI(url), headers) {
            override fun onOpen(handshakedata: ServerHandshake?) { sendSetup(att) }
            override fun onMessage(message: String?) { if (message != null) handleMessage(message) }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (!running || tok != attemptToken) return
                // Any close before the session is ready is a failed attempt — move on at
                // once instead of waiting for the watchdog (a 1000 close used to hang here).
                if (!setupDone) { nextOrFail(key, "Live band ho gaya (model: ${att.model}, code $code ${(reason ?: "").take(60)})"); return }
                if (setupDone && reconnects < 8 && code != 1008) { reconnects++; if (running) connect(key, att) }
                else if (code == 1008 || code == 1007) fail("The live voice was refused for this key or model. Open Settings → Live Voice and press Test to pick a working model.")
            }
            override fun onError(ex: Exception?) {
                // Java-WebSocket calls onClose right after onError when the socket
                // dies, and onClose owns the reconnect — so onError only reports
                // a failure that never reached onClose (e.g. the handshake itself
                // was refused). The token/advancing guards stop double-handling.
                if (!running || tok != attemptToken) return
                if (!setupDone) { nextOrFail(key, "Live connection fail ho gaya (model: ${att.model}): ${(ex?.message ?: "network error").take(90)}"); return }
            }
        }
        // Disable Java-WebSocket's own ping-based liveness check — the app has its
        // own watchdog + reconnect logic, and the Live server drives the traffic.
        socket.connectionLostTimeout = 0
        ws = socket
        socket.connect()
    }

    private fun sendSetup(att: Attempt) {
        val voice = store.liveVoiceName
        if (att.level != 3) {
            // Full setup — used by level 0 (BADSHAH's ?key= way) and level 1
            // (the SDK's header way). The official google-genai SDK's EXACT
            // working setup (verified live 2026-11-26: setupComplete in 1.4 s,
            // RANI spoke first). Note the casing — camelCase throughout,
            // including inside speechConfig (voiceConfig / prebuiltVoiceConfig /
            // voiceName) — BADSHAH's app.html proves this shape; the snake_case
            // form (v1.6.0) never got setupComplete. Role:"user" on the system
            // instruction. No contextWindowCompression and no sessionResumption
            // unless resuming (the SDK omits both).
            // The extended-thinking model REQUIRES thinkingLevel HIGH (the server
            // closes 1007 without it); plain 3.8-live REJECTS it (1007 the other way).
            val gen = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
            if (LiveModels.fromModelId(att.model)?.thinking == true) {
                gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "HIGH"))
            }
            val sys = JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                .put("role", "user")
            val setup = JSONObject()
                .put("model", "models/${att.model}")
                .put("generationConfig", gen)
                .put("systemInstruction", sys)
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
            // Resume only when we actually hold a handle (the SDK omits the field otherwise).
            resumeHandle?.let { setup.put("sessionResumption", JSONObject().put("handle", it)) }
            ws?.send(JSONObject().put("setup", setup).toString())
            return
        }
        // Backup variant (level 3 = bare SDK shape, no system instruction): only used
        // if the full setup is ever rejected, so the ladder still has somewhere to go.
        // The speak-first greeting still tells RANI who she is (see setupComplete).
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        if (LiveModels.fromModelId(att.model)?.thinking == true) {
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "HIGH"))
        }
        val setup = JSONObject()
            .put("model", "models/${att.model}")
            .put("generationConfig", gen)
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
            // Speak FIRST, like BADSHAH AI: greet Babu Jaan the moment the call
            // connects, instead of waiting silently for him. The official SDK
            // sends this as snake_case client_content (verified live 2026-11-26:
            // RANI answered "Hi Babu Jaan! Call connect ho gayi! ..." in ~8 s).
            val greet = JSONObject().put("client_content", JSONObject()
                .put("turns", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text",
                        "(The call just connected. Greet me warmly right now, speak first, call me Babu Jaan, and ask how you can help — in a real girl's loving voice.)")))))
                .put("turnComplete", true))
            ws?.send(greet.toString())
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
                // v1.4.0 — BADSHAH's exact mic format: realtimeInput.mediaChunks
                // (an array of {mimeType, data}). The old realtimeInput.audio
                // object was never heard by the server — RANI could not hear
                // Babu Jaan, so no conversation ever started even when the
                // socket connected. BADSHAH's rani-voice.js + rani-live.html
                // both send mediaChunks; the native engine must match.
                val msg = JSONObject().put("realtimeInput",
                    JSONObject().put("mediaChunks", JSONArray().put(JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", chunk))))
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
