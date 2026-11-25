package com.badshah222.rani.ai.data.ai

import com.badshah222.rani.ai.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
private val JSON = "application/json; charset=utf-8".toMediaType()

internal suspend fun execute(req: Request, provider: String): Pair<Int, String> = withContext(Dispatchers.IO) {
    try { http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") } }
    catch (e: IOException) { throw AiException("network", "$provider network error: ${e.javaClass.simpleName}", "No internet connection or $provider is unreachable. Check your network and try again.") }
}

internal fun mapHttpError(provider: Provider, code: Int, body: String): AiException {
    val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(160)
    return when (code) {
        400 -> if (detail.contains("API key", true)) AiException("invalid_key", detail, "${provider.label} rejected this API key. Check it and try again.")
               else AiException("bad_request", detail, "${provider.label} could not process the request. ${detail}".trim())
        401, 403 -> AiException("invalid_key", detail, "${provider.label} rejected this API key (HTTP $code). Create a new key and paste it again.")
        404 -> AiException("model_not_found", detail, "The selected model is not available for this key. Pick another model in Settings.")
        429 -> AiException("rate_limited", detail, "${provider.label} says you are sending too many requests or your free quota is used up. Wait a bit or switch provider.")
        in 500..599 -> AiException("server_error", detail, "${provider.label} is having problems right now (HTTP $code). Try again shortly.")
        else -> AiException("http_$code", detail, "Unexpected response from ${provider.label} (HTTP $code).")
    }
}

interface AiClient {
    val provider: Provider
    /** Real call to the provider: also serves as the connection test. */
    suspend fun listModels(apiKey: String): List<String>
    suspend fun chat(apiKey: String, model: String, system: String, history: List<ChatMessage>): AiReply
}

class GeminiClient : AiClient {
    override val provider = Provider.GEMINI
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun listModels(apiKey: String): List<String> {
        val (code, body) = execute(Request.Builder().url("$base/models?pageSize=200").header("x-goog-api-key", apiKey).build(), "Gemini")
        if (code != 200) throw mapHttpError(provider, code, body)
        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") }.filter { it.startsWith("gemini") }.sortedDescending()
    }

    /**
     * Models that can run a Gemini Live (bidirectional audio) session for this key.
     * Detected from the key's own model list — never invented. Best first:
     * native-audio models, then other live models.
     */
    suspend fun listLiveModels(apiKey: String): List<String> {
        val (code, body) = execute(Request.Builder().url("$base/models?pageSize=200").header("x-goog-api-key", apiKey).build(), "Gemini")
        if (code != 200) throw mapHttpError(provider, code, body)
        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val live = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "bidiGenerateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") }
        return live.sortedWith(compareByDescending<String> { it.contains("native-audio") }.thenByDescending { it.contains("live") }.thenByDescending { it })
    }

    override suspend fun chat(apiKey: String, model: String, system: String, history: List<ChatMessage>): AiReply {
        val contents = JSONArray()
        history.forEach { m -> contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", m.content)))) }
        val payload = JSONObject().put("contents", contents)
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val t0 = System.currentTimeMillis()
        val (code, body) = execute(Request.Builder().url("$base/models/$model:generateContent").header("x-goog-api-key", apiKey).post(payload.toString().toRequestBody(JSON)).build(), "Gemini")
        if (code != 200) throw mapHttpError(provider, code, body)
        val j = JSONObject(body)
        val text = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
        if (text.isBlank()) {
            val why = j.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw AiException("empty_reply", "empty Gemini reply $why", if (why.isNotEmpty()) "Gemini blocked this request ($why). Try rephrasing." else "Gemini returned an empty answer. Try again.")
        }
        val u = j.optJSONObject("usageMetadata")
        return AiReply(text.trim(), provider, model, u?.optLong("promptTokenCount") ?: 0, u?.optLong("candidatesTokenCount") ?: 0, System.currentTimeMillis() - t0)
    }
}

/**
 * OpenRouter — one free account key, many free models (Llama 3.3, DeepSeek, Qwen, Gemini
 * experimental…). listModels returns only the genuinely free (":free") models, live from
 * OpenRouter's own catalogue, so the list never goes stale.
 */
class OpenRouterClient : AiClient {
    override val provider = Provider.OPENROUTER
    private val base = "https://openrouter.ai/api/v1"

    override suspend fun listModels(apiKey: String): List<String> {
        val (code, body) = execute(Request.Builder().url("$base/models").header("Authorization", "Bearer $apiKey").build(), "OpenRouter")
        if (code != 200) throw mapHttpError(provider, code, body)
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            .filter { it.endsWith(":free") }.sorted()
    }

    override suspend fun chat(apiKey: String, model: String, system: String, history: List<ChatMessage>): AiReply {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.7)
        val t0 = System.currentTimeMillis()
        val (code, body) = execute(Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer $apiKey").post(payload.toString().toRequestBody(JSON)).build(), "OpenRouter")
        if (code != 200) throw mapHttpError(provider, code, body)
        val j = JSONObject(body)
        val text = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isBlank()) throw AiException("empty_reply", "empty OpenRouter reply", "OpenRouter returned an empty answer. Try again.")
        val u = j.optJSONObject("usage")
        return AiReply(text.trim(), provider, model, u?.optLong("prompt_tokens") ?: 0, u?.optLong("completion_tokens") ?: 0, System.currentTimeMillis() - t0)
    }
}

class GroqClient : AiClient {
    override val provider = Provider.GROQ
    private val base = "https://api.groq.com/openai/v1"

    override suspend fun listModels(apiKey: String): List<String> {
        val (code, body) = execute(Request.Builder().url("$base/models").header("Authorization", "Bearer $apiKey").build(), "Groq")
        if (code != 200) throw mapHttpError(provider, code, body)
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            .filterNot { id -> listOf("whisper", "guard", "tts", "playai", "distil-whisper").any { id.contains(it, true) } }.sorted()
    }

    override suspend fun chat(apiKey: String, model: String, system: String, history: List<ChatMessage>): AiReply {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.7)
        val t0 = System.currentTimeMillis()
        val (code, body) = execute(Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer $apiKey").post(payload.toString().toRequestBody(JSON)).build(), "Groq")
        if (code != 200) throw mapHttpError(provider, code, body)
        val j = JSONObject(body)
        val text = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isBlank()) throw AiException("empty_reply", "empty Groq reply", "Groq returned an empty answer. Try again.")
        val u = j.optJSONObject("usage")
        return AiReply(text.trim(), provider, model, u?.optLong("prompt_tokens") ?: 0, u?.optLong("completion_tokens") ?: 0, System.currentTimeMillis() - t0)
    }
}
