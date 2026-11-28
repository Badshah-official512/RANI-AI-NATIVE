package com.badshah222.rani.ai.core

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v1.7.0 (§19.3.3) — the ONE shared Live-call state, fed by the RaniBridge
 * callbacks from the bundled Live page (RaniLiveActivity) and read by the
 * Home hero (§19.1), the status pill + character animation (§19.8) and the
 * FloatingOrbService (§19.3). No surface reads HttpLiveSession any more —
 * that engine is dead in v1.6.1 and its state never becomes active.
 *
 * state values: "idle" | "connecting" | "listening" | "speaking" |
 * "thinking" | "error" | "ended". active == a call is live right now.
 * transcript carries the latest (who, text) turn for emotion auto-detect.
 */
object LiveState {
    data class Transcript(val who: String, val text: String, val atMs: Long = System.currentTimeMillis())

    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    private val _captionWho = MutableStateFlow("")
    val captionWho: StateFlow<String> = _captionWho.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _transcript = MutableStateFlow<Transcript?>(null)
    val transcript: StateFlow<Transcript?> = _transcript.asStateFlow()

    /** Glow colour the hero character + floating avatar ring show right now
     *  (§19.8.4) — driven by the auto-detected emotion family. */
    private val _glowColor = MutableStateFlow(Color(0xFF8B3DFF))
    val glowColor: StateFlow<Color> = _glowColor.asStateFlow()

    /** v1.8.0 §20.2.5 — live output audio level, 0..1, fed by the page's
     *  100 ms analyser interval via RaniBridge.onLiveAudioLevel. Smoothed
     *  (exponential, 0.35) so the dot field vibrates with her voice without
     *  jittering on every sample; 0 when idle / not speaking / no analyser. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    fun onAudioLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _audioLevel.value = _audioLevel.value * 0.65f + clamped * 0.35f
    }

    fun setState(s: String) {
        _state.value = s
        if (s == "speaking" || s == "listening" || s == "thinking" || s == "connecting") _active.value = true
        if (s == "ended" || s == "error") _active.value = false
    }

    fun onConnected() { _active.value = true; _state.value = "listening"; _muted.value = false }

    fun onEnded() { _active.value = false; _state.value = "ended"; _caption.value = ""; _captionWho.value = ""; _audioLevel.value = 0f }

    /** v3.0.0 §23.2 Fix 5: phone-number-like digit runs are masked in
     *  captions before display — the name is shown, never the digits. */
    fun maskNumberText(text: String): String {
        if (text.isBlank()) return text
        var out = text
        // Long digit runs (with spaces/dashes/+): mask the middle digits.
        out = out.replace(Regex("[+]?[0-9][0-9 \\-.]{7,}[0-9]")) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length < 8) m.value else "••• " + digits.takeLast(2)
        }
        return out
    }

    fun setCaption(who: String, text: String) { _captionWho.value = who; _caption.value = maskNumberText(text) }

    /** v3.0.0 §23.1 item 5: the Live page asked to start screen-sharing —
     *  AppNav observes this and opens the Screen Share screen (system
     *  consent flow). Consumed (set back to false) by the observer. */
    val screenShareRequested = MutableStateFlow(false)

    /** v3.0.0 §23.3: the Live session flipped the top-bar Live switch
     *  herself ("bas hogaya" -> OFF). The Home screen observes this and
     *  refreshes the toggle. Null = no pending change. */
    val liveVoiceEvent = MutableStateFlow<Boolean?>(null)

    fun setMuted(m: Boolean) { _muted.value = m }

    fun onTranscript(who: String, text: String) {
        if (text.isBlank()) return
        _transcript.value = Transcript(who, text)
        setCaption(who, text)
        if (who == "assistant") _state.value = "speaking" else _state.value = "listening"
    }

    fun setGlowColor(c: Color) { _glowColor.value = c }
}
