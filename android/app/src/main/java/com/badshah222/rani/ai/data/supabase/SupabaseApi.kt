package com.badshah222.rani.ai.data.supabase

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
 */
class SupabaseApi(private val token: suspend () -> String?) {
    private val json = "application/json".toMediaType()

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

    // ---- identity / analytics (security-definer RPCs created in migration 002) ----
    /** Returns true if the account is blocked. */
    suspend fun touchProfile(email: String?, name: String?, avatar: String?): Boolean {
        val b = JSONObject().put("p_email", email ?: JSONObject.NULL).put("p_display_name", name ?: JSONObject.NULL).put("p_avatar_url", avatar ?: JSONObject.NULL)
        return JSONObject(call("POST", "rpc/app_touch_profile", b.toString())).optBoolean("is_blocked", false)
    }
    suspend fun recordLogin(provider: String, event: String) {
        runCatching { call("POST", "rpc/app_record_login", JSONObject().put("p_provider", provider).put("p_event_type", event).put("p_device", "android").toString()) }
    }
    /** Best effort: analytics must never break the chat. */
    suspend fun recordUsage(provider: String, model: String, type: String, ok: Boolean, inTok: Long, outTok: Long, latency: Int?, err: String?) {
        runCatching {
            val b = JSONObject().put("p_provider", provider).put("p_model", model).put("p_event_type", type).put("p_success", ok)
                .put("p_input_tokens", inTok).put("p_output_tokens", outTok).put("p_latency_ms", latency ?: JSONObject.NULL).put("p_error_code", err ?: JSONObject.NULL)
            call("POST", "rpc/app_record_usage", b.toString())
        }
    }

    // ---- preferences (never contains API keys) ----
    suspend fun savePreferences(userId: String, provider: String, model: String?, voice: String?, engine: String, rate: Float, pitch: Float, lang: String) {
        val b = JSONObject().put("user_id", userId).put("selected_provider", provider).put("selected_model", model ?: JSONObject.NULL)
            .put("selected_voice", voice ?: JSONObject.NULL).put("voice_engine", engine).put("speech_rate", rate.toDouble()).put("speech_pitch", pitch.toDouble()).put("language", lang)
        call("POST", "user_preferences?on_conflict=user_id", b.toString(), "resolution=merge-duplicates")
    }
    /** Stores only the STATUS of a provider (configured / tested), never the key. */
    suspend fun saveProviderStatus(userId: String, provider: String, configured: Boolean, status: String) {
        val b = JSONObject().put("user_id", userId).put("provider", provider).put("is_configured", configured).put("connection_status", status).put("last_tested_at", java.time.Instant.now().toString())
        call("POST", "user_provider_settings?on_conflict=user_id,provider", b.toString(), "resolution=merge-duplicates")
    }

    // ---- chat history ----
    suspend fun createConversation(userId: String, title: String, provider: String, model: String): String {
        val b = JSONObject().put("user_id", userId).put("title", title.take(60)).put("ai_provider", provider).put("model_name", model)
        return rows(call("POST", "conversations", b.toString(), "return=representation")).getJSONObject(0).getString("id")
    }
    suspend fun addMessage(userId: String, convoId: String, role: String, content: String, provider: String?, model: String?) {
        val b = JSONObject().put("conversation_id", convoId).put("user_id", userId).put("role", role).put("content", content).put("ai_provider", provider ?: JSONObject.NULL).put("model_name", model ?: JSONObject.NULL)
        call("POST", "messages", b.toString())
        runCatching { call("PATCH", "conversations?id=eq.${enc(convoId)}", JSONObject().put("updated_at", java.time.Instant.now().toString()).toString()) }
    }
    suspend fun listConversations(): List<RemoteConvo> = rows(call("GET", "conversations?select=id,title,updated_at&order=updated_at.desc&limit=100")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> RemoteConvo(o.getString("id"), o.getString("title"), o.getString("updated_at")) } } }
    suspend fun listMessages(convoId: String): List<RemoteMsg> = rows(call("GET", "messages?select=role,content&conversation_id=eq.${enc(convoId)}&order=created_at.asc&limit=500")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> RemoteMsg(o.getString("role"), o.getString("content")) } } }
    suspend fun deleteConversation(id: String) { call("DELETE", "conversations?id=eq.${enc(id)}") }

    // ---- support center ----
    suspend fun createTicket(userId: String, subject: String, category: String, message: String) {
        call("POST", "support_tickets", JSONObject().put("user_id", userId).put("subject", subject.take(150)).put("category", category).put("message", message.take(4000)).toString())
    }
    suspend fun listTickets(): List<TicketRow> = rows(call("GET", "support_tickets?select=id,subject,status,message,updated_at&order=updated_at.desc&limit=50")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> TicketRow(o.getString("id"), o.getString("subject"), o.getString("status"), o.getString("message"), o.getString("updated_at")) } } }
    suspend fun ticketReplies(ticketId: String): List<TicketReply> = rows(call("GET", "support_messages?select=sender_type,message,created_at&ticket_id=eq.${enc(ticketId)}&order=created_at.asc")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> TicketReply(o.getString("sender_type"), o.getString("message"), o.getString("created_at")) } } }
    suspend fun addTicketMessage(ticketId: String, message: String) { call("POST", "support_messages", JSONObject().put("ticket_id", ticketId).put("sender_type", "user").put("message", message.take(4000)).toString()) }

    // ---- remote app configuration (admin controlled) ----
    suspend fun maintenanceMessage(): String? = runCatching {
        val a = rows(call("GET", "app_config?select=config_value&config_key=eq.maintenance"))
        a.optJSONObject(0)?.optJSONObject("config_value")?.takeIf { it.optBoolean("enabled") }?.optString("message")?.ifBlank { "RANI AI is under maintenance." }
    }.getOrNull()
    suspend fun announcements(): List<Pair<String, String>> = runCatching {
        rows(call("GET", "announcements?select=title,content&is_published=eq.true&order=created_at.desc&limit=5")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("title") to o.getString("content") } } }
    }.getOrDefault(emptyList())
    suspend fun voiceProfiles(): List<VoiceProfileRow> = runCatching {
        rows(call("GET", "voice_profiles?select=id,label,category,pitch,speed,language&order=sort_order.asc")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> VoiceProfileRow(o.getString("id"), o.getString("label"), o.getString("category"), o.getDouble("pitch").toFloat(), o.getDouble("speed").toFloat(), o.getString("language")) } } }
    }.getOrDefault(emptyList())
}
