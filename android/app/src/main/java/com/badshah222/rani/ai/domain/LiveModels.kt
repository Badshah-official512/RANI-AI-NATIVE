package com.badshah222.rani.ai.domain

/**
 * Gemini Live voice models — verified against Google's Live API docs (Nov 2026).
 * key -> (UI label, model id, API version/endpoint).
 * Default key is "3.8" (Gemini 3.8 Live) — GA since 15 Sep 2026, Google's
 * recommended Live model, free tier included. Correction of the July 2026
 * note: "gemini-3.8-live" DOES exist — the July research was stale. The old
 * models shut down on 17 Nov 2026, which is exactly why the call never
 * connected: "gemini-3.1-flash-live-preview" and
 * "gemini-2.5-flash-native-audio-preview-12-2025" are dead, and
 * "gemini-2.0-flash-live-001" is gone from the production list too — all
 * three are removed here so the ladder never wastes time on a dead model.
 * All models use v1beta (the Live API endpoint). The engine tries the
 * selected model first, then the next model in this order if the server
 * rejects the setup.
 *
 * SDK VERIFICATION 2026-11-26 (the fix that finally made the call connect):
 * the official google-genai SDK (Python) connects on v1beta in 1.4 s with the
 * SAME key the app uses. The two things that matter, both copied into
 * LiveSession: (1) the API key goes in the `x-goog-api-key` HEADER, never in
 * a `?key=` URL param — with ?key= the server accepts the socket but NEVER
 * sends setupComplete for gemini-3.8-live (silent 60 s+, both keys, every
 * setup shape, v1beta AND v1alpha); (2) the setup is the SDK's exact shape —
 * camelCase on top, snake_case inside speechConfig, role:"user" on the system
 * instruction, no contextWindowCompression/sessionResumption unless resuming.
 * The extended-thinking model REQUIRES thinkingLevel HIGH (server closes 1007
 * without it); plain 3.8-live REJECTS thinkingConfig (1007 the other way) —
 * hence the `thinking` flag. NOTE: this VM's direct (non-proxy) network path
 * also goes silent for Live — the SDK only works here through the egress
 * proxy; the phone connects directly, so the phone test is the real proof.
 */
data class LiveModel(val key: String, val label: String, val modelId: String, val version: String, val thinking: Boolean = false)

object LiveModels {
    val all = listOf(
        LiveModel("3.8", "Gemini 3.8 Live (Default)", "gemini-3.8-live", "v1beta"),
        LiveModel("3.8-thinking", "Gemini 3.8 Live Extended Thinking", "gemini-3.8-live-extended-thinking", "v1beta", thinking = true),
        LiveModel("native-ga", "2.5 Flash Native Audio (GA)", "gemini-live-2.5-flash-native-audio", "v1beta"),
    )
    const val DEFAULT_KEY = "3.8"
    fun fromKey(key: String?): LiveModel = all.firstOrNull { it.key == key } ?: all.first()
    fun fromModelId(modelId: String?): LiveModel? = all.firstOrNull { it.modelId == modelId }
    /** First MYRA model (in MYRA's order) that the user's key actually offers. */
    fun firstAvailable(offered: List<String>): LiveModel? =
        all.firstOrNull { m -> offered.any { it == m.modelId || it.endsWith(m.modelId) } }
}
