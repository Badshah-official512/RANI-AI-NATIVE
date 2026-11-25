package com.badshah222.rani.ai.core

import com.badshah222.rani.ai.data.supabase.SupabaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Remote feature config for RANI, controlled from the separate RANI admin
 * panel (badshah222-ai.pages.dev/Ranipanal). Reads table `app_settings`
 * (columns: app TEXT, setting_key TEXT, setting_value JSONB, updated_at).
 * Only rows with app = 'rani' are read. The panel writes; the app only reads.
 *
 * Groups (setting_key -> JSON object), seeded by migration 015:
 *   features:     {"awareness", "camera", "screen_recording",
 *                  "voiceprint_gate", "floating_orb"} (booleans)
 *   persona:      {"default_voice", "tone", "call_greeting"}
 *   gemini:       {"model"}
 *   maintenance:  {"enabled", "message"}
 *   announcement: {"enabled", "title", "body"}
 *
 * Fail-soft by design: when the table does not exist yet (migration 015
 * pending) or the read fails, every key falls back to the on-device default
 * — the app never blocks on remote config. The last good values are cached
 * locally. Remote wins when the panel set the key; the user's own toggle
 * still applies when the panel has not set it.
 */
class RaniRemoteConfig(private val store: SecureStore, private val cloud: SupabaseApi) {
    private val _values = MutableStateFlow<Map<String, String>>(loadCache())
    val values: StateFlow<Map<String, String>> = _values

    private fun loadCache(): Map<String, String> = runCatching {
        val o = JSONObject(store.remoteConfigJson)
        val m = mutableMapOf<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k) }
        m
    }.getOrDefault(emptyMap())

    /** Best-effort refresh (app start + Home start). Never throws. */
    suspend fun refresh() {
        val groups = runCatching { cloud.fetchAppSettings("rani") }.getOrDefault(emptyMap())
        if (groups.isEmpty()) return
        val flat = mutableMapOf<String, String>()
        fun putObj(prefix: String, o: JSONObject?) {
            if (o == null) return
            o.keys().forEach { k -> flat["$prefix$k"] = o.opt(k)?.toString().orEmpty() }
        }
        val features = groups["features"]
        putObj("", features)
        putObj("", groups["persona"])
        putObj("gemini_", groups["gemini"])
        putObj("maintenance_", groups["maintenance"])
        putObj("announcement_", groups["announcement"])
        // The features group keys stay bare (awareness, camera, ...).
        _values.value = flat
        store.remoteConfigJson = JSONObject(flat as Map<*, *>).toString()
    }

    fun refreshAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun bool(key: String, default: Boolean): Boolean =
        _values.value[key]?.trim()?.lowercase()?.let {
            it == "true" || it == "1" || it == "on" || it == "yes"
        } ?: default

    fun string(key: String, default: String): String =
        _values.value[key]?.takeIf { it.isNotBlank() } ?: default

    /** Effective values: remote wins when the panel set the key, else the local setting. */
    fun awarenessOn(localDefault: Boolean) = bool("awareness", localDefault)
    fun cameraEnabled(localDefault: Boolean) = bool("camera", localDefault)
    fun screenRecordingOn(localDefault: Boolean) = bool("screen_recording", localDefault)
    fun voicePrintGateOn(localDefault: Boolean) = bool("voiceprint_gate", localDefault)
    fun floatingOrbOn(localDefault: Boolean) = bool("floating_orb", localDefault)
    fun defaultVoice(localDefault: String) = string("default_voice", localDefault)

    /** Persona tone (an emotion id like "loving"), or null when unset/invalid. */
    fun tone(): String? = _values.value["tone"]?.takeIf { it.isNotBlank() }

    /** What RANI says first when a call starts. Panel default: "G Babu". */
    fun callGreeting(): String = string("call_greeting", "G Babu")

    /** Default Gemini model from the panel (seed: gemini-3.8-flash). */
    fun geminiModel(): String = string("gemini_model", "gemini-3.8-flash")

    /** Maintenance mode from the panel: (enabled, message). */
    fun maintenance(): Pair<Boolean, String> =
        bool("maintenance_enabled", false) to string("maintenance_message", "")

    /** Panel announcement: (enabled, title, body). */
    fun announcement(): Triple<Boolean, String, String> =
        Triple(
            bool("announcement_enabled", false),
            string("announcement_title", ""),
            string("announcement_body", "")
        )
}
