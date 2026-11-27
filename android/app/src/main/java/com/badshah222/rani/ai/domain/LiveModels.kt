package com.badshah222.rani.ai.domain

/**
 * Gemini Live voice model — BINDING ORDER (2026-11-26 20:17 PKT): ONLY
 * gemini-3.8-live, one request, no model ladder, no other model.
 * key -> (UI label, model id, API version/endpoint).
 * Default key is "3.8" (Gemini 3.8 Live) — GA since 15 Sep 2026, Google's
 * recommended Live model, free tier included. Correction of the July 2026
 * note: "gemini-3.8-live" DOES exist — the July research was stale. The old
 * models shut down on 17 Nov 2026, which is exactly why the call never
 * connected: "gemini-3.1-flash-live-preview" and
 * "gemini-2.5-flash-native-audio-preview-12-2025" are dead, and
 * "gemini-2.0-flash-live-001" is gone from the production list too — all
 * three are removed here so the ladder never wastes time on a dead model.
 * All models use v1beta (the Live API endpoint). There is exactly ONE
 * entry in `all` — the list is deliberately not a ladder: no other Live
 * model id may ever be picked or stored (see AiRepository.detectLiveModel).
 *
 * SDK VERIFICATION 2026-11-26 (the fix that finally made the call connect):
 * the official google-genai SDK (Python) connects on v1beta in 1.4 s with the
 * SAME key the app uses. CORRECTION (same day, raw-client variant tests):
 * ?key= in the URL ALSO completes setup — the earlier "silent 60 s+" was the
 * VM's direct (non-proxy) network path going silent for Live, not the auth
 * method. Through the proxy, ?key=, header auth, and both together ALL reached
 * setupComplete (variants V1–V6). So the app tries BADSHAH's way first
 * (?key=, exactly like the BADSHAH web app's Live on the phone), then the
 * SDK's way (x-goog-api-key header). The setup is the SDK's exact shape —
 * camelCase on top, snake_case inside speechConfig, role:"user" on the system
 * instruction, no contextWindowCompression/sessionResumption unless resuming.
 * Plain 3.8-live REJECTS thinkingConfig (1007) — hence no `thinking` model
 * exists here. NOTE: this VM's direct (non-proxy) network path
 * also goes silent for Live — the SDK only works here through the egress
 * proxy; the phone connects directly, so the phone test is the real proof.
 */
data class LiveModel(val key: String, val label: String, val modelId: String, val version: String, val thinking: Boolean = false)

object LiveModels {
    // F6 (v1.8.0 fix): SINGLE entry only — gemini-3.8-live. The
    // 3.8-extended-thinking and 2.5-flash-native-audio entries are removed:
    // the boss's binding order (2026-11-26 20:17 PKT) is ONLY gemini-3.8-live,
    // one request, no other models, and the Settings detect/test path could
    // previously store one of those ids.
    val all = listOf(
        LiveModel("3.8", "Gemini 3.8 Live (Default)", "gemini-3.8-live", "v1beta"),
    )
    const val DEFAULT_KEY = "3.8"
    fun fromKey(key: String?): LiveModel = all.firstOrNull { it.key == key } ?: all.first()
    fun fromModelId(modelId: String?): LiveModel? = all.firstOrNull { it.modelId == modelId }
    /** The ONLY Live model (binding order 2026-11-26): gemini-3.8-live.
     *  Returns it when the user's key offers it, null otherwise — never
     *  another model id. */
    fun firstAvailable(offered: List<String>): LiveModel? =
        all.firstOrNull { m -> offered.any { it == m.modelId || it.endsWith(m.modelId) } }
}
