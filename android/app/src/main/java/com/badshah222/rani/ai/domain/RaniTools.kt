package com.badshah222.rani.ai.domain

/**
 * MYRA's Apps & Tools, Automation routines and Web Search — ported 1:1 from
 * myra_pages.py (AppsToolsPage.AI_TOOLS, AutomationPage routines,
 * WebSearchPage.ENGINES / SITES). Prompts are MYRA's exact words; RANI only
 * swaps the name ("MYRA" -> "RANI") where the prompt addresses the assistant.
 */
object RaniTools {
    data class AiTool(val name: String, val ask: String?, val template: String)

    /** (name, ask-prompt shown to the user, Gemini prompt template with {x}). */
    val aiTools = listOf(
        AiTool("Generate Image", "Is description par ek image banao:", "Generate an image: {x}"),
        AiTool("Summarize Text", "Jis text ko summarize karna hai wo paste karo:", "Summarize this text briefly:\n{x}"),
        AiTool("Translate", "Kya translate karna hai? (language bhi likho, jaise 'Hindi to English: ...')", "Translate: {x}"),
        AiTool("Write Code", "Kaunsa code chahiye? (language + kya karna hai)", "Write code for: {x}"),
        AiTool("Create PDF", "PDF mein kya likhna hai / topic?", "Create a PDF document about: {x}"),
        AiTool("Analyze File", null, "Analyze this file and give key insights: {x}"),
        AiTool("Plan My Day", "Aaj ke kaam / priorities likho:", "Plan my day with these tasks, time-blocked: {x}"),
    )

    data class Routine(val name: String, val prompt: String, val volume: Int?, val brightness: Int?)

    /** (name, prompt sent to RANI, local volume 0-100, local brightness 0-100). */
    val routines = listOf(
        Routine("Good Morning", "Aaj ki date, mausam aur mere pending tasks batao. Warm good-morning ke saath.", null, null),
        Routine("Focus Mode", "Focus mode on — mujhe disturb mat karna, short replies.", 20, 70),
        Routine("Meeting Mode", "Meeting mode on — main meeting mein hoon.", 0, 50),
        Routine("Night Mode", "Night mode on — dheere, shaant awaaz mein baat karo.", 15, 20),
        Routine("Daily Summary", "Aaj mene kya kya kiya uska short summary do aur kal ke liye suggestions.", null, null),
        Routine("Clean Up", "Mere Downloads folder ko organize karne ke tips do.", null, null),
    )

    data class Engine(val key: String, val label: String, val urlTemplate: String)

    val engines = listOf(
        Engine("google", "Google", "https://www.google.com/search?q={q}"),
        Engine("youtube", "YouTube", "https://www.youtube.com/results?search_query={q}"),
        Engine("bing", "Bing", "https://www.bing.com/search?q={q}"),
        Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q={q}"),
        Engine("wiki", "Wikipedia", "https://en.wikipedia.org/w/index.php?search={q}"),
    )

    data class QuickSite(val name: String, val url: String)

    val quickSites = listOf(
        QuickSite("YouTube", "https://youtube.com"),
        QuickSite("Gmail", "https://mail.google.com"),
        QuickSite("Drive", "https://drive.google.com"),
        QuickSite("Maps", "https://maps.google.com"),
        QuickSite("News", "https://news.google.com"),
        QuickSite("GitHub", "https://github.com"),
        QuickSite("WhatsApp Web", "https://web.whatsapp.com"),
        QuickSite("Translate", "https://translate.google.com"),
    )

    /** MYRA VoicePage listen modes + hints (wake word says "RANI" for RANI). */
    val listenModes = listOf("wake", "tap", "continuous", "stop")
    fun listenHint(mode: String): String = when (mode) {
        "wake" -> "Wake Word: RANI sirf tab bolegi jab aap 'RANI' bolo (phir 30 sec tak baat-cheet chalti rahegi)."
        "tap" -> "Tap to Talk: mic band rehta hai — orb ya mic button dabao, bolo; jawab ke baad mic khud band."
        "continuous" -> "Continuous: mic hamesha on, RANI har baat ka jawab degi."
        else -> "Stop Listening: mic band. Koi mode dabao ya mic button dabao to dobara chalu."
    }
    fun listenLabel(mode: String): String = when (mode) {
        "wake" -> "Wake Word"
        "tap" -> "Tap to Talk"
        "continuous" -> "Continuous"
        else -> "Stop Listening"
    }
}
