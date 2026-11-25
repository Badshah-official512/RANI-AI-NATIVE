package com.badshah222.rani.ai.data.voice

import android.util.Base64
import com.badshah222.rani.ai.domain.AiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech — Google's own premium voices (Journey, Chirp, Studio),
 * far more natural than the phone's built-in engine. Uses the user's OWN Google Cloud
 * API key (Settings → Google Voice). MP3 bytes out, played by TtsManager.
 */
class GoogleTtsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun synthesize(apiKey: String, voiceName: String, text: String): ByteArray = withContext(Dispatchers.IO) {
        val lang = voiceName.split("-").take(2).joinToString("-").ifBlank { "en-US" }
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", lang).put("name", voiceName))
            .put("audioConfig", JSONObject().put("audioEncoding", "MP3"))
            .toString()
        val req = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val res = client.newCall(req).execute()
        val raw = res.body?.string().orEmpty()
        if (!res.isSuccessful) {
            val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()?.take(140)
            throw AiException("google_tts", "http ${res.code}", "Google Voice: ${msg ?: "key ya voice ka masla hai."}")
        }
        val b64 = JSONObject(raw).optString("audioContent")
        if (b64.isBlank()) throw AiException("google_tts_empty", "empty audio", "Google Voice ne khaali jawab diya.")
        Base64.decode(b64, Base64.DEFAULT)
    }
}

object GoogleVoices {
    // Female voices only — RANI speaks with a girl's voice, like MYRA's Aoede.
    val all = listOf(
        "en-US-Journey-F" to "Journey F — natural female (English)",
        "ur-PK-Standard-A" to "Urdu female",
    )
}
