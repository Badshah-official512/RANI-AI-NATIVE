package com.badshah222.rani.ai.data.worker

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The BADSHAH AI Cloudflare Worker (badshah222-ai.pages.dev) — the owner's
 * pooled API system, shared by the BADSHAH web app and RANI.
 *
 * Owner keys (Gemini / Groq) live in Supabase `api_keys` (RLS: no public
 * read). The Worker reads them with the service role, so the app NEVER
 * holds the pooled keys itself — it just calls these endpoints:
 *   GET  /api/live-key          pooled Gemini key for Live calls (Firebase login required)
 *   GET  /api/chat?provider=groq&model=...&q=...   Groq chat via the pooled key
 *   GET  /api/image?q=...&model=...                image (Gemini pool, Pollinations fallback)
 *   POST /api/video {prompt, model}                video job start (long-running)
 *   GET  /api/video?name=<operation>               video job status (poll until done)
 *
 * Every call here is fail-soft (returns null on any error) — the caller
 * always falls back to the user's own key / the free engine.
 *
 * NOTE (2026-11-26): the live Worker is NEWER than the local copy in
 * ~/workspace/badshah-ai-web-v4/_worker.js — image falls back to
 * Pollinations ("keyless_route") and video runs on NovAI/CogVideoX
 * (provider "novai"), not Gemini Veo. The code below handles both the
 * data-URI and the plain-URL forms for images, and several possible
 * shapes for a finished video job.
 */
class WorkerApi(private val tokenProvider: (suspend () -> String?)? = null) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()

    companion object {
        const val BASE_URL = "https://badshah222-ai.pages.dev"
        const val GROQ_MODEL = "openai/gpt-oss-120b"
        const val IMAGE_MODEL = "gemini-3.1-flash-image"
        const val VIDEO_MODEL = "gemini-omni-1.1-flash"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(url: String, bearer: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val rb = Request.Builder().url(url)
            if (bearer != null) rb.header("Authorization", "Bearer $bearer")
            http.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.string()
            }
        }.getOrNull()
    }

    private suspend fun postJson(url: String, json: String, bearer: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val rb = Request.Builder().url(url)
                .header("Content-Type", "application/json")
            if (bearer != null) rb.header("Authorization", "Bearer $bearer")
            val req = rb
                .post(json.toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.string()
            }
        }.getOrNull()
    }

    private suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.bytes()
            }
        }.getOrNull()
    }

    /** v3.0.0 §23.1 P2 item 11 — ONE fail-soft method for the free-tools
     *  suite (same pattern as chatGroq). `path` is the part after
     *  /api/tools/, e.g. "weather?city=Swabi". Returns the parsed JSON or
     *  null when the endpoint is down — the caller then shows COMING_SOON,
     *  never a fake answer. Verified live 2026-11-28: weather, joke, fact,
     *  meme, ask, wiki, shorten, lyrics-search, lyrics-get. */
    suspend fun tool(path: String): JSONObject? {
        val body = withTimeoutOrNull(30_000) { get("$BASE_URL/api/tools/$path") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** v3.0.0 §23.1 item 18: News — GET /api/news (verified live 200). */
    suspend fun news(): JSONObject? {
        val body = withTimeoutOrNull(30_000) { get("$BASE_URL/api/news") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** v3.0.0 §23.1 item 26: Dost Room — GET/POST /api/dost?room= (Firebase
     *  token required; the endpoint answers 401 without one, so it is live
     *  and auth-gated, not dead). RANI uses its own room name. */
    suspend fun dostGet(room: String): JSONObject? {
        val token = tokenProvider?.invoke() ?: return null
        val body = withTimeoutOrNull(30_000) { get("$BASE_URL/api/dost?room=${enc(room)}", token) } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    suspend fun dostPost(room: String, name: String, text: String): Boolean {
        val token = tokenProvider?.invoke() ?: return false
        val payload = JSONObject().put("name", name).put("text", text).toString()
        val body = withTimeoutOrNull(30_000) { postJson("$BASE_URL/api/dost?room=${enc(room)}", payload, token) } ?: return false
        return runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(false)
    }

    /** v3.0.0 §23.1 item 19: Namaz times — direct public Aladhan API
     *  (no Worker needed; BADSHAH calls it directly). City default Swabi. */
    suspend fun namaz(city: String): JSONObject? {
        val body = withTimeoutOrNull(30_000) {
            get("https://api.aladhan.com/v1/timingsByCity?city=${enc(city)}&country=Pakistan&method=1")
        } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** v3.0.0 §23.1 item 13: TikTok fallback — the Worker /api/tools/tiktok
     *  endpoint exists but is rate-limited (429 "limit" on 2026-11-28), so
     *  the screen tries the Worker first, then tikwm.com directly (BADSHAH's
     *  own fallback). */
    suspend fun tikwm(url: String): JSONObject? {
        val body = withTimeoutOrNull(30_000) { get("https://tikwm.com/api/?url=${enc(url)}") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** Pooled Gemini key for the Live call — BADSHAH's system: the key is
     *  fetched fresh at call time from /api/live-key (Firebase login gated),
     *  never stored on the phone. Returns null when not signed in or the
     *  pool has no key — the caller then uses the user's own Gemini key. */
    suspend fun liveKey(): String? {
        val token = tokenProvider?.invoke() ?: return null
        val body = withTimeoutOrNull(9_000) { get("$BASE_URL/api/live-key", token) } ?: return null
        return runCatching { JSONObject(body).optString("key").takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** Groq chat via the owner's pooled key (Supabase `api_keys`, provider groq).
     *  Single-turn on the server side — the caller composes persona + recent
     *  history into `q` (max 65000 chars server-side). Returns the reply text. */
    suspend fun chatGroq(q: String, model: String = GROQ_MODEL): String? {
        val body = withTimeoutOrNull(60_000) {
            get("$BASE_URL/api/chat?provider=groq&model=${enc(model)}&q=${enc(q)}")
        } ?: return null
        return runCatching { JSONObject(body).optString("text").takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** Image via the owner's pooled system (Gemini pool, Pollinations fallback).
     *  The Worker returns either a data URI (base64 image) or a plain image URL.
     *  Returns the image bytes, or null when nothing came back. */
    suspend fun image(prompt: String, model: String = IMAGE_MODEL): ByteArray? {
        val body = withTimeoutOrNull(90_000) {
            get("$BASE_URL/api/image?q=${enc(prompt)}&model=${enc(model)}")
        } ?: return null
        val img = runCatching { JSONObject(body).optString("image") }.getOrNull().orEmpty()
        if (img.isBlank()) return null
        return imageFieldToBytes(img)
    }

    /** Turns the Worker's `image` field into bytes: a data URI, a base64 blob,
     *  or a plain http(s) URL that is downloaded. */
    private suspend fun imageFieldToBytes(img: String): ByteArray? {
        val s = img.trim()
        if (s.startsWith("data:")) {
            val b64 = s.substringAfter(",", "")
            if (b64.isBlank()) return null
            return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        }
        if (s.startsWith("http://") || s.startsWith("https://")) return downloadBytes(s)
        // Bare base64 (no data: prefix).
        return runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull()
    }

    /** Starts a video job on the owner's pooled video system. Returns the
     *  operation name to poll (e.g. "nov:..."), or null when it could not start
     *  (the pooled key cannot run video, or the server is busy). */
    suspend fun startVideo(prompt: String, model: String = VIDEO_MODEL): String? {
        val payload = JSONObject().put("prompt", prompt.take(1000)).put("model", model).toString()
        val body = withTimeoutOrNull(60_000) { postJson("$BASE_URL/api/video", payload) } ?: return null
        return runCatching {
            val o = JSONObject(body)
            if (o.has("error")) null else o.optString("name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** One poll of a video job. `status` is the JSON status (NovAI/Agnes/Agent/
     *  Gemini: {"done":false} while running, {"done":true,"url":...} when done).
     *  `videoBytes` is set when the provider answers the poll with the finished
     *  video itself — Pollinations returns raw MP4 bytes, not JSON. Without this
     *  the poller saw "not JSON" forever and the video timed out after 7 minutes
     *  even though it was done. */
    data class VideoPoll(val status: JSONObject?, val videoBytes: ByteArray?)
    suspend fun videoPoll(name: String): VideoPoll {
        val bytes = withTimeoutOrNull(30_000) { downloadBytes("$BASE_URL/api/video?name=${enc(name)}") } ?: return VideoPoll(null, null)
        // MP4 files start with a 4-byte box size followed by "ftyp" — a raw video,
        // not a JSON status (Pollinations answers the poll with the finished MP4).
        if (bytes.size > 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() && bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()) {
            return VideoPoll(null, bytes)
        }
        val status = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        return VideoPoll(status, null)
    }

    /** Extracts the finished video from a done status object. Handles the
     *  shapes the Worker has returned: a plain URL, a data URI, bare base64,
     *  or a nested Gemini-style operation response. Returns bytes or null. */
    suspend fun videoBytes(status: JSONObject): ByteArray? {
        // Direct fields first.
        for (key in listOf("video", "videoUrl", "video_url", "url", "downloadUrl", "download_url", "data")) {
            val v = status.optString(key).takeIf { it.isNotBlank() } ?: continue
            imageFieldToBytes(v)?.let { return it }
        }
        // Nested Gemini operation style: response.generateVideoResponse.generatedSamples[0].video.uri
        // or response.videos[0].uri — a Google URI; download it plain (public video URIs work as-is).
        val resp = status.optJSONObject("response") ?: return null
        val samples = resp.optJSONObject("generateVideoResponse")?.optJSONArray("generatedSamples")
        if (samples != null && samples.length() > 0) {
            val uri = samples.optJSONObject(0)?.optJSONObject("video")?.optString("uri").orEmpty()
            if (uri.isNotBlank()) return downloadBytes(uri)
        }
        val videos = resp.optJSONArray("videos")
        if (videos != null && videos.length() > 0) {
            val uri = videos.optJSONObject(0)?.optString("uri").orEmpty()
            if (uri.isNotBlank()) return downloadBytes(uri)
        }
        val direct = resp.optString("video").takeIf { it.isNotBlank() }
        if (direct != null) return imageFieldToBytes(direct)
        return null
    }
}
