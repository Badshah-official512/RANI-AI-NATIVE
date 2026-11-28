package com.badshah222.rani.ai.core

/**
 * v2.0.0 §22.3 — backstop farewell detector (native safety net; the page
 * backstop in rani-live.html is primary). Runs on the SAME
 * LiveState.transcript collector that feeds memory — no new listener, no
 * new capture, no new upload.
 *
 * Strict on purpose: a wrong end is worse than a missed end. It fires
 * ONLY when the farewell IS the turn (after filler-stripping, ≤ 8 words),
 * is the final clause of a short turn (≤ 12 words), or repeats across
 * two turns; NEVER inside a story, quote, song, question, or
 * hypothetical. When in doubt it does NOT fire — the prompt rule (the
 * model, which understands context) is the primary path.
 *
 * Mirrors detectFarewell() in rani-live.html — keep the two in step:
 * same phrase list, same fillers, same guards, same three rules.
 */
object FarewellDetector {
    private val phrases = listOf(
        "good night", "allah hafiz", "khuda hafiz", "goodbye", "chalo bye",
        "cut kardo", "bas kardo", "khatam karo", "band karo", "thats enough",
        "stop now", "end the call", "bas ab", "bye",
        "شب بخیر", "اللہ حافظ", "خدا حافظ",
    )
    private val fillers = listOf(
        "theek hai", "theek he", "okay", "ok", "acha", "achaa",
        "chalo", "to", "ab", "han", "haan", "hmm",
    )
    private val questionWords = listOf(
        "kya", "what", "why", "kaise", "kab", "kaun", "matlab", "agar", "if",
    )
    private val storyMarkers = listOf(
        "us ne", "usne", "kaha tha", "keha tha", "phir",
        "story", "gaana", "song", "us ko", "usko",
    )

    /** Lowercase, apostrophes dropped ("that's" -> "thats"), punctuation
     *  to spaces, spaces collapsed. '?' is stripped here, so callers that
     *  need the question guard must check the raw text (see
     *  isQuestionOrStory). */
    fun normalize(s: String): String = s.lowercase()
        .replace("’", "").replace("'", "")
        .replace(Regex("[.,!?;:\"()\\-_/\\\\|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Longest phrase first, so "goodbye" wins over "bye" and "chalo bye"
     *  wins over "bye" when both are present. */
    fun phraseIn(norm: String): String? =
        phrases.sortedByDescending { it.length }.firstOrNull { norm.contains(it) }

    /** Question ("good night ka matlab kya hai?") or story/quote/song/
     *  hypothetical ("us ne good night kaha tha, phir…"). '?' is checked
     *  on the raw text because normalize() strips it. */
    fun isQuestionOrStory(rawText: String, norm: String): Boolean {
        if (rawText.contains("?")) return true
        if (questionWords.any { norm.contains(it) }) return true
        if (storyMarkers.any { norm.contains(it) }) return true
        return false
    }

    /**
     * Strict matcher. Returns the matched farewell phrase, or null.
     * @param prevTurnHadFarewell true when the previous user turn also
     *   contained a farewell phrase (rule C: he said it twice — he means
     *   it). Callers must pass false for a turn that only grew since it
     *   was last evaluated — that is the SAME turn, not a repeat.
     */
    fun detect(text: String, prevTurnHadFarewell: Boolean): String? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        val phrase = phraseIn(norm) ?: return null
        if (isQuestionOrStory(text, norm)) return null
        val words = norm.split(" ").filter { it.isNotEmpty() }
        var stripped = " $norm "
        fillers.sortedByDescending { it.length }.forEach { f ->
            stripped = stripped.replace(" $f ", " ")
        }
        stripped = stripped.replace(Regex("\\s+"), " ").trim()
        // Rule A: the farewell IS the turn — only fillers + farewell.
        if (words.size <= 8 && stripped == phrase) return phrase
        // Rule B: the farewell is the final clause of a short turn — the
        // phrase the turn ENDS with (not necessarily the longest match:
        // "Allah Hafiz, bye" ends with "bye"). Word-boundary checked, so
        // "goodbye" does not match as a trailing "bye".
        if (words.size <= 12) {
            val tail = phrases.sortedByDescending { it.length }.firstOrNull {
                norm.endsWith(it) && (norm.length == it.length || norm[norm.length - it.length - 1] == ' ')
            }
            if (tail != null) return tail
        }
        // Rule C: repeated across two turns.
        if (prevTurnHadFarewell) return phrase
        return null
    }

    /** Loose check for the assistant's goodbye acknowledgement ("good
     *  night, Babu Jaan, sleep well…") — used by the native safety net to
     *  confirm she has had her turn to speak before it ends the call. */
    fun isGoodbye(text: String): Boolean {
        val norm = normalize(text)
        return phrases.any { norm.contains(it) } ||
            norm.contains("sleep well") || norm.contains("sweet dreams")
    }
}
