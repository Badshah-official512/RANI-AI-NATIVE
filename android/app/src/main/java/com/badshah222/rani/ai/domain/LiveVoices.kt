package com.badshah222.rani.ai.domain

/**
 * The REAL voices of RANI AI: Google's Gemini Live prebuilt voices — the same
 * natural, human voices the reference apps (LIA / MYRA) use. These are NOT the
 * phone's robotic text-to-speech voices: the Live API generates native audio with
 * real tone, breathing and emotion. Names and style words are Google's own
 * documented descriptors for each voice.
 *
 * RANI is female-only: every voice here is a girl's voice. No male voice
 * appears anywhere in RANI. Default = Aoede (MYRA's own female voice).
 */
data class LiveVoice(val name: String, val style: String, val female: Boolean) {
    val label: String get() = "$name — $style"
}

object LiveVoices {
    // Owner's rule: RANI (girlfriend) voices only — the best few of Google's
    // Gemini Live female voices, MYRA-style. Default = Aoede.
    val female = listOf(
        LiveVoice("Aoede", "Breezy, sweet", true),
        LiveVoice("Kore", "Firm, confident", true),
        LiveVoice("Zephyr", "Bright, fresh", true),
        LiveVoice("Leda", "Youthful, lovely", true),
        LiveVoice("Sulafat", "Warm, gentle", true),
        LiveVoice("Callirrhoe", "Easy-going, soft", true),
    )
    val all: List<LiveVoice> = female
    fun fromName(name: String?): LiveVoice = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: female.first()
}
