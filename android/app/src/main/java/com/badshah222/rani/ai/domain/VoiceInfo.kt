package com.badshah222.rani.ai.domain

/** A voice as reported by the installed Android TTS engine (kept for the backup phone-voice engine). */
data class VoiceInfo(
    val name: String,            // engine voice id, e.g. "en-us-x-iom-local"
    val localeTag: String,       // BCP-47, e.g. "en-US"
    val requiresNetwork: Boolean,
    val quality: Int,            // Voice.getQuality()
)
