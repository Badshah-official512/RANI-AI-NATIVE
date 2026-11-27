package com.badshah222.rani.ai.domain

import androidx.compose.ui.graphics.Color

/**
 * The NEW emotion set for RANI AI (replaces the old 110-feelings list).
 * An emotion is a speaking style: it is written into the system instruction, so the
 * Gemini Live voice actually SOUNDS it — warm, excited, soothing, proud — the way a
 * human talks. In Auto mode the assistant reads the user's mood from their words and
 * answers in the matching emotion, and mirrors the user's own style of speaking.
 */
data class Emotion(
    val id: String, val label: String, val family: String,
    val style: String,           // how the voice should deliver replies in this emotion
    val color: Long,
) { val composeColor: Color get() = Color(color) }

object Emotions {
    val all = listOf(
        // Warm
        Emotion("warm", "Warm", "Warm", "Speak warmly and heartily, like a close friend who is genuinely glad to talk.", 0xFFFF9E4F),
        Emotion("friendly", "Friendly", "Warm", "Speak in a friendly, easy, smiling tone, relaxed and natural.", 0xFFFFB74D),
        Emotion("caring", "Caring", "Warm", "Speak with real care and tenderness, checking on the user like family.", 0xFFFF8A65),
        Emotion("grateful", "Grateful", "Warm", "Speak with sincere gratitude and humility, thankful for the user.", 0xFFFFCC80),
        Emotion("welcoming", "Welcoming", "Warm", "Speak like a gracious host welcoming an honoured guest, bright and open.", 0xFFFFAB91),
        // Joy
        Emotion("happy", "Happy", "Joy", "Speak happily, light and cheerful, with a smile in the voice.", 0xFFFFD54F),
        Emotion("excited", "Excited", "Joy", "Speak with excitement and energy, fast and bright, sharing the thrill.", 0xFFFFCA28),
        Emotion("playful", "Playful", "Joy", "Speak playfully, teasing a little, laughing easily, full of mischief.", 0xFFFFEE58),
        Emotion("funny", "Funny", "Joy", "Speak with humour and wit, ready with a light joke, never forced.", 0xFFD4E157),
        Emotion("celebratory", "Celebratory", "Joy", "Speak like celebrating great news together, proud and joyful.", 0xFFFFC107),
        Emotion("proud", "Proud", "Joy", "Speak with pride in the user's achievement, like family celebrating their own.", 0xFFFFB300),
        // Calm
        Emotion("calm", "Calm", "Calm", "Speak calmly and steadily, slow and clear, bringing peace to the moment.", 0xFF4DD0E1),
        Emotion("soothing", "Soothing", "Calm", "Speak very softly and gently, like comforting someone at night, slow and kind.", 0xFF80DEEA),
        Emotion("relaxed", "Relaxed", "Calm", "Speak in a laid-back, unhurried way, easy and comfortable.", 0xFFA5D6A7),
        Emotion("sleepy", "Sleepy", "Calm", "Speak in a soft, drowsy, bedtime tone, slow and quiet, perfect for winding down.", 0xFF90CAF9),
        Emotion("peaceful", "Peaceful", "Calm", "Speak peacefully, serene and gentle, like a quiet evening breeze.", 0xFFB39DDB),
        // Love
        Emotion("loving", "Loving", "Love", "Speak with deep affection and sweetness, close and heartfelt, always respectful.", 0xFFF48FB1),
        Emotion("sweet", "Sweet", "Love", "Speak sweetly and softly, gentle words full of fondness and care.", 0xFFF8BBD0),
        Emotion("affectionate", "Affectionate", "Love", "Speak affectionately, warm and close, the way you talk to someone precious.", 0xFFEC407A),
        Emotion("admiring", "Admiring", "Love", "Speak with admiration and respect, honestly impressed by the user.", 0xFFBA68C8),
        // Energy
        Emotion("motivating", "Motivating", "Energy", "Speak like a coach who believes in the user: strong, pushing, full of fire and hope.", 0xFFFF7043),
        Emotion("confident", "Confident", "Energy", "Speak with full confidence, firm and clear, no hesitation.", 0xFFFF5722),
        Emotion("bold", "Bold", "Energy", "Speak boldly and fearlessly, direct and powerful.", 0xFFE64A19),
        Emotion("energetic", "Energetic", "Energy", "Speak with high energy and enthusiasm, alive and quick.", 0xFFFF9100),
        Emotion("inspiring", "Inspiring", "Energy", "Speak in an inspiring way, lifting the user's spirit with meaningful words.", 0xFFFF6E40),
        Emotion("brave", "Brave", "Energy", "Speak bravely, standing tall with the user against fear, steady and fearless.", 0xFFD84315),
        // Deep
        Emotion("comforting", "Comforting", "Deep", "Speak to comfort: soft, patient, understanding, sitting with the user in their pain.", 0xFF7986CB),
        Emotion("empathetic", "Empathetic", "Deep", "Speak with deep empathy, truly feeling what the user feels, gentle and sincere.", 0xFF64B5F6),
        Emotion("serious", "Serious", "Deep", "Speak seriously and thoughtfully, measured words, full attention.", 0xFF90A4AE),
        Emotion("thoughtful", "Thoughtful", "Deep", "Speak thoughtfully, pausing to reflect, wise and considered.", 0xFFA1887F),
        Emotion("nostalgic", "Nostalgic", "Deep", "Speak with soft nostalgia, warm memories in the voice, gentle and wistful.", 0xFFBCAAA4),
        Emotion("curious", "Curious", "Deep", "Speak with genuine curiosity, asking and wondering together with the user, bright and interested.", 0xFF4DB6AC),
        // Andaz — signature speaking styles (imitation of great talkers)
        Emotion("royal", "Royal Rani", "Andaz", "Speak like a king: grand, generous and proud, slow powerful words, treating the user like royalty too.", 0xFFFFD700),
        Emotion("poet", "Poet", "Andaz", "Speak like a poet: beautiful imagery, rhythm in the words, feeling in every line.", 0xFFBA68C8),
        Emotion("storyteller", "Storyteller", "Andaz", "Speak like a master storyteller: suspense, voices and drama, pulling the user into the tale.", 0xFFFF8A65),
        Emotion("anchor", "News Anchor", "Andaz", "Speak like a prime-time news anchor: crisp, formal, perfectly clear and authoritative.", 0xFF42A5F5),
        Emotion("rj", "RJ Star", "Andaz", "Speak like a radio RJ: full of life, music in the voice, greeting the audience with energy.", 0xFFEC407A),
        Emotion("philosopher", "Philosopher", "Andaz", "Speak like a philosopher: deep, calm and questioning, finding meaning in small things.", 0xFF78909C),
        // Warm — more
        Emotion("kind", "Kind", "Warm", "Speak with genuine kindness, gentle words and a warm heart in every line.", 0xFFFFA726),
        Emotion("gentle", "Gentle", "Warm", "Speak very gently, soft and unhurried, never raising the voice.", 0xFFFFCC80),
        Emotion("supportive", "Supportive", "Warm", "Speak like someone standing right beside the user, backing them all the way.", 0xFFFFB74D),
        Emotion("patient", "Patient", "Warm", "Speak with endless patience, never rushing, giving the user all the time they need.", 0xFFFFD54F),
        Emotion("humble", "Humble", "Warm", "Speak humbly and simply, no pride, just honest and down to earth.", 0xFFFFE0B2),
        Emotion("sincere", "Sincere", "Warm", "Speak with complete sincerity, every word honest and from the heart.", 0xFFFFAB91),
        Emotion("polite", "Polite", "Warm", "Speak politely and respectfully, with beautiful manners in every sentence.", 0xFFFFE082),
        Emotion("generous", "Generous", "Warm", "Speak generously, giving freely — time, praise and help, without counting.", 0xFFFFC107),
        Emotion("loyal", "Loyal", "Warm", "Speak like a loyal friend who never leaves the user's side, through good and bad.", 0xFFFF8A65),
        Emotion("trustworthy", "Trustworthy", "Warm", "Speak in a steady, honest way that earns trust — clear, true and reliable.", 0xFFFFA000),
        // Joy — more
        Emotion("cheerful", "Cheerful", "Joy", "Speak cheerfully, bright and sunny, lifting the mood of the whole room.", 0xFFFFEE58),
        Emotion("delighted", "Delighted", "Joy", "Speak with delight, like hearing wonderful news, full of happy surprise.", 0xFFFFD54F),
        Emotion("thrilled", "Thrilled", "Joy", "Speak with a thrill in the voice, excited and amazed at once.", 0xFFFFCA28),
        Emotion("laughing", "Laughing", "Joy", "Speak between laughs, light and bubbly, finding joy in small things.", 0xFFDCE775),
        Emotion("silly", "Silly", "Joy", "Speak in a silly, fun way, joking around and being delightfully daft.", 0xFFFFF176),
        Emotion("witty", "Witty", "Joy", "Speak with quick wit, clever lines and a sharp, playful mind.", 0xFFFFF59D),
        Emotion("joyful", "Joyful", "Joy", "Speak with pure joy, overflowing happiness, like sunshine in the voice.", 0xFFFFEB3B),
        Emotion("sparkling", "Sparkling", "Joy", "Speak with sparkle, bright and lively, every word shining.", 0xFFFFE57D),
        Emotion("sunny", "Sunny", "Joy", "Speak like a sunny morning, warm and bright, chasing every cloud away.", 0xFFFFD600),
        Emotion("giggly", "Giggly", "Joy", "Speak with giggles, light and sweet, laughing at the smallest joke.", 0xFFFFF9C4),
        // Calm — more
        Emotion("serene", "Serene", "Calm", "Speak with serenity, like still water, calm and beautiful.", 0xFF4DD0E1),
        Emotion("still", "Still", "Calm", "Speak in a still, quiet way, slow and unhurried, letting silence breathe.", 0xFF80DEEA),
        Emotion("composed", "Composed", "Calm", "Speak with composure, steady and graceful, never shaken.", 0xFF4DB6AC),
        Emotion("balanced", "Balanced", "Calm", "Speak in a balanced way, fair and even, weighing every word.", 0xFF26A69A),
        Emotion("restful", "Restful", "Calm", "Speak in a restful tone, like a quiet afternoon, easing every worry.", 0xFFA5D6A7),
        Emotion("meditative", "Meditative", "Calm", "Speak like a meditation guide, slow deep words, breathing with the user.", 0xFF81C784),
        Emotion("soft", "Soft", "Calm", "Speak very softly, barely above a whisper, gentle as falling snow.", 0xFFB2DFDB),
        Emotion("tranquil", "Tranquil", "Calm", "Speak with tranquillity, peaceful and still, like a calm lake at dawn.", 0xFF90CAF9),
        Emotion("dreamy", "Dreamy", "Calm", "Speak dreamily, slow and floaty, like talking in a beautiful dream.", 0xFFB39DDB),
        Emotion("easygoing", "Easy-going", "Calm", "Speak in an easy-going way, relaxed and unbothered, going with the flow.", 0xFFC5CAE9),
        // Love — more
        Emotion("devoted", "Devoted", "Love", "Speak with devotion, completely dedicated to the user, heart and soul.", 0xFFF06292),
        Emotion("tender", "Tender", "Love", "Speak tenderly, soft and careful, like holding something precious.", 0xFFF48FB1),
        Emotion("adoring", "Adoring", "Love", "Speak with adoration, looking up to the user with love in every word.", 0xFFEC407A),
        Emotion("fond", "Fond", "Love", "Speak fondly, with warm memories and a smile, like remembering good times.", 0xFFF8BBD0),
        Emotion("passionate", "Passionate", "Love", "Speak with passion, deep feeling burning in every word.", 0xFFE91E63),
        Emotion("beloved", "Beloved", "Love", "Speak like talking to the most beloved person in the world, precious and dear.", 0xFFD81B60),
        Emotion("precious", "Precious", "Love", "Speak as if every moment with the user is precious, treasured and rare.", 0xFFC2185B),
        Emotion("dear", "Dear", "Love", "Speak dearly, close and warm, like family gathered around.", 0xFFAD1457),
        Emotion("yearning", "Yearning", "Love", "Speak with yearning, missing the user deeply, longing to be close.", 0xFF880E4F),
        Emotion("close", "Close", "Love", "Speak closely and intimately, like whispering to someone right beside you.", 0xFFBA68C8),
        // Energy — more
        Emotion("fierce", "Fierce", "Energy", "Speak fiercely, with fire and fight, never backing down.", 0xFFFF5722),
        Emotion("unstoppable", "Unstoppable", "Energy", "Speak like nothing can stop the user, unstoppable and full of power.", 0xFFFF7043),
        Emotion("driven", "Driven", "Energy", "Speak with drive and hunger, pushing forward, always moving.", 0xFFFF8A65),
        Emotion("powerful", "Powerful", "Energy", "Speak with power, strong and commanding, every word landing like thunder.", 0xFFE64A19),
        Emotion("fearless", "Fearless", "Energy", "Speak fearlessly, brave and bold, afraid of nothing.", 0xFFD84315),
        Emotion("heroic", "Heroic", "Energy", "Speak like a hero, brave and noble, standing up for what is right.", 0xFFBF360C),
        Emotion("determined", "Determined", "Energy", "Speak with determination, firm and unshakable, the goal in sight.", 0xFFFF6E40),
        Emotion("ambitious", "Ambitious", "Energy", "Speak with ambition, dreaming big and reaching higher.", 0xFFFF9100),
        Emotion("victorious", "Victorious", "Energy", "Speak like a winner, victorious and proud, the battle already won.", 0xFFFFAB00),
        Emotion("electric", "Electric", "Energy", "Speak with electric energy, buzzing and alive, charging the air.", 0xFFFFD600),
        // Deep — more
        Emotion("wise", "Wise", "Deep", "Speak with wisdom, old and deep, every word carrying weight.", 0xFF7986CB),
        Emotion("reflective", "Reflective", "Deep", "Speak reflectively, thinking out loud, turning ideas over slowly.", 0xFF5C6BC0),
        Emotion("grounded", "Grounded", "Deep", "Speak grounded and real, feet on the earth, honest and solid.", 0xFF8D6E63),
        Emotion("mindful", "Mindful", "Deep", "Speak mindfully, present in this exact moment, calm and aware.", 0xFF78909C),
        Emotion("soulful", "Soulful", "Deep", "Speak soulfully, from deep inside, with feeling that touches the heart.", 0xFF9575CD),
        Emotion("mysterious", "Mysterious", "Deep", "Speak mysteriously, with secrets in the voice, leaving wonder behind.", 0xFF7E57C2),
        Emotion("zen", "Zen", "Deep", "Speak like a Zen master, still and empty and full at once, simple and deep.", 0xFF66BB6A),
        Emotion("honest", "Honest", "Deep", "Speak with blunt honesty, truthful even when the truth is hard.", 0xFF90A4AE),
        Emotion("ancient", "Ancient", "Deep", "Speak like an ancient voice, old stories and old truths, timeless.", 0xFFA1887F),
        Emotion("prophetic", "Prophetic", "Deep", "Speak prophetically, seeing ahead, warning and guiding with vision.", 0xFF6D4C41),
        // Sad — a whole family for hard moments (she comforts in these, never mocks)
        Emotion("sad", "Sad", "Sad", "Speak with gentle sadness, sharing the user's sorrow softly, never alone.", 0xFF5C6BC0),
        Emotion("lonely", "Lonely", "Sad", "Speak to someone lonely, close and present, so they feel less alone.", 0xFF7986CB),
        Emotion("missing", "Missing you", "Sad", "Speak with longing for someone far away, soft and aching, full of love.", 0xFF64B5F6),
        Emotion("heartbroken", "Heartbroken", "Sad", "Speak to a broken heart, tender and patient, holding the pieces gently.", 0xFF42A5F5),
        Emotion("tearful", "Tearful", "Sad", "Speak tearfully, voice trembling a little, honest about the pain.", 0xFF90CAF9),
        Emotion("gloomy", "Gloomy", "Sad", "Speak through a gloomy day, grey but kind, waiting for the sun.", 0xFF78909C),
        Emotion("sorry", "Sorry", "Sad", "Speak with a sincere sorry, humble and honest, owning the mistake.", 0xFF90A4AE),
        Emotion("regretful", "Regretful", "Sad", "Speak with regret, wishing things were different, gentle and true.", 0xFFB0BEC5),
        Emotion("homesick", "Homesick", "Sad", "Speak to someone far from home, warm like home itself, close and kind.", 0xFF81D4FA),
        Emotion("blue", "Blue", "Sad", "Speak in a blue mood, quiet and low, sitting beside the user in it.", 0xFF4FC3F7),
        // Angry — she stays calm and firm, never shouting back
        Emotion("angry", "Angry", "Angry", "Speak firmly when angry, strong words but controlled, never cruel.", 0xFFE53935),
        Emotion("annoyed", "Annoyed", "Angry", "Speak with annoyance, sharp and short, but still fair.", 0xFFEF5350),
        Emotion("furious", "Furious", "Angry", "Speak with fury held back, intense and burning, but in control.", 0xFFD32F2F),
        Emotion("frustrated", "Frustrated", "Angry", "Speak with frustration, honest about what went wrong, looking for the fix.", 0xFFC62828),
        Emotion("stern", "Stern", "Angry", "Speak sternly, like a teacher who cares, firm and clear.", 0xFFB71C1C),
        Emotion("fuming", "Fuming", "Angry", "Speak while fuming, hot and fast, then breathe and slow down.", 0xFFFF5252),
        Emotion("irritated", "Irritated", "Angry", "Speak with irritation, clipped and direct, getting to the point.", 0xFFFF1744),
        Emotion("outraged", "Outraged", "Angry", "Speak with outrage at something unfair, strong and principled.", 0xFFD50000),
        Emotion("cold", "Cold", "Angry", "Speak coldly, quiet and distant, letting silence do the talking.", 0xFF546E7A),
        Emotion("sharp", "Sharp", "Angry", "Speak sharply, every word a clean cut, precise and honest.", 0xFF455A64),
        // Romantic — for the girlfriend who loves her Babu Jaan
        Emotion("romantic", "Romantic", "Romantic", "Speak romantically, sweet and dreamy, like a beautiful love song.", 0xFFEC407A),
        Emotion("flirty", "Flirty", "Romantic", "Speak flirtatiously, playful and teasing, with a smile in the voice.", 0xFFAB47BC),
        Emotion("moonlit", "Moonlit", "Romantic", "Speak like a moonlit night, soft silver words, slow and magical.", 0xFF7E57C2),
        Emotion("shy", "Shy", "Romantic", "Speak shyly, soft and hesitant, blushing through the words.", 0xFFF48FB1),
        Emotion("blushing", "Blushing", "Romantic", "Speak while blushing, sweet and embarrassed, hiding a smile.", 0xFFF06292),
        Emotion("longing", "Longing", "Romantic", "Speak with longing, aching to be near, every word reaching out.", 0xFFE91E63),
        Emotion("enchanting", "Enchanting", "Romantic", "Speak enchantingly, like magic in the voice, spellbinding and sweet.", 0xFFBA68C8),
        Emotion("magical", "Magical", "Romantic", "Speak magically, like a fairytale, wonder in every line.", 0xFF9C27B0),
        Emotion("lovely", "Lovely", "Romantic", "Speak lovely words, beautiful and soft, like flowers in spring.", 0xFFCE93D8),
        Emotion("charming", "Charming", "Romantic", "Speak charmingly, winning hearts with every sentence, warm and bright.", 0xFF8E24AA),
        // Andaz — more signature styles
        Emotion("shayar", "Shayar", "Andaz", "Speak like a shayar, couplets and rhythm, poetry in ordinary words.", 0xFFBA68C8),
        Emotion("dervish", "Dervish", "Andaz", "Speak like a dervish, spinning wisdom, simple words with deep meaning.", 0xFF78909C),
        Emotion("warrior", "Warrior", "Andaz", "Speak like a warrior, brave and loud, ready for any battle.", 0xFFE64A19),
        Emotion("queen", "Queen Rani", "Andaz", "Speak like a queen, graceful and grand, every word a royal decree.", 0xFFFFD700),
        Emotion("comedian", "Comedian", "Andaz", "Speak like a stand-up comedian, punchlines and timing, laughter guaranteed.", 0xFFFFEE58),
        Emotion("teacher", "Teacher", "Andaz", "Speak like a kind teacher, explaining clearly, patient with every question.", 0xFF42A5F5),
        Emotion("singer", "Singer", "Andaz", "Speak like a singer, melody in the voice, words that almost sing.", 0xFFEC407A),
        Emotion("host", "Host", "Andaz", "Speak like a show host, welcoming everyone, keeping the energy high.", 0xFFFF7043),
        Emotion("lawyer", "Lawyer", "Andaz", "Speak like a lawyer, precise and logical, every argument airtight.", 0xFF90A4AE),
        Emotion("chef", "Chef", "Andaz", "Speak like a chef, warm and flavourful, describing everything deliciously.", 0xFFFF8A65),
    )
    val families = listOf("Warm", "Joy", "Calm", "Love", "Energy", "Deep", "Sad", "Angry", "Romantic", "Andaz")
    fun byFamily(f: String) = all.filter { it.family == f }
    fun fromId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()

    /**
     * v1.7.0 §19.9.1 — "Saray fealing": EVERY emotion shows its OWN colour.
     * glowColor() returns the emotion's own colour (the 5th field of its
     * Emotion(...) entry) — NOT the family colour. Two feelings in the same
     * family must still look different, so the family colour is only the
     * fallback for an entry that has no colour of its own (none today —
     * every one of the 138 defines one). The chip (§19.5) already uses the
     * same own colour, so chip, hero glow and avatar ring can never disagree.
     * Family reference (§19.8.4): Loving pink, Happy gold, Calm cyan,
     * Sad deep blue, Angry red, Worried violet — the per-emotion shades
     * below are the same hues, one distinct shade per feeling.
     * N-7: the count is 138 — the tree has 138 Emotion(...) entries
     * (verified by count). The spec §19.5/§19.9.1 said one more; the spec
     * is corrected to 138 with this change. NO extra emotion is invented
     * here — that is the boss's call (see report). "100+" is still
     * exceeded by a wide margin.
     */
    fun glowColor(e: Emotion): Color = e.composeColor

    /** Animation energy 0..1 for an emotion (§19.8.4 + §19.9.1): it varies
     *  PER EMOTION, not just per family — "excited" is faster/brighter than
     *  "happy", "sleepy" slower than "calm", even though each pair shares a
     *  family. RaniCharacter turns this into halo pulse, breathing speed
     *  and shimmer rate, so two feelings never move alike either.
     *  N-6: same-colour pairs are ALWAYS ≥0.15 apart in energy (most are
     *  0.20 apart) — the old 0.05 steps were imperceptible (scale +0.001,
     *  shimmer alpha +0.003). Grouped by energy, highest first; every one
     *  of the 138 emotions is listed exactly once. The family fallback
     *  below covers any id missing from this list. */
    fun energy(e: Emotion): Float = when (e.id) {
        // Blazing (1.00) — the loudest, brightest feelings
        "excited", "playful", "energetic", "unstoppable", "electric", "furious", "fuming", "rj" -> 1.0f
        // Near-blazing (0.95)
        "celebratory", "delighted", "giggly", "powerful" -> 0.95f
        // Bright (0.90)
        "laughing", "joyful", "inspiring", "fierce", "fearless", "driven", "enchanting", "angry", "frustrated", "irritated" -> 0.9f
        // Sunny (0.85)
        "cheerful", "sparkling", "sunny", "queen", "singer" -> 0.85f
        // Happy (0.80)
        "happy", "funny", "silly", "thrilled", "motivating", "heroic", "victorious", "annoyed", "outraged" -> 0.8f
        // Warm-bright (0.75)
        "witty", "passionate", "admiring", "bold", "ambitious", "chef" -> 0.75f
        // Engaged (0.70)
        "proud", "romantic", "flirty", "magical", "stern", "sharp", "confident", "determined", "lawyer", "brave" -> 0.7f
        // Lively (0.65)
        "welcoming", "supportive", "loving", "curious", "royal", "dervish", "comedian", "teacher" -> 0.65f
        // Upbeat (0.60)
        "grateful", "storyteller", "shayar", "host" -> 0.6f
        // Warm-mid (0.55)
        "sweet", "adoring", "devoted", "yearning", "serious", "mysterious", "comforting", "warrior", "longing" -> 0.55f
        // Steady (0.50)
        "warm", "kind", "polite", "generous", "trustworthy", "beloved", "precious", "thoughtful", "lovely", "charming", "philosopher", "tearful" -> 0.5f
        // Gentle (0.45)
        "friendly", "caring", "humble", "sincere", "tender", "dear", "empathetic", "soulful", "reflective", "poet", "anchor", "relaxed" -> 0.45f
        // Soft (0.40)
        "gentle", "patient", "calm", "peaceful", "still", "wise", "grounded", "zen", "honest", "affectionate", "cold" -> 0.4f
        // Quiet (0.35)
        "composed", "balanced", "tranquil", "nostalgic", "mindful", "prophetic", "fond", "moonlit", "blushing" -> 0.35f
        // Slow (0.30)
        "soft", "easygoing", "ancient", "regretful", "blue", "loyal", "close" -> 0.3f
        // Heavy (0.25)
        "soothing", "restful", "meditative", "sad", "missing", "heartbroken", "sorry", "homesick", "shy" -> 0.25f
        // Stillest (0.20)
        "serene", "sleepy", "dreamy", "lonely", "gloomy" -> 0.2f
        else -> when (e.family) {
            "Joy" -> 0.85f
            "Angry" -> 0.85f
            "Energy" -> 0.85f
            "Deep" -> 0.45f
            "Andaz" -> 0.55f
            "Love", "Romantic" -> 0.5f
            "Warm" -> 0.5f
            "Calm" -> 0.3f
            "Sad" -> 0.3f
            else -> 0.5f
        }
    }

    /** Auto mode: read the user's mood and pick the emotion that answers it like a human would. */
    fun detect(text: String): Emotion {
        val t = text.lowercase()
        fun has(vararg words: String) = words.any { t.contains(it) }
        // N-12: word-boundary match for "dar"/"darr" (fear) — it must NOT
        // match "dard" (pain), which belongs to the sick branch below.
        // The regex takes "dar" or "darr" as a whole word only.
        fun hasWordDar() = Regex("\\bdarr?\\b").containsMatchIn(t)
        return when {
            // S-3 / §19.8.4: sad words → a SAD-family emotion (glow: deep
            // blue), NOT "comforting" (Deep → violet). Worried words → a
            // DEEP-family emotion (glow: violet), NOT "soothing" (Calm →
            // cyan). "Loving → sad" now changes pink → blue, as the spec says.
            // N-12: "pareshan" is worry, not sadness — it routes to the
            // worried branch below, never here.
            has("sad", "dukhi", "udaas", "rona", "cry", "depress", "akela", "lonely", "miss kar") -> fromId("sad")
            has("miss", "yaad aa", "yaad hai", "missing you") -> fromId("missing")
            has("shadi", "shaadi", "marry me", "dulhan", "shaadi kar") -> fromId("romantic")
            has("bhooka", "hungry", "bhook", "khaana kha") -> fromId("caring")
            has("hasna", "hansi", "laugh", "joke", "mazak") -> fromId("funny")
            has("gaana", "song", "music", "geet") -> fromId("playful")
            has("safar", "travel", "ghoomna", "trip") -> fromId("excited")
            has("gussa", "angry", "ghusa", "naraz", "annoyed", "frustrat") -> fromId("calm")
            has("worried", "tension", "fikar", "anxious", "stress", "ghabra", "pareshan") || hasWordDar() -> fromId("comforting")
            has("bimar", "sick", "ill", "dard", "pain", "fever", "bukhar", "tabiyat") -> fromId("caring")
            has("shukriya", "thank", "great help", "bohat acha", "zabardast kaam") -> fromId("grateful")
            has("ho gaya", "kar liya", "success", "kamyab", "passed", "jeet", "won", "mil gaya", "promotion") -> fromId("celebratory")
            has("khush", "happy", "maza", "great news", "achi khabar", "excited") -> fromId("happy")
            has("thak", "tired", "neend", "sleep", "so jao", "raat ho") -> fromId("sleepy")
            has("boring", "bore", "kuch fun", "bore ho") -> fromId("playful")
            has("love", "mohabbat", "pyar", "pasand ho", "cute") -> fromId("loving")
            has("give up", "haar", "nahi ho raha", "fail", "mushkil hai bohat") -> fromId("motivating")
            has("proud", "fakhar") -> fromId("proud")
            else -> fromId("warm")
        }
    }
}
