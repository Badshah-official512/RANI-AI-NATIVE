package com.badshah222.rani.ai.domain

/**
 * MYRA Live voice models — ported 1:1 from aria.py LIVE_MODELS (MYRA Ultra).
 * key -> (UI label, model id, API version/endpoint).
 * Default key is "3.1" (Gemini 3.1 Flash Live), exactly as MYRA ships it.
 * The engine tries the selected model first, then the next MYRA model in
 * this order if the server rejects the setup.
 */
data class LiveModel(val key: String, val label: String, val modelId: String, val version: String)

object LiveModels {
    val all = listOf(
        LiveModel("3.1", "Gemini 3.1 Flash Live (Default)", "gemini-3.1-flash-live-preview", "v1beta"),
        LiveModel("native", "2.5 Flash Native Audio", "gemini-2.5-flash-native-audio-latest", "v1alpha"),
        LiveModel("native-dec", "2.5 Native Audio (Dec 2025 Preview)", "gemini-2.5-flash-native-audio-preview-12-2025", "v1alpha"),
        LiveModel("3.8", "Gemini 3.8 Live", "gemini-3.8-live", "v1beta"),
    )
    const val DEFAULT_KEY = "3.1"
    fun fromKey(key: String?): LiveModel = all.firstOrNull { it.key == key } ?: all.first()
    fun fromModelId(modelId: String?): LiveModel? = all.firstOrNull { it.modelId == modelId }
    /** First MYRA model (in MYRA's order) that the user's key actually offers. */
    fun firstAvailable(offered: List<String>): LiveModel? =
        all.firstOrNull { m -> offered.any { it == m.modelId || it.endsWith(m.modelId) } }
}
