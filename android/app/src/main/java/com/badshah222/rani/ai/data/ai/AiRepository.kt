package com.badshah222.rani.ai.data.ai

import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.data.supabase.SupabaseApi
import com.badshah222.rani.ai.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Provider switching + per-user keys + usage reporting. There is NO shared developer key anywhere. Chat runs only on the user's own keys (Gemini / Groq / OpenRouter). */
class AiRepository(private val store: SecureStore, private val cloud: SupabaseApi) {
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
     * Detects which MYRA Live model this key offers — MYRA's order (aria.py
     * LIVE_MODELS): 3.1 Flash Live first (MYRA's default), then 2.5 Flash
     * Native Audio, 2.5 Native Audio (Dec 2025 Preview), then 3.8 Live.
     * Stores both the MYRA key (e.g. "3.1") and the model id. Returns the
     * model id, or null when the key offers no Live model — the app then
     * uses the classic voice mode.
     */
    suspend fun detectLiveModel(): String? {
        val key = store.apiKey(Provider.GEMINI) ?: return null
        val gemini = clients.getValue(Provider.GEMINI) as GeminiClient
        val live = runCatching { gemini.listLiveModels(key) }.getOrDefault(emptyList())
        store.liveCandidates = live.joinToString(",")
        val picked = LiveModels.firstAvailable(live)
        store.liveModelKey = picked?.key ?: LiveModels.DEFAULT_KEY
        store.liveModel = picked?.modelId
        return picked?.modelId
    }

    /** The composed system instruction for the current personality + emotion + identity settings. */
    fun systemPrompt(emotionStyle: String): String = SystemPrompt.build(
        Personalities.fromId(store.personalityId).prompt,
        emotionStyle,
        store.language,
        store.assistantName,
        store.responseStyle,
    )

    suspend fun chat(
        history: List<ChatMessage>,
        emotionStyle: String = "Speak warmly and heartily, like a close friend who is genuinely glad to talk.",
        systemOverride: String? = null,
    ): AiReply {
        val p = store.provider
        val key = store.apiKey(p) ?: throw AiException("no_key", "no key", "Add your ${p.label} API key in Settings to start chatting.")
        val model = store.model(p) ?: throw AiException("no_model", "no model", "Test your ${p.label} connection and choose a model in Settings.")
        return try {
            val reply = client(p).chat(key, model, systemOverride ?: systemPrompt(emotionStyle), history.takeLast(20))
            cloud.recordUsage(p.id, model, "chat", true, reply.inputTokens, reply.outputTokens, reply.latencyMs.toInt(), null)
            reply
        } catch (e: AiException) {
            cloud.recordUsage(p.id, model, "chat", false, 0, 0, null, e.code)
            throw e
        }
    }

    /**
     * Vision (MYRA MediaPage camera vision / Screen Guardian): describe an image
     * with Gemini. The image bytes are sent to Google with the user's own key
     * and never stored anywhere.
     */
    suspend fun describeImage(bytes: ByteArray, question: String): String {
        val key = store.apiKey(Provider.GEMINI) ?: throw AiException(
            "no_key", "no gemini key",
            "Vision ke liye pehle Settings → Connections mein apni Google Gemini key lagao, Babu Jaan.",
        )
        val model = "gemini-2.5-flash"
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
                if (!res.isSuccessful) throw AiException("vision_${res.code}", bodyStr.take(160), "Vision ne jawab nahi diya (HTTP ${res.code}). Key check karo, Babu Jaan.")
                val text = JSONObject(bodyStr).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text").orEmpty()
                if (text.isBlank()) throw AiException("vision_empty", "empty", "Vision ne kuch nahi bataya.")
                text.trim()
            }
        }
    }

    /**
     * Image generation with the user's own Gemini key (MYRA Image Studio behavior):
     * the Gemini image model returns the picture as inline data. The caller falls
     * back to the free Pollinations image URL when this throws.
     */
    suspend fun generateImage(prompt: String): ByteArray {
        val key = store.apiKey(Provider.GEMINI) ?: throw AiException(
            "no_key", "no gemini key",
            "Image banane ke liye pehle Settings → API Keys mein apni Google Gemini key lagao, Babu Jaan.",
        )
        val model = "gemini-2.5-flash-image"
        val t0 = System.currentTimeMillis()
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
                if (!res.isSuccessful) throw AiException("image_${res.code}", bodyStr.take(160), "Gemini image ne jawab nahi diya (HTTP ${res.code}). Key check karo ya thodi dair baad try karo, Babu Jaan.")
                val parts = JSONObject(bodyStr).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                var b64: String? = null
                if (parts != null) for (i in 0 until parts.length()) {
                    b64 = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!b64.isNullOrBlank()) break
                }
                if (b64.isNullOrBlank()) throw AiException("image_empty", "empty", "Gemini ne image nahi banayi. Prompt badal kar dobara try karo, Babu Jaan.")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }
        cloud.recordUsage("gemini", model, "image", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
        return bytes
    }

    /**
     * Image understanding (screen share, photos) via the user's own Gemini key —
     * the same key Live voice needs. Direct HTTP to the official Gemini endpoint.
     */
    suspend fun vision(prompt: String, imageBase64: String, mimeType: String = "image/jpeg"): AiReply {
        val key = store.apiKey(Provider.GEMINI) ?: throw AiException(
            "no_key", "no gemini key",
            "Screen samajhne ke liye pehle Settings → Connections mein apni Google Gemini API key lagao, Babu Jaan — yehi key Live voice ke liye bhi chahiye hoti hai.",
        )
        val model = store.model(Provider.GEMINI)?.takeIf { it.contains("flash") || it.contains("pro") } ?: "gemini-2.5-flash"
        val t0 = System.currentTimeMillis()
        val payload = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                systemPrompt("Speak warmly and heartily, like a close friend who is genuinely glad to talk.")))))
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mimeType).put("data", imageBase64))))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key).header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw AiException("vision_${res.code}", bodyStr.take(160), "Gemini vision ne jawab nahi diya (HTTP ${res.code}). Key check karo ya thodi dair baad try karo, Babu Jaan.")
                val parts = JSONObject(bodyStr).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                sb.toString()
            }
        }
        if (text.isBlank()) throw AiException("vision_empty", "empty", "Screen parh li, lekin Gemini ne kuch bataya nahi. Dobara try karo, Babu Jaan.")
        cloud.recordUsage("gemini", model, "vision", true, 0, 0, (System.currentTimeMillis() - t0).toInt(), null)
        return AiReply(text.trim(), Provider.GEMINI, model, 0, 0, System.currentTimeMillis() - t0)
    }
}
