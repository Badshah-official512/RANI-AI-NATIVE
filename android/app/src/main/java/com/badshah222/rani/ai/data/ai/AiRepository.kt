package com.badshah222.rani.ai.data.ai

import com.badshah222.rani.ai.core.MemoryStore
import com.badshah222.rani.ai.core.RaniRemoteConfig
import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.data.supabase.SupabaseApi
import com.badshah222.rani.ai.data.worker.WorkerApi
import com.badshah222.rani.ai.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Provider switching + per-user keys + usage reporting. There is NO shared developer key anywhere. Chat runs only on the user's own keys (Gemini / Groq / OpenRouter). */
class AiRepository(private val store: SecureStore, private val cloud: SupabaseApi, private val remoteConfig: RaniRemoteConfig? = null, private val worker: WorkerApi? = null, private val memory: MemoryStore? = null) {
    private val clients = mapOf(Provider.GEMINI to GeminiClient(), Provider.GROQ to GroqClient(), Provider.OPENROUTER to OpenRouterClient())
    private fun client(p: Provider) = clients.getValue(p)

    fun isConfigured(p: Provider) = store.apiKey(p) != null && store.model(p) != null

    /** Tests the key with a real request and returns the models it may use. */
    suspend fun testConnection(p: Provider, key: String): List<String> {
        require(key.isNotBlank()) { "empty key" }
        val models = client(p).listModels(key.trim())
        if (models.isEmpty()) throw AiException("no_models", "no models", "The key works but no chat models are available on this account.")
        return models
    }

    /**
     * Detects which Live model this key offers — BINDING ORDER (2026-11-26
     * 20:17 PKT): ONLY gemini-3.8-live, one request, no other models.
     * Returns "gemini-3.8-live" when the key offers it, null otherwise —
     * never stores another model id. Stores both the model key ("3.8")
     * and the model id. Returns null when the key offers no Live model —
     * the app then uses the classic voice mode.
     */
    suspend fun detectLiveModel(): String? {
        val key = store.apiKey(Provider.GEMINI) ?: return null
        val gemini = clients.getValue(Provider.GEMINI) as GeminiClient
        val live = runCatching { gemini.listLiveModels(key) }.getOrDefault(emptyList())
        store.liveCandidates = live.joinToString(",")
        // F6 (v1.8.0 fix): accept ONLY gemini-3.8-live — LiveModels.all has
        // a single entry, and the takeIf below makes the acceptance explicit
        // here too, so no other model id can ever be stored.
        val picked = LiveModels.firstAvailable(live)?.takeIf { it.modelId == "gemini-3.8-live" }
        store.liveModelKey = picked?.key ?: LiveModels.DEFAULT_KEY
        store.liveModel = picked?.modelId
        return picked?.modelId
    }

    /** The composed system instruction for the current personality + emotion + identity settings. */
    fun systemPrompt(emotionStyle: String): String {
        // v3.0.0 §23.1 item 28: the active AI Mode's prompt prefix ("" = none).
        val mode = com.badshah222.rani.ai.domain.RaniModes.fromId(store.modeId)
        val base0 = SystemPrompt.build(
            Personalities.fromId(store.personalityId).prompt,
            emotionStyle,
            store.language,
            store.assistantName,
            store.responseStyle,
            store.petName,
        )
        val base = if (mode != null) base0 + "\n\nCurrent mode — ${mode.title}: ${mode.prompt}" else base0
        // v1.9.0 §21.2 — the MEMORY block: same recall block the Live call
        // gets via &memory=. Omitted entirely when memory is empty.
        // F6: the header uses the STORED pet name, like the prompt itself.
        val recall = memory?.buildRecallBlock().orEmpty()
        return if (recall.isBlank()) base else base + "\n\nThings you remember about ${store.petName}:\n" + recall +
            "\nUse these naturally — do not recite them, and never say \"according to my memory\". " +
            "A fact you are unsure of, confirm gently instead of asserting it."
    }

    /**
     * Chat — BADSHAH system first: the owner's pooled Groq key via the Worker
     * (Supabase `api_keys`, provider groq — no user key needed, multi-turn
     * works because persona + recent history are composed into the request).
     * The Worker is single-turn server-side, so the persona and the last few
     * turns travel inside the one message. Falls back to the user's own
     * provider/key (Gemini / Groq / OpenRouter) when the Worker is down,
     * rate-limited, or returns nothing — chat never hard-depends on it.
     */
    suspend fun chat(
        history: List<ChatMessage>,
        emotionStyle: String = "Speak warmly and heartily, like a close friend who is genuinely glad to talk.",
        systemOverride: String? = null,
    ): AiReply {
        val system = systemOverride ?: systemPrompt(emotionStyle)
        val t0 = System.currentTimeMillis()
        worker?.let { w ->
            val text = runCatching { w.chatGroq(composeWorkerChat(system, history)) }.getOrNull()
            if (!text.isNullOrBlank()) {
                val reply = AiReply(text.trim(), Provider.GROQ, WorkerApi.GROQ_MODEL, 0, 0, System.currentTimeMillis() - t0)
                cloud.recordUsage("groq", WorkerApi.GROQ_MODEL, "chat", true, 0, 0, reply.latencyMs.toInt(), null)
                return reply
            }
        }
        val p = store.provider
        val key = store.apiKey(p) ?: throw AiException("no_key", "no key", "Add your ${p.label} API key in Settings to start chatting.")
        // Panel default (gemini group, seed "gemini-3.8-flash") when the user has not picked a model yet.
        val model = store.model(p) ?: (if (p == Provider.GEMINI) remoteConfig?.geminiModel() else null)
            ?: throw AiException("no_model", "no model", "Test your ${p.label} connection and choose a model in Settings.")
        return try {
            val reply = client(p).chat(key, model, system, history.takeLast(20))
            cloud.recordUsage(p.id, model, "chat", true, reply.inputTokens, reply.outputTokens, reply.latencyMs.toInt(), null)
            reply
        } catch (e: AiException) {
            cloud.recordUsage(p.id, model, "chat", false, 0, 0, null, e.code)
            throw e
        }
    }

    /** Composes persona + recent turns into the single message the Worker sends
     *  to Groq. Keeps the last 8 turns; drops the oldest turns first when the
     *  text would pass 6000 chars (far inside the Worker's 65000-char limit).
     *  The trailing "answer as RANI" line matters: without it Groq sometimes
     *  introduces itself as BADSHAH AI; a NEGATIVE "you are NOT BADSHAH AI"
     *  makes gpt-oss refuse outright — so the identity is stated positively
     *  only, at the end where the model weighs it most. */
    private fun composeWorkerChat(system: String, history: List<ChatMessage>): String {
        val pet = store.petName
        val turns = history.takeLast(8).toMutableList()
        while (turns.size > 1) {
            val q = buildString {
                append(system.trim()).append("\n\n")
                append("Recent conversation (you are RANI, the user is $pet):\n")
                for (m in turns) {
                    append(if (m.role == "assistant") "RANI: " else "$pet: ")
                        .append(m.content.trim()).append("\n")
                }
                append("\nNow answer as RANI, warmly and lovingly, in the same language as $pet.")
            }
            if (q.length <= 6000) return q
            turns.removeAt(0)
        }
        return buildString {
            append(system.trim()).append("\n\n")
            for (m in turns) {
                append(if (m.role == "assistant") "RANI: " else "$pet: ")
                    .append(m.content.trim()).append("\n")
            }
            append("\nNow answer as RANI, warmly and lovingly, in the same language as $pet.")
        }.take(6000)
    }

    /**
     * Vision (MYRA MediaPage camera vision / Screen Guardian): describe an image
     * with Gemini. The image bytes are sent to Google with the user's own key
     * and never stored anywhere.
     */
    suspend fun describeImage(bytes: ByteArray, question: String): String {
        val key = store.apiKey(Provider.GEMINI) ?: throw AiException(
            "no_key", "no gemini key",
            "To use vision, first add your Google Gemini key in Settings > Connections.",
        )
        val model = store.model(Provider.GEMINI)?.takeIf { it.contains("flash") || it.contains("pro") }
            ?: remoteConfig?.geminiModel() ?: "gemini-2.5-flash"
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", question))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64))))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key).header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw AiException("vision_${res.code}", bodyStr.take(160), "Vision did not answer (HTTP ${res.code}). Please check the key.")
                val text = JSONObject(bodyStr).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text").orEmpty()
                if (text.isBlank()) throw AiException("vision_empty", "empty", "Vision did not describe anything.")
                text.trim()
            }
        }
    }

    /**
     * Image generation — BADSHAH system first: the owner's pooled image system
     * via the Worker (Gemini pool, Pollinations fallback server-side), so no
     * user key is needed. Falls back to the user's own Gemini key
     * (gemini-2.5-flash-image), and the caller falls back to the free
     * Pollinations URL when this throws.
     */
    suspend fun generateImage(prompt: String): ByteArray {
        val t0 = System.currentTimeMillis()
        worker?.let { w ->
            val bytes = runCatching { w.image(prompt) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                cloud.recordUsage("gemini", WorkerApi.IMAGE_MODEL, "image", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
                return bytes
            }
        }
        val key = store.apiKey(Provider.GEMINI) ?: throw AiException(
            "no_key", "no gemini key",
            "To create an image, first add your Google Gemini key in Settings > API Keys.",
        )
        val model = "gemini-2.5-flash-image"
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key).header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val bytes = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw AiException("image_${res.code}", bodyStr.take(160), "Gemini image did not answer (HTTP ${res.code}). Please check the key or try again shortly.")
                val parts = JSONObject(bodyStr).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                var b64: String? = null
                if (parts != null) for (i in 0 until parts.length()) {
                    b64 = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!b64.isNullOrBlank()) break
                }
                if (b64.isNullOrBlank()) throw AiException("image_empty", "empty", "Gemini did not create an image. Change the prompt and try again.")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }
        cloud.recordUsage("gemini", model, "image", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
        return bytes
    }

    /**
     * Video generation — BADSHAH system: the owner's pooled video system via
     * the Worker (long-running job: start, then poll until done). The job
     * takes a few minutes; this suspends while polling (15 s apart, up to
     * ~7 minutes). Returns the finished video bytes (mp4). Throws AiException
     * when the job cannot start (the pooled key cannot run video) or does not
     * finish in time — the caller shows the message and the user can retry.
     */
    suspend fun generateVideo(prompt: String): ByteArray {
        val w = worker ?: throw AiException("no_worker", "no worker", "Video needs the pooled video system, which is not available right now.")
        val t0 = System.currentTimeMillis()
        val name = w.startVideo(prompt)
            ?: throw AiException("video_start", "video did not start", "Video abhi start nahi ho saka — the pooled key cannot run video right now. Try again later, or ask for an image instead.")
        // Poll until done (video takes a few minutes).
        repeat(28) {
            delay(15_000)
            val poll = runCatching { w.videoPoll(name) }.getOrNull() ?: return@repeat
            // Pollinations answers the poll with the finished MP4 itself (raw bytes).
            poll.videoBytes?.let { bytes ->
                if (bytes.isNotEmpty()) {
                    cloud.recordUsage("gemini", WorkerApi.VIDEO_MODEL, "video", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
                    return bytes
                }
            }
            val status = poll.status ?: return@repeat
            if (status.optBoolean("done")) {
                val bytes = w.videoBytes(status)
                if (bytes != null && bytes.isNotEmpty()) {
                    cloud.recordUsage("gemini", WorkerApi.VIDEO_MODEL, "video", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
                    return bytes
                }
                throw AiException("video_empty", "video empty", "The video finished but came back empty. Try a different prompt.")
            }
            if (status.has("error")) {
                throw AiException("video_error", status.optString("error").take(160), "Video failed: ${status.optString("error").take(120)}")
            }
        }
        throw AiException("video_timeout", "video timeout", "Video abhi tayyar nahi hui (7 minutes) — it is still rendering. Try again in a bit.")
    }

    /**
     * Image understanding (screen share, photos) via the user's own Gemini key —
     * the same key Live voice needs. Direct HTTP to the official Gemini endpoint.
     */
    suspend fun vision(prompt: String, imageBase64: String, mimeType: String = "image/jpeg"): AiReply {
        val key = store.apiKey(Provider.GEMINI) ?: throw AiException(
            "no_key", "no gemini key",
            "To understand the screen, first add your Google Gemini API key in Settings > Connections — the same key Live voice needs.",
        )
        val model = store.model(Provider.GEMINI)?.takeIf { it.contains("flash") || it.contains("pro") }
            ?: remoteConfig?.geminiModel() ?: "gemini-2.5-flash"
        val t0 = System.currentTimeMillis()
        val payload = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                systemPrompt("Speak warmly and heartily, like a close friend who is genuinely glad to talk.")))))
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", mimeType).put("data", imageBase64))))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key).header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw AiException("vision_${res.code}", bodyStr.take(160), "Gemini vision did not answer (HTTP ${res.code}). Please check the key or try again shortly.")
                val parts = JSONObject(bodyStr).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                sb.toString()
            }
        }
        if (text.isBlank()) throw AiException("vision_empty", "empty", "I read the screen, but Gemini said nothing about it. Try again.")
        cloud.recordUsage("gemini", model, "vision", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
        return AiReply(text.trim(), Provider.GEMINI, model, 0, 0, System.currentTimeMillis() - t0)
    }
}
