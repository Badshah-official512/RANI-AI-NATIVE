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
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.core.CallService
import com.badshah222.rani.ai.core.FloatingOrbService
import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.data.worker.WorkerApi
import com.badshah222.rani.ai.domain.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Gemini Live voice over HTTP streaming — the v1.5.0 call engine.
 *
 * WHY HTTP: the phone's native WebSockets are PROVEN blocked — OkHttp
 * (v1.1.0–v1.2.2) AND Java-WebSocket (v1.4.0) both time out at 15 s on this
 * phone, while plain HTTP works fine (chat / image / video all go through
 * the Worker) and the browser's WebSocket works. So the phone talks to the
 * Cloudflare Worker over a plain HTTP POST stream and the WORKER holds the
 * Google Live WebSocket:
 *   phone --POST /api/live-http (mic PCM16 up, framed audio/text down)--> Worker
 *   Worker --wss--> Google Live (setup, mediaChunks, greeting, audio back)
 * NO WebSocket on the phone, NO web page. The response body is a stream of
 * length-prefixed frames: [4-byte big-endian length][1-byte type][payload]
 *   type 0x01 = model audio (raw PCM16, 24 kHz)
 *   type 0x02 = text JSON {kind: "userText"|"assistantText"|"status", text}
 *   type 0x03 = error/end JSON {kind: "error"|"ended", message}
 * The Worker also sends a "ping" status frame every 15 s; if no frame at all
 * arrives for 35 s after connecting, the stream is silently hung and the
 * session reconnects (same model, up to 8 times) instead of leaving the user
 * on a dead call. Voice is smooth because audio is forwarded as raw bytes —
 * no base64 / JSON on the phone for the mic path (the Worker does that).
 *
 * This mirrors LiveSession's public interface EXACTLY (same State enum —
 * it reuses LiveSession.State — same flows, same callbacks) so VoiceScreen
 * works unchanged. LiveSession (Java-WebSocket) stays in the codebase as the
 * Settings-test / fallback engine; it is NOT the call transport anymore.
 */
class HttpLiveSession(private val context: Context, private val store: SecureStore, private val worker: WorkerApi? = null) {

    // Reuses LiveSession.State so VoiceScreen's `when (liveState)` branches
    // (LiveSession.State.Connecting / Listening / Thinking / Speaking / Error / Idle)
    // compile unchanged against this session.
    private val _state = MutableStateFlow(LiveSession.State.Idle); val state: StateFlow<LiveSession.State> = _state
    private val _level = MutableStateFlow(0f); val level: StateFlow<Float> = _level
    private val _userText = MutableStateFlow(""); val userText: StateFlow<String> = _userText
    private val _assistantText = MutableStateFlow(""); val assistantText: StateFlow<String> = _assistantText
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    /** What the engine is trying right now ("Trying gemini-3.8-live via HTTP") — shown on the call screen so a stuck call never looks silent. */
    private val _attemptInfo = MutableStateFlow(""); val attemptInfo: StateFlow<String> = _attemptInfo
    /** The last server/network reason a Live attempt failed — shown on the call screen and in Settings. */
    private val _lastError = MutableStateFlow<String?>(null); val lastError: StateFlow<String?> = _lastError
    val muted = MutableStateFlow(false)

    /** Fired once per completed exchange with (user said, assistant said). */
    var onTurn: ((user: String, assistant: String) -> Unit)? = null
    /** Fired when Live cannot run at all — the caller should use the classic voice mode. */
    var onUnavailable: ((reason: String) -> Unit)? = null

    // Dedicated HTTP client for the Live stream. Read/write timeouts are 0
    // (no timeout) — the response streams for as long as the user talks and
    // the mic streams for as long as the call runs. The 15 s watchdog and the
    // heartbeat liveness check (not OkHttp) own failure detection. The shared
    // WorkerApi client (60 s read timeout) is NOT used here — it would cut a
    // quiet call off mid-sentence.
    private val liveHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var setupDone = false
    @Volatile private var reconnects = 0
    @Volatile private var attemptToken = 0
    @Volatile private var advancing = false
    /** Last time any frame (audio, text or ping) arrived — the liveness check. */
    @Volatile private var lastFrameAtMs = 0L
    private var sessionKey = ""
    private var systemInstruction = ""
    private var voice = "Aoede"
    private var recorder: AudioRecord? = null
    @Volatile private var micThread: Thread? = null
    private var track: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playerRunning = false
    /** Mic PCM chunks waiting to be written into the HTTP request body. */
    private val micQueue = LinkedBlockingQueue<ByteArray>()
    /** Poison pill: offered to micQueue on stop() so the body writer exits. */
    private val END_MARKER = ByteArray(0)
    private var httpCall: Call? = null
    private var userBuf = StringBuilder()
    private var assistantBuf = StringBuilder()
    @Volatile private var turnOpen = false
    /** True when this call started the overlay service (user had the orb off). */
    private var callStartedOverlay = false

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
        setupDone = false; reconnects = 0; attemptToken = 0; advancing = false
        lastFrameAtMs = System.currentTimeMillis()
        sessionStartedAtMs = System.currentTimeMillis()
        _error.value = null; _userText.value = ""; _assistantText.value = ""
        setCommMode(true)
        this.systemInstruction = systemInstruction
        this.voice = store.liveVoiceName
        // Call keep-alive: the microphone foreground service keeps the app
        // process (and the HTTP stream + mic) alive when the user minimizes
        // the app or switches to another app. Started only from a user tap.
        runCatching { CallService.start(context) }
        // Floating call window: the overlay service hosts the call card that
        // stays visible over other apps while the call is active.
        ensureCallOverlay()
        // BADSHAH system: the Live key is fetched FRESH at call time from the
        // owner's pool (/api/live-key, Firebase-login gated) — exactly how the
        // WebSocket engine did it. The user's own Gemini key (Settings) is the
        // fallback when the pool is unreachable or the user is not signed in.
        // The fetch runs on a worker thread (≤9 s) so the call screen never freezes.
        thread(isDaemon = true) {
            val pooled = runCatching {
                runBlocking {
                    withTimeoutOrNull(9_000) { worker?.liveKey() }
                }
            }.getOrNull()
            val key = pooled ?: store.apiKey(Provider.GEMINI)
            if (key == null) {
                running = false
                setCommMode(false)
                runCatching { CallService.stop(context) }
                onUnavailable?.invoke("Add your Google Gemini API key in Settings to use the real voice (or sign in so the pooled key can be used).")
                return@thread
            }
            // The user may have ended the call while the key was being fetched.
            if (!running) { setCommMode(false); runCatching { CallService.stop(context) }; return@thread }
            sessionKey = key
            // v1.5.0 — BOSS'S ORDER (2026-11-26): ONLY gemini-3.8-live, ONE
            // request, no ladder, no other models. There is exactly ONE HTTP
            // attempt; if the stream drops mid-call it reconnects the SAME
            // model (up to 8 times). If it fails, the error shows at once and
            // the call ends; the boss taps call again to retry.
            connectHttp(key)
        }
    }

    /** Start the overlay service for the call window when the overlay
     *  permission is granted. If the user never enabled the floating orb,
     *  the service is stopped again when the call ends (see stop()). */
    private fun ensureCallOverlay() {
        if (!Settings.canDrawOverlays(context)) return
        callStartedOverlay = !FloatingOrbService.running && !store.floatingOrbEnabled
        runCatching { FloatingOrbService.start(context) }
    }

    /** The HTTP stream dropped mid-call (after connecting): reconnect the SAME
     *  model (gemini-3.8-live, boss's order) up to 8 times, then give up with
     *  the real reason. Guarded by `advancing` so two threads (read loop +
     *  liveness check) cannot start two reconnects at once. */
    @Synchronized
    private fun reconnect(key: String, reason: String) {
        if (!running || advancing) return
        advancing = true
        if (reconnects >= 8) { fail(reason); return }
        reconnects++
        _lastError.value = reason
        _attemptInfo.value = "Reconnecting… ($reconnects/8)"
        _state.value = LiveSession.State.Connecting
        setupDone = false
        // Stop the old HTTP call so its threads exit, then start fresh.
        runCatching { httpCall?.cancel() }
        micQueue.clear()
        thread(isDaemon = true) {
            Thread.sleep(600)
            advancing = false
            if (running) connectHttp(key)
        }
    }

    private fun connectHttp(key: String) {
        _state.value = LiveSession.State.Connecting
        setupDone = false
        lastFrameAtMs = System.currentTimeMillis()
        _attemptInfo.value = "Trying gemini-3.8-live via HTTP · attempt 1 of 1"
        val tok = ++attemptToken
        // Watchdog: a hung handshake must never leave the screen on "Connecting…" forever.
        // 15 s — the boss's 4G is slow, so a working model gets room to finish its setup.
        thread(isDaemon = true) {
            Thread.sleep(15000)
            if (running && !setupDone && tok == attemptToken) {
                fail("Live connection did not start in 15 seconds (model: gemini-3.8-live). The internet is slow, or this model did not run on the key — test it in Settings → Live Voice.")
            }
        }
        // Liveness: the Worker sends a "ping" status frame every 15 s. If no
        // frame at all arrives for 35 s after connecting, the stream is
        // silently hung — reconnect instead of leaving the user on a dead call
        // where RANI looks connected but never speaks.
        thread(isDaemon = true) {
            while (running && tok == attemptToken) {
                Thread.sleep(5000)
                if (running && setupDone && tok == attemptToken &&
                    System.currentTimeMillis() - lastFrameAtMs > 35000
                ) {
                    reconnect(key, "Live connection went quiet — reconnecting…")
                    return@thread
                }
            }
        }
        // Voice + persona travel as query params (the Worker builds the Live
        // setup from them); the key travels in the X-Gemini-Key header.
        val url = "${WorkerApi.BASE_URL}/api/live-http" +
            "?voice=" + URLEncoder.encode(voice, "UTF-8") +
            "&persona=" + URLEncoder.encode(systemInstruction, "UTF-8")
        // Streaming request body: the mic thread offers raw PCM chunks into
        // micQueue as they are recorded; writeTo drains them into the HTTP
        // body (chunked) as they arrive. writeTo runs on OkHttp's dispatcher
        // thread and blocks on the queue — that is the stream.
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                try {
                    while (true) {
                        val chunk = micQueue.take() // blocks until mic data or END_MARKER
                        if (chunk === END_MARKER) break
                        if (chunk.isEmpty()) continue
                        sink.write(chunk)
                        sink.flush()
                    }
                } catch (e: InterruptedException) {
                    // Call cancelled while waiting for mic data.
                } catch (e: IOException) {
                    // Socket closed while writing (call ended / network dropped).
                }
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("X-Gemini-Key", key)
            .header("Accept", "application/octet-stream")
            .post(requestBody)
            .build()
        val call = liveHttp.newCall(request)
        httpCall = call
        // Execute on a worker thread. The response headers arrive immediately
        // (the Worker returns the stream at once); the body streams for the
        // whole call. Read frames until the stream ends or the call stops.
        thread(isDaemon = true, name = "http-live") {
            try {
                val response = call.execute()
                if (!running || tok != attemptToken) { runCatching { response.close() }; return@thread }
                if (!response.isSuccessful) {
                    val msg = "Live voice error (HTTP ${response.code})."
                    runCatching { response.close() }
                    if (!setupDone) fail(msg) else reconnect(key, msg)
                    return@thread
                }
                val body = response.body
                if (body == null) {
                    runCatching { response.close() }
                    if (!setupDone) fail("Live voice error (empty response).") else reconnect(key, "Live connection ended.")
                    return@thread
                }
                // The call is live from here: start mic + player so the user's
                // first words are already streaming when RANI greets (the
                // Worker queues mic data until its Google setup is sent, so
                // nothing is lost).
                startMic(); startPlayer()
                readFrames(body.byteStream(), key, tok)
                // readFrames returned (stream ended).
                runCatching { response.close() }
                if (!running || tok != attemptToken) return@thread
                if (advancing) return@thread // a reconnect is already in progress
                if (!setupDone) {
                    fail("Live connection did not start in 15 seconds (model: gemini-3.8-live). The internet is slow, or this model did not run on the key — test it in Settings → Live Voice.")
                } else {
                    reconnect(key, "Live connection ended.")
                }
            } catch (e: IOException) {
                if (!running || tok != attemptToken) return@thread
                if (!setupDone) {
                    fail("Live connection fail ho gaya (model: gemini-3.8-live): ${(e.message ?: "network error").take(90)}")
                } else {
                    reconnect(key, "Live connection dropped.")
                }
            } catch (e: Exception) {
                if (!running || tok != attemptToken) return@thread
                if (!setupDone) fail("Live connection fail ho gaya (model: gemini-3.8-live): ${(e.message ?: "network error").take(90)}")
                else reconnect(key, "Live connection dropped.")
            }
        }
    }

    /** Read length-prefixed frames until the stream ends. Returns when the
     *  stream ends (EOF / error) — the caller then fails or reconnects. */
    private fun readFrames(input: InputStream, key: String, tok: Int) {
        val header = ByteArray(4)
        while (running && tok == attemptToken) {
            if (!readExact(input, header, 4)) return // EOF
            val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (len < 1 || len > 4 * 1024 * 1024) return // corrupt frame — drop the stream
            val frame = ByteArray(len)
            if (!readExact(input, frame, len)) return // EOF mid-frame
            lastFrameAtMs = System.currentTimeMillis()
            val type = frame[0].toInt() and 0xFF
            val payload = frame.copyOfRange(1, len)
            when (type) {
                0x01 -> {
                    // Model audio (raw PCM16, 24 kHz) — straight to the player queue.
                    audioQueue.offer(payload)
                    if (_state.value != LiveSession.State.Speaking) _state.value = LiveSession.State.Speaking
                }
                0x02 -> handleTextFrame(payload)
                0x03 -> {
                    // Error / end — the Worker closed the call.
                    val msg = runCatching { JSONObject(String(payload, Charsets.UTF_8)).optString("message").takeIf { it.isNotBlank() } }.getOrNull()
                        ?: "Live connection ended."
                    if (!setupDone) fail(msg) else reconnect(key, msg)
                    return
                }
                else -> { /* unknown frame type — ignore */ }
            }
        }
    }

    private fun readExact(input: InputStream, buf: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = try { input.read(buf, off, n - off) } catch (e: IOException) { return false }
            if (r < 0) return false
            off += r
        }
        return true
    }

    private fun handleTextFrame(payload: ByteArray) {
        val obj = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val kind = obj.optString("kind")
        val text = obj.optString("text")
        when (kind) {
            "connected" -> {
                // Google confirmed the setup and RANI is about to greet.
                setupDone = true
                _attemptInfo.value = ""
                _state.value = LiveSession.State.Listening
                store.lastLiveError = null
                store.liveModel = "gemini-3.8-live"
            }
            "userText" -> {
                if (text.isNotEmpty()) { userBuf.append(text); _userText.value = userBuf.toString(); turnOpen = true }
            }
            "assistantText" -> {
                if (text.isNotEmpty()) {
                    if (_state.value != LiveSession.State.Speaking) _state.value = LiveSession.State.Thinking
                    assistantBuf.append(text); _assistantText.value = assistantBuf.toString(); turnOpen = true
                }
            }
            "status" -> {
                when (text) {
                    "connected" -> {
                        // v1.5.0 FIX (review): the Worker sends the connect signal as
                        // {kind:"status", text:"connected"} — NOT kind:"connected". Without
                        // this case setupDone never became true and the 15 s watchdog
                        // killed every call even though Google had connected.
                        setupDone = true
                        _attemptInfo.value = ""
                        _state.value = LiveSession.State.Listening
                        store.lastLiveError = null
                        store.liveModel = "gemini-3.8-live"
                    }
                    "ping" -> { /* heartbeat — lastFrameAtMs already updated */ }
                    "interrupted" -> {
                        // Barge-in: the user started talking over RANI — cut playback at once.
                        audioQueue.clear()
                        track?.pause(); track?.flush(); track?.play()
                        if (_state.value == LiveSession.State.Speaking) _state.value = LiveSession.State.Listening
                    }
                    "turnComplete" -> {
                        // Wait for the playback queue to drain, then close the turn out.
                        thread(isDaemon = true) {
                            var waited = 0
                            while (running && audioQueue.isNotEmpty() && waited < 8000) { Thread.sleep(120); waited += 120 }
                            Thread.sleep(350)
                            if (!running) return@thread
                            if (_state.value == LiveSession.State.Speaking) _state.value = LiveSession.State.Listening
                            val u = userBuf.toString().trim(); val a = assistantBuf.toString().trim()
                            if (turnOpen && (u.isNotEmpty() || a.isNotEmpty())) onTurn?.invoke(u, a)
                            userBuf = StringBuilder(); assistantBuf = StringBuilder()
                            _userText.value = ""; _assistantText.value = ""
                            turnOpen = false
                        }
                    }
                }
            }
        }
    }

    // ---- microphone → HTTP body ----
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
                // Raw PCM straight into the HTTP body queue — no base64, no JSON
                // on the phone (the Worker does the base64 + mediaChunks framing).
                // 80 ms chunks keep the voice smooth with minimal latency. The
                // queue is capped (~16 s) so a stalled network cannot build a
                // huge backlog that would play out late.
                if (micQueue.size > 200) micQueue.poll()
                micQueue.offer(buf.copyOf(n))
            }
        }
    }

    // ---- HTTP audio frames → speaker ----
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
                if (_state.value == LiveSession.State.Speaking) _level.value = (Math.sqrt(sum / (chunk.size / 2)) / 7000.0).toFloat().coerceIn(0f, 1f)
                try { t.write(chunk, 0, chunk.size) } catch (e: Exception) { break }
            }
            playerRunning = false
        }
    }

    fun setMuted(m: Boolean) { muted.value = m }

    fun stop() {
        running = false; setupDone = false
        micThread = null
        // Unblock the HTTP body writer so the POST finishes cleanly, then
        // cancel the call so the read loop exits too.
        micQueue.clear()
        micQueue.offer(END_MARKER)
        runCatching { httpCall?.cancel() }
        httpCall = null
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        audioQueue.clear()
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        _level.value = 0f
        _attemptInfo.value = ""
        if (_state.value != LiveSession.State.Error) _state.value = LiveSession.State.Idle
        setCommMode(false)
        runCatching { CallService.stop(context) }
        // If this call started the overlay service (user had the orb off),
        // stop it again so no unwanted orb is left behind.
        if (callStartedOverlay && !store.floatingOrbEnabled) runCatching { FloatingOrbService.stop(context) }
        callStartedOverlay = false
    }

    private fun fail(message: String) {
        _error.value = message
        _lastError.value = message
        _attemptInfo.value = ""
        _state.value = LiveSession.State.Error
        val wasRunning = running
        stop()
        _state.value = LiveSession.State.Error
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
