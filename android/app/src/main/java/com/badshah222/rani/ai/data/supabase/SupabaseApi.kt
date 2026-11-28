package com.badshah222.rani.ai.data.supabase

import android.util.Log
import com.badshah222.rani.ai.core.Config
import com.badshah222.rani.ai.data.ai.http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class CloudException(message: String, val status: Int = 0) : Exception(message)

data class TicketRow(val id: String, val subject: String, val status: String, val message: String, val updatedAt: String)
data class TicketReply(val sender: String, val message: String, val createdAt: String)
data class RemoteConvo(val id: String, val title: String, val updatedAt: String)
data class RemoteMsg(val role: String, val content: String)
data class VoiceProfileRow(val id: String, val label: String, val category: String, val pitch: Float, val speed: Float, val language: String)

/**
 * Talks to Supabase PostgREST using ONLY the public publishable key plus the signed-in user's
 * Firebase ID token. Row Level Security decides what each user may read/write.
 * [token] returns a Firebase ID token that carries role=authenticated, or null when cloud sync is unavailable.
 *
 * RANI contract (migration 015, RANI admin panel — see docs/SUPABASE-RANI-DATA.md):
 *  - Shared tables (conversations, messages, support_tickets, support_messages):
 *    every POST includes `app: "rani"` (optimistic; retried without the tag if the
 *    column does not exist yet — the migration may not be live).
 *  - RPCs take `p_app => 'rani'` (retried without it when the old signature is live).
 *  - Preferences live in RANI-only tables: rani_preferences / rani_provider_settings
 *    (never user_preferences / user_provider_settings — shared row, BADSHAH closed).
 *  - Every user action is logged (metadata only, never content) to rani_activity.
 *  - Screen recordings / voice-prints: metadata only (rani_screen_recordings /
 *    rani_voiceprints) — files and raw voice never leave the phone.
 *  - Feature switches + maintenance + announcement come from app_settings
 *    (app='rani'). All RANI-only writes are defensive: a missing table/column
 *    (migration not live yet) is logged and skipped — the app keeps working.
 */
class SupabaseApi(private val token: suspend () -> String?) {
    private val json = "application/json".toMediaType()

    companion object {
        /** RANI's app tag for shared tables and RPCs. */
        const val APP_TAG = "rani"

        /** Event types allowed by the rani_activity CHECK constraint (migration 015). */
        val ACTIVITY_EVENTS = setOf(
            "chat", "voice_call", "voice_minutes", "command",
            "youtube_search", "message_sent", "contacts_read",
            "screen_recording", "camera_event", "face_recognition",
            "voiceprint_enrol", "face_lock", "safety_alert", "awareness"
        )
    }

    /** True until PostgREST proves the `app` column is missing on a shared table (PGRST204). */
    @Volatile private var appColumnAvailable = true

    private fun log(msg: String) {
        Log.d("RaniCloud", msg)
    }

    private suspend fun call(method: String, path: String, body: String? = null, prefer: String? = null, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        val jwt = if (auth) token() ?: throw CloudException("Cloud sync is not available yet (sign in again or see Help > Troubleshooting).") else null
        val rb = Request.Builder().url("${Config.SUPABASE_URL}/rest/v1/$path")
            .header("apikey", Config.SUPABASE_KEY).header("Content-Type", "application/json")
        if (jwt != null) rb.header("Authorization", "Bearer $jwt")
        if (prefer != null) rb.header("Prefer", prefer)
        rb.method(method, body?.toRequestBody(json) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(json))
        try {
            http.newCall(rb.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw CloudException(runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty().ifBlank { "Server error ${r.code}" }, r.code)
                text
            }
        } catch (e: IOException) { throw CloudException("No connection to the server.") }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun rows(s: String) = if (s.isBlank()) JSONArray() else JSONArray(s)

    /** Adds the app tag to a shared-table write payload when the column is known to exist. */
    private fun JSONObject.withAppTag(): JSONObject {
        if (appColumnAvailable) put("app", APP_TAG)
        return this
    }

    /** POST a row to a SHARED table with the app tag when possible. On a "column app
     *  does not exist" error, retries once without the tag and remembers the column
     *  is missing — writes never hard-crash on a missing column. */
    private suspend fun postTable(path: String, body: JSONObject, prefer: String? = null): String {
        return try {
            call("POST", path, body.withAppTag().toString(), prefer)
        } catch (e: CloudException) {
            if (appColumnAvailable && isAppColumnError(e)) {
                appColumnAvailable = false
                log("app column missing on $path — retrying without the tag (migration 015 not live yet)")
                call("POST", path, body.toString(), prefer)
            } else throw e
        }
    }

    private fun isAppColumnError(e: CloudException): Boolean {
        val m = e.message?.lowercase().orEmpty()
        return e.status == 400 && (
            m.contains("pgrst204") ||
                (m.contains("column") && m.contains("app")) ||
                m.contains("could not find the 'app' column")
            )
    }

    /** POST a row to a RANI-only table (no `app` column there). Best-effort: a missing
     *  table (migration 015 not live yet) is logged and skipped — returns false.
     *  Never throws for missing-table/column errors. */
    private suspend fun postRaniTable(path: String, body: JSONObject, prefer: String? = null): Boolean {
        return try {
            call("POST", path, body.toString(), prefer)
            true
        } catch (e: CloudException) {
            if (isMissingTableError(e)) {
                log("table $path not found — skipping write (migration 015 not live yet)")
            } else {
                log("write to $path failed: ${e.message}")
            }
            false
        } catch (e: Exception) {
            log("write to $path failed: ${e.message}")
            false
        }
    }

    private fun isMissingTableError(e: CloudException): Boolean {
        val m = e.message?.lowercase().orEmpty()
        return e.status == 404 || m.contains("pgrst205") ||
            m.contains("relation") && m.contains("does not exist") ||
            m.contains("could not find the table")
    }

    /** Calls an RPC with `p_app => 'rani'`. When the old function signature is still
     *  live (migration 015 not run), PostgREST rejects the extra argument — then the
     *  same call is retried once WITHOUT p_app. Only signature errors are retried. */
    private suspend fun callRpc(name: String, args: JSONObject): String {
        val withApp = JSONObject(args.toString()).put("p_app", APP_TAG)
        return try {
            call("POST", "rpc/$name", withApp.toString())
        } catch (e: CloudException) {
            if (isRpcSignatureError(e)) {
                log("RPC $name rejected p_app — retrying with the old signature")
                call("POST", "rpc/$name", args.toString())
            } else throw e
        }
    }

    private fun isRpcSignatureError(e: CloudException): Boolean {
        val m = e.message?.lowercase().orEmpty()
        return m.contains("pgrst202") || m.contains("could not find the function") ||
            m.contains("p_app") || m.contains("function") && m.contains("does not exist")
    }

    // ---- RANI long-term memory (v1.9.0 §21.2, migration 016) ----
    /**
     * Best-effort write to `rani_memories` (owner-only RLS, app='rani').
     * A missing table (migration not live yet) is logged and skipped —
     * never thrown, never blocking the call. The on-device store is the
     * source of truth; sync is a copy, not a dependency.
     */
    /** Returns true when the row was written. A 'visual' row fails the
     *  old CHECK until migration 017 is run — the caller keeps it
     *  on-device and retries later (never crash, never lose the note). */
    suspend fun syncMemory(userId: String, kind: String, title: String, content: String, facts: JSONObject?, source: String): Boolean {
        if (userId.isBlank()) return false
        val b = JSONObject().put("user_id", userId).put("app", APP_TAG).put("kind", kind)
            .put("title", title.take(120)).put("content", content)
            .put("facts", facts ?: JSONObject()).put("source", source)
            .put("updated_at", java.time.Instant.now().toString())
        return postRaniTable("rani_memories", b)
    }

    /** Deletes ALL of the user's rani_memories rows ("Forget everything"). Best-effort. */
    suspend fun deleteMemories(userId: String) {
        if (userId.isBlank()) return
        runCatching { call("DELETE", "rani_memories?user_id=eq.${enc(userId)}") }
            .onFailure { log("deleteMemories failed: ${it.message}") }
    }

    // ---- identity / analytics (security-definer RPCs, migration 002 + 015) ----
    /** Returns true if the account is blocked. Fail-open (false) when the call cannot run. */
    suspend fun touchProfile(email: String?, name: String?, avatar: String?): Boolean {
        val b = JSONObject().put("p_email", email ?: JSONObject.NULL).put("p_display_name", name ?: JSONObject.NULL).put("p_avatar_url", avatar ?: JSONObject.NULL)
        return runCatching {
            JSONObject(callRpc("app_touch_profile", b)).optBoolean("is_blocked", false)
        }.getOrElse {
            log("touchProfile failed: ${it.message}")
            false
        }
    }

    suspend fun recordLogin(provider: String, event: String) {
        runCatching {
            callRpc("app_record_login", JSONObject().put("p_provider", provider).put("p_event_type", event).put("p_device", "android"))
        }
    }

    /** Best effort: analytics must never break the chat. */
    suspend fun recordUsage(provider: String, model: String, type: String, ok: Boolean, inTok: Long, outTok: Long, latency: Int?, err: String?) {
        runCatching {
            val b = JSONObject().put("p_provider", provider).put("p_model", model).put("p_event_type", type).put("p_success", ok)
                .put("p_input_tokens", inTok).put("p_output_tokens", outTok).put("p_latency_ms", latency ?: JSONObject.NULL).put("p_error_code", err ?: JSONObject.NULL)
            callRpc("app_record_usage", b)
        }
    }

    // ---- RANI preferences (rani_preferences / rani_provider_settings — NEVER the shared tables) ----
    /** Best-effort: a missing table (migration not live) is logged, never thrown. */
    suspend fun savePreferences(userId: String, provider: String, model: String?, voice: String?, engine: String, rate: Float, pitch: Float, lang: String, personality: String = "girlfriend", memoryEnabled: Boolean = true) {
        val b = JSONObject().put("user_id", userId).put("selected_provider", provider)
            .put("voice_engine", engine).put("speech_rate", rate.toDouble()).put("speech_pitch", pitch.toDouble()).put("language", lang)
            .put("personality", personality).put("memory_enabled", memoryEnabled)
            .put("updated_at", java.time.Instant.now().toString())
        if (model != null) b.put("selected_model", model)
        if (voice != null) b.put("selected_voice", voice)
        postRaniTable("rani_preferences?on_conflict=user_id", b, "resolution=merge-duplicates")
    }

    /** Stores only the STATUS of a provider (configured / tested), never the key. Best-effort. */
    suspend fun saveProviderStatus(userId: String, provider: String, configured: Boolean, status: String) {
        val b = JSONObject().put("user_id", userId).put("provider", provider).put("is_configured", configured).put("connection_status", status)
            .put("last_tested_at", java.time.Instant.now().toString())
            .put("updated_at", java.time.Instant.now().toString())
        postRaniTable("rani_provider_settings?on_conflict=user_id,provider", b, "resolution=merge-duplicates")
    }

    // ---- hisab kitab: every user action, metadata ONLY (never content) ----
    /**
     * Logs one user action to rani_activity. Metadata must be counts/flags only —
     * NEVER message content, contacts data, recordings, or raw voice.
     * Best-effort and silent: unknown event types and missing tables are skipped.
     */
    suspend fun recordActivity(userId: String?, eventType: String, metadata: Map<String, Any?> = emptyMap()) {
        if (userId.isNullOrBlank()) return
        if (eventType !in ACTIVITY_EVENTS) {
            log("recordActivity: unknown event_type '$eventType' — skipped")
            return
        }
        val meta = JSONObject()
        metadata.forEach { (k, v) -> if (v != null) meta.put(k, v) }
        val b = JSONObject().put("user_id", userId).put("event_type", eventType).put("metadata", meta)
        postRaniTable("rani_activity", b)
    }

    /** Screen-recording metadata ONLY (file stays on the phone). Best-effort. */
    suspend fun recordScreenRecording(userId: String?, fileName: String, durationSec: Int, sizeBytes: Long) {
        if (userId.isNullOrBlank()) return
        val b = JSONObject().put("user_id", userId).put("file_name", fileName)
            .put("duration_sec", durationSec).put("size_bytes", sizeBytes)
        postRaniTable("rani_screen_recordings", b)
    }

    /** Voice-print enrolment metadata ONLY (never raw voice). Best-effort. */
    suspend fun recordVoiceprintEnrolled(userId: String?, modelVersion: String = "voiceprint-v1") {
        if (userId.isNullOrBlank()) return
        val b = JSONObject().put("user_id", userId).put("model_version", modelVersion).put("is_active", true)
        postRaniTable("rani_voiceprints", b)
    }

    /** Marks the latest voice-print row as verified now. Best-effort. */
    suspend fun recordVoiceprintVerified(userId: String?) {
        if (userId.isNullOrBlank()) return
        runCatching {
            val a = rows(call("GET", "rani_voiceprints?select=id&user_id=eq.${enc(userId)}&order=enrolled_at.desc&limit=1"))
            val id = a.optJSONObject(0)?.optString("id") ?: return@runCatching
            call("PATCH", "rani_voiceprints?id=eq.${enc(id)}", JSONObject().put("last_verified_at", java.time.Instant.now().toString()).toString())
        }.onFailure { log("recordVoiceprintVerified failed: ${it.message}") }
    }

    // ---- chat history (shared tables, app-tagged) ----
    suspend fun createConversation(userId: String, title: String, provider: String, model: String): String {
        val b = JSONObject().put("user_id", userId).put("title", title.take(60)).put("ai_provider", provider).put("model_name", model)
        return rows(postTable("conversations", b, "return=representation")).getJSONObject(0).getString("id")
    }
    suspend fun addMessage(userId: String, convoId: String, role: String, content: String, provider: String?, model: String?) {
        val b = JSONObject().put("conversation_id", convoId).put("user_id", userId).put("role", role).put("content", content).put("ai_provider", provider ?: JSONObject.NULL).put("model_name", model ?: JSONObject.NULL)
        postTable("messages", b)
        runCatching { call("PATCH", "conversations?id=eq.${enc(convoId)}", JSONObject().put("updated_at", java.time.Instant.now().toString()).toString()) }
    }
    suspend fun listConversations(): List<RemoteConvo> = rows(call("GET", "conversations?select=id,title,updated_at&order=updated_at.desc&limit=100")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> RemoteConvo(o.getString("id"), o.getString("title"), o.getString("updated_at")) } } }
    suspend fun listMessages(convoId: String): List<RemoteMsg> = rows(call("GET", "messages?select=role,content&conversation_id=eq.${enc(convoId)}&order=created_at.asc&limit=500")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> RemoteMsg(o.getString("role"), o.getString("content")) } } }
    suspend fun deleteConversation(id: String) { call("DELETE", "conversations?id=eq.${enc(id)}") }

    // ---- support center (shared tables, app-tagged) ----
    suspend fun createTicket(userId: String, subject: String, category: String, message: String) {
        postTable("support_tickets", JSONObject().put("user_id", userId).put("subject", subject.take(150)).put("category", category).put("message", message.take(4000)))
    }
    suspend fun listTickets(): List<TicketRow> = rows(call("GET", "support_tickets?select=id,subject,status,message,updated_at&order=updated_at.desc&limit=50")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> TicketRow(o.getString("id"), o.getString("subject"), o.getString("status"), o.getString("message"), o.getString("updated_at")) } } }
    suspend fun ticketReplies(ticketId: String): List<TicketReply> = rows(call("GET", "support_messages?select=sender_type,message,created_at&ticket_id=eq.${enc(ticketId)}&order=created_at.asc")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> TicketReply(o.getString("sender_type"), o.getString("message"), o.getString("created_at")) } } }
    suspend fun addTicketMessage(ticketId: String, message: String) { postTable("support_messages", JSONObject().put("ticket_id", ticketId).put("sender_type", "user").put("message", message.take(4000))) }

    // ---- remote app configuration (RANI admin panel: app_settings, app='rani') ----
    /**
     * Reads app_settings for one app. Returns setting_key -> setting_value (JSON object),
     * e.g. "features" -> {"awareness": true, ...}, "persona" -> {"default_voice": "Aoede", ...}.
     * Empty map when the table does not exist yet, the user is not signed in, or the
     * read fails. Never throws. NOTE: RLS grants SELECT to authenticated users only,
     * so this returns rows only when a Firebase token is available.
     */
    suspend fun fetchAppSettings(app: String): Map<String, JSONObject> = withContext(Dispatchers.IO) {
        val jwt = runCatching { token() }.getOrNull() ?: return@withContext emptyMap()
        val rb = Request.Builder()
            .url("${Config.SUPABASE_URL}/rest/v1/app_settings?select=setting_key,setting_value&app=eq.${enc(app)}")
            .header("apikey", Config.SUPABASE_KEY)
            .header("Authorization", "Bearer $jwt")
        try {
            http.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyMap()
                val a = rows(r.body?.string().orEmpty())
                val m = mutableMapOf<String, JSONObject>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("setting_key")
                    val v = o.optJSONObject("setting_value")
                    if (k.isNotBlank() && v != null) m[k] = v
                }
                m
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Maintenance banner: app_settings (app='rani', key 'maintenance') first, then the legacy app_config table. */
    suspend fun maintenanceMessage(): String? {
        val panel = runCatching { fetchAppSettings("rani")["maintenance"] }.getOrNull()
        if (panel != null && panel.optBoolean("enabled")) {
            return panel.optString("message").ifBlank { "RANI AI is under maintenance." }
        }
        return runCatching {
            val a = rows(call("GET", "app_config?select=config_value&config_key=eq.maintenance"))
            a.optJSONObject(0)?.optJSONObject("config_value")?.takeIf { it.optBoolean("enabled") }?.optString("message")?.ifBlank { "RANI AI is under maintenance." }
        }.getOrNull()
    }

    /** Announcements: app_settings (app='rani', key 'announcement') first, then the legacy announcements table. */
    suspend fun announcements(): List<Pair<String, String>> {
        val panel = runCatching { fetchAppSettings("rani")["announcement"] }.getOrNull()
        if (panel != null && panel.optBoolean("enabled")) {
            val title = panel.optString("title")
            val body = panel.optString("body")
            if (title.isNotBlank() || body.isNotBlank()) return listOf(title to body)
        }
        return runCatching {
            rows(call("GET", "announcements?select=title,content&is_published=eq.true&order=created_at.desc&limit=5")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("title") to o.getString("content") } } }
        }.getOrDefault(emptyList())
    }

    suspend fun voiceProfiles(): List<VoiceProfileRow> = runCatching {
        rows(call("GET", "voice_profiles?select=id,label,category,pitch,speed,language&order=sort_order.asc")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> VoiceProfileRow(o.getString("id"), o.getString("label"), o.getString("category"), o.getDouble("pitch").toFloat(), o.getDouble("speed").toFloat(), o.getString("language")) } } }
    }.getOrDefault(emptyList())
}
