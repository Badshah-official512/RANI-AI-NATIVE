package com.badshah222.rani.ai.domain

/**
 * Gemini Live voice models — verified against Google's Live API docs (July 2026).
 * key -> (UI label, model id, API version/endpoint).
 * Default key is "3.1" (Gemini 3.1 Flash Live) — the current flagship Live model.
 * All models use v1beta (the Live API endpoint). Two old entries were removed:
 * "gemini-3.8-live" never existed, and "gemini-2.5-flash-native-audio-latest"
 * was retired from the model catalog — replaced by the GA native-audio model.
 * The engine tries the selected model first, then the next model in this order
 * if the server rejects the setup.
 */
data class LiveModel(val key: String, val label: String, val modelId: String, val version: String)

object LiveModels {
    val all = listOf(
        LiveModel("3.1", "Gemini 3.1 Flash Live (Default)", "gemini-3.1-flash-live-preview", "v1beta"),
        LiveModel("native-dec", "2.5 Native Audio (Dec 2025 Preview)", "gemini-2.5-flash-native-audio-preview-12-2025", "v1beta"),
        LiveModel("native-ga", "2.5 Flash Native Audio (GA)", "gemini-live-2.5-flash-native-audio", "v1beta"),
        LiveModel("2.0", "Gemini 2.0 Flash Live", "gemini-2.0-flash-live-001", "v1beta"),
    )
    const val DEFAULT_KEY = "3.1"
    fun fromKey(key: String?): LiveModel = all.firstOrNull { it.key == key } ?: all.first()
    fun fromModelId(modelId: String?): LiveModel? = all.firstOrNull { it.modelId == modelId }
    /** First MYRA model (in MYRA's order) that the user's key actually offers. */
    fun firstAvailable(offered: List<String>): LiveModel? =
        all.firstOrNull { m -> offered.any { it == m.modelId || it.endsWith(m.modelId) } }
}
