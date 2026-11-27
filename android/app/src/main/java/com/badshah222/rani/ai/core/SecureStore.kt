package com.badshah222.rani.ai.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.badshah222.rani.ai.domain.Provider

/** API keys live ONLY here: AES-256 encrypted, Android Keystore-backed, never logged, never sent to our servers. */
class SecureStore(context: Context) {
    private val secrets: SharedPreferences = try {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "rani_secrets", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) {
        // Keystore corrupted (rare, e.g. after restore): start clean rather than crash.
        context.deleteSharedPreferences("rani_secrets")
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "rani_secrets", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val prefs: SharedPreferences = context.getSharedPreferences("rani_settings", Context.MODE_PRIVATE)

    fun apiKey(p: Provider): String? = secrets.getString("key_${p.id}", null)?.takeIf { it.isNotBlank() }
    fun setApiKey(p: Provider, key: String?) = secrets.edit().apply { if (key.isNullOrBlank()) remove("key_${p.id}") else putString("key_${p.id}", key.trim()) }.apply()
    fun googleTtsKey(): String? = secrets.getString("key_google_tts", null)?.takeIf { it.isNotBlank() }
    fun setGoogleTtsKey(key: String?) = secrets.edit().apply { if (key.isNullOrBlank()) remove("key_google_tts") else putString("key_google_tts", key.trim()) }.apply()
    /** Other user-owned tokens (e.g. a GitHub deploy token for WebForge) — encrypted, on-device only. */
    fun miscSecret(name: String): String? = secrets.getString("misc_$name", null)?.takeIf { it.isNotBlank() }
    fun setMiscSecret(name: String, value: String?) = secrets.edit().apply { if (value.isNullOrBlank()) remove("misc_$name") else putString("misc_$name", value.trim()) }.apply()
    fun wipeAllSecrets() = secrets.edit().clear().apply()

    // ---- non-secret settings ----
    var provider: Provider get() = Provider.fromId(prefs.getString("provider", null)); set(v) = prefs.edit().putString("provider", v.id).apply()
    fun model(p: Provider): String? = prefs.getString("model_${p.id}", null)
    fun setModel(p: Provider, m: String) = prefs.edit().putString("model_${p.id}", m).apply()
    var voiceEngine: String get() = prefs.getString("voice_engine", "android") ?: "android"; set(v) = prefs.edit().putString("voice_engine", v).apply()
    var ttsVoiceName: String? get() = prefs.getString("tts_voice", null); set(v) = prefs.edit().putString("tts_voice", v).apply()
    var styleId: String get() = prefs.getString("style", "deep") ?: "deep"; set(v) = prefs.edit().putString("style", v).apply()
    var pitch: Float get() = prefs.getFloat("pitch", 0.95f); set(v) = prefs.edit().putFloat("pitch", v).apply()
    var speed: Float get() = prefs.getFloat("speed", 1.0f); set(v) = prefs.edit().putFloat("speed", v).apply()
    var speakReplies: Boolean get() = prefs.getBoolean("speak", true); set(v) = prefs.edit().putBoolean("speak", v).apply()
    var lowPower: Boolean get() = prefs.getBoolean("low_power", false); set(v) = prefs.edit().putBoolean("low_power", v).apply()
    var language: String get() = prefs.getString("lang", "en-US") ?: "en-US"; set(v) = prefs.edit().putString("lang", v).apply()
    var personalityId: String get() = prefs.getString("personality", "girlfriend") ?: "girlfriend"; set(v) = prefs.edit().putString("personality", v).apply()
    /** null = Auto: the emotion is detected from the user's words on every message. */
    var emotionId: String? get() = prefs.getString("emotion", null); set(v) = prefs.edit().putString("emotion", v).apply()
    var wakeWord: Boolean get() = prefs.getBoolean("wake_word", false); set(v) = prefs.edit().putBoolean("wake_word", v).apply()
    /** Optional custom wake phrase (in addition to "RANI"). */
    var customWakeWord: String get() = prefs.getString("wake_custom", "") ?: ""; set(v) = prefs.edit().putString("wake_custom", v).apply()

    // ---- Gemini Live (real human voices) ----
    /** Selected Gemini Live voice name — always one of the female voices (Aoede default). */
    var liveVoiceName: String get() = prefs.getString("live_voice", "Aoede") ?: "Aoede"; set(v) = prefs.edit().putString("live_voice", v).apply()
    /** Live-capable model found for the user's own Gemini key (auto-detected, never invented). */
    var liveModel: String? get() = prefs.getString("live_model", null); set(v) = prefs.edit().putString("live_model", v).apply()
    /** Use the Gemini Live engine on the Voice screen when a Gemini key exists. */
    var liveEnabled: Boolean get() = prefs.getBoolean("live_enabled", true); set(v) = prefs.edit().putBoolean("live_enabled", v).apply()
    /** v1.7.0 (§19.2) — auto-connect + talk on app open, and the floating
     *  avatar may appear. Default ON. Distinct from liveEnabled (the Live
     *  engine is allowed at all): OFF here only stops auto-connects. */
    var autoVoiceEnabled: Boolean get() = prefs.getBoolean("auto_voice_enabled", true); set(v) = prefs.edit().putBoolean("auto_voice_enabled", v).apply()
    /** All live-capable models found for the key (comma-separated), best first — the Live engine tries them in order. */
    var liveCandidates: String get() = prefs.getString("live_candidates", "") ?: ""; set(v) = prefs.edit().putString("live_candidates", v).apply()
    /** Which Live setup variant worked last time (0 = full, 1 = no language code, 2 = bare). Tried first next time. */
    var liveSetupLevel: Int get() = prefs.getInt("live_setup_level", 0); set(v) = prefs.edit().putInt("live_setup_level", v).apply()
    var liveVersion: String get() = prefs.getString("live_version", "v1beta") ?: "v1beta"; set(v) = prefs.edit().putString("live_version", v).apply()
    /** Why the last Live attempt could not run (shown in Settings + on the call screen). Cleared when Live connects. */
    var lastLiveError: String? get() = prefs.getString("live_last_error", null); set(v) = prefs.edit().putString("live_last_error", v).apply()

    // ---- Gemini images & Google voice ----
    /** Image generation model: gemini (Gemini image models, user's own key) | pollinations (free, no key) */
    var imageModel: String get() = prefs.getString("image_model", "gemini") ?: "gemini"; set(v) = prefs.edit().putString("image_model", v).apply()
    var googleTtsVoice: String get() = prefs.getString("google_tts_voice", "en-US-Journey-F") ?: "en-US-Journey-F"; set(v) = prefs.edit().putString("google_tts_voice", v).apply()

    // ---- assistant identity & look ----
    var assistantName: String get() = prefs.getString("assistant_name", "RANI")?.ifBlank { "RANI" } ?: "RANI"; set(v) = prefs.edit().putString("assistant_name", v).apply()
    /** The pet name RANI calls the user — default "Babu Jaan", editable in Settings. */
    var petName: String get() = prefs.getString("pet_name", "Babu Jaan")?.ifBlank { "Babu Jaan" } ?: "Babu Jaan"; set(v) = prefs.edit().putString("pet_name", v).apply()
    /** balanced | short | detailed */
    var responseStyle: String get() = prefs.getString("response_style", "balanced") ?: "balanced"; set(v) = prefs.edit().putString("response_style", v).apply()
    /** girl (RANI face) | logo (crown logo) */
    var avatarChoice: String get() = prefs.getString("avatar", "girl") ?: "girl"; set(v) = prefs.edit().putString("avatar", v).apply()

    // ---- MYRA Live model choice (aria.py LIVE_MODELS) ----
    /** Live model key — F6 (v1.8.0 fix): ONLY "3.8" (gemini-3.8-live).
     *  Binding order 2026-11-26: no other Live model; the old
     *  "3.8-thinking" / "native-ga" keys no longer exist. */
    var liveModelKey: String get() = prefs.getString("live_model_key", "3.8") ?: "3.8"; set(v) = prefs.edit().putString("live_model_key", v).apply()

    // ---- MYRA VoicePage: listen modes + mic ----
    /** wake | tap | continuous | stop (MYRA ModeBar). Default tap-to-talk. */
    var listenMode: String get() = prefs.getString("listen_mode", "tap") ?: "tap"; set(v) = prefs.edit().putString("listen_mode", v).apply()
    /** Mic sensitivity threshold 5–120 (MYRA: kam = zyada sensitive). Default 40. */
    var micSensitivity: Int get() = prefs.getInt("mic_sensitivity", 40); set(v) = prefs.edit().putInt("mic_sensitivity", v).apply()
    /** Mic gain 1.0–5.0x (MYRA slider 10–50 / 10). Default 1.0. */
    var micGain: Float get() = prefs.getFloat("mic_gain", 1.0f); set(v) = prefs.edit().putFloat("mic_gain", v).apply()

    // ---- MYRA Orb + Screen Guardian (SettingsPage) ----
    /** Floating orb size in px, 56–200 (MYRA default 96). */
    var orbFloatSize: Int get() = prefs.getInt("orb_float_size", 96); set(v) = prefs.edit().putInt("orb_float_size", v).apply()
    /** Orb UI scale %, 60–140 (MYRA default 100). */
    var orbUiScale: Int get() = prefs.getInt("orb_ui_scale", 100); set(v) = prefs.edit().putInt("orb_ui_scale", v).apply()
    /** Screen Guardian on/off — watches the shared screen and speaks up if something looks wrong. OFF by default. */
    var guardianEnabled: Boolean get() = prefs.getBoolean("guardian_enabled", false); set(v) = prefs.edit().putBoolean("guardian_enabled", v).apply()
    /** Guardian check interval in seconds: 30 | 45 (default) | 90 (MYRA segmented choice). */
    var guardianInterval: Int get() = prefs.getInt("guardian_interval", 45); set(v) = prefs.edit().putInt("guardian_interval", v).apply()

    // ---- Girlfriend extras: memory, special days, favourites ----
    /** RANI remembers the user (auto-learn). Off = no new memories are saved. */
    var memoryOn: Boolean get() = prefs.getBoolean("memory_on", true); set(v) = prefs.edit().putBoolean("memory_on", v).apply()
    /** Ask before saving a new memory (MYRA-style consent). */
    var memoryAskFirst: Boolean get() = prefs.getBoolean("memory_ask_first", false); set(v) = prefs.edit().putBoolean("memory_ask_first", v).apply()
    /** v1.9.0 §21.2 — sync memories to the user's own Supabase rows
     *  (owner-only RLS). Default ON when signed in; the switch lives in
     *  Settings → Memory. The on-device store is the source of truth and
     *  the app works fully with sync off or failing. */
    var memorySync: Boolean get() = prefs.getBoolean("memory_sync", true); set(v) = prefs.edit().putBoolean("memory_sync", v).apply()
    /** Special days as JSON: [{"name":"Birthday","month":1,"day":1}] — RANI wishes Babu Jaan first. */
    var specialDaysJson: String get() = prefs.getString("special_days", "[]") ?: "[]"; set(v) = prefs.edit().putString("special_days", v).apply()
    /** Relationship memory (favourites) as JSON: {"chai":"doodh pati","song":"..."} — "RANI yaad rakhti hai". */
    var favouritesJson: String get() = prefs.getString("favourites", "{}") ?: "{}"; set(v) = prefs.edit().putString("favourites", v).apply()
    /** Good-morning / good-night messages (opt-in, quiet hours respected). */
    var greetingsOn: Boolean get() = prefs.getBoolean("greetings_on", false); set(v) = prefs.edit().putBoolean("greetings_on", v).apply()

    // ---- MYRA Automation: tasks & reminders (JSON list, local only) ----
    /** Tasks as JSON: [{"id":"..","title":"..","due":"..","status":"pending|done"}]. */
    var tasksJson: String get() = prefs.getString("tasks", "[]") ?: "[]"; set(v) = prefs.edit().putString("tasks", v).apply()

    // ---- Stonic: Awareness, Face Lock, voice-print, screen recording ----
    /** Awareness (front-app notice) — ON by default, Stonic-style; user can switch it off anytime. */
    var awarenessEnabled: Boolean get() = prefs.getBoolean("awareness_enabled", true); set(v) = prefs.edit().putBoolean("awareness_enabled", v).apply()
    /** v1.8.0 (§20.3) — the one-question onboarding has been completed.
     *  Default false: a fresh sign-in runs the onboarding first. */
    var onboardingDone: Boolean get() = prefs.getBoolean("onboarding_done", false); set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()
    /** Face Lock — locks RANI when a different face is detected. OFF by default. */
    var faceLockEnabled: Boolean get() = prefs.getBoolean("face_lock_enabled", false); set(v) = prefs.edit().putBoolean("face_lock_enabled", v).apply()
    /** Enrolled boss face-print (Base64 float vector) — encrypted, on-device only, never uploaded. */
    var facePrintProfile: String? get() = secrets.getString("face_print", null); set(v) = secrets.edit().apply { if (v == null) remove("face_print") else putString("face_print", v) }.apply()
    /** Enrolled boss voice-print (Base64 float vector) — encrypted, on-device only, never uploaded. */
    var voicePrintProfile: String? get() = secrets.getString("voice_print", null); set(v) = secrets.edit().apply { if (v == null) remove("voice_print") else putString("voice_print", v) }.apply()
    /** Voice-print gate — when ON, RANI only starts a voice session for the enrolled boss voice. OFF by default. */
    var voicePrintGate: Boolean get() = prefs.getBoolean("voice_print_gate", false); set(v) = prefs.edit().putBoolean("voice_print_gate", v).apply()
    /** Screen recording allowed (user toggle; admin panel can override via remote config). OFF by default. */
    var screenRecordingEnabled: Boolean get() = prefs.getBoolean("screen_recording_enabled", false); set(v) = prefs.edit().putBoolean("screen_recording_enabled", v).apply()
    /** Floating orb allowed (user toggle; needs the overlay permission, granted by the user in Settings). OFF by default. */
    var floatingOrbEnabled: Boolean get() = prefs.getBoolean("floating_orb_enabled", false); set(v) = prefs.edit().putBoolean("floating_orb_enabled", v).apply()
    /** Last good remote config from the RANI admin panel (app_settings), cached as JSON. */
    var remoteConfigJson: String get() = prefs.getString("remote_config_json", "{}") ?: "{}"; set(v) = prefs.edit().putString("remote_config_json", v).apply()
    /** Cached "Three things for you today" briefing (JSON) + the day it was made (yyyy-MM-dd). */
    var briefingJson: String get() = prefs.getString("briefing_json", "") ?: ""; set(v) = prefs.edit().putString("briefing_json", v).apply()
    var briefingDate: String get() = prefs.getString("briefing_date", "") ?: ""; set(v) = prefs.edit().putString("briefing_date", v).apply()
}
