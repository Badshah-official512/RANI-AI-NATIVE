package com.badshah222.rani.ai.domain

/**
 * MYRA's Apps & Tools, Automation routines and Web Search — ported 1:1 from
 * myra_pages.py (AppsToolsPage.AI_TOOLS, AutomationPage routines,
 * WebSearchPage.ENGINES / SITES). Prompts are MYRA's exact words; RANI only
 * swaps the name ("MYRA" -> "RANI") where the prompt addresses the assistant.
 *
 * v2.0.0 §22.4 — the Skills catalog: 65 real skills across 12 categories.
 * Every skill is REAL, in exactly one of three kinds — never fabricated:
 *  (a) CHAT — Gemini answers through the existing c.ai.chat prompt-template
 *      pattern (name + ask-prompt + template with {x}). It is never
 *      presented as live data, a live price, a live score or a verified
 *      fact unless a live source backs it.
 *  (b) NATIVE — a real on-device action: the existing native handlers
 *      (image / video / PDF / file), a Live phone tool (§21.8) or the
 *      existing Automation routine mechanism (volume/brightness + spoken
 *      confirmation). A phone-control skill MUST call the action it names.
 *  (c) COMING_SOON — shown with a "Coming soon" badge, greyed, never
 *      fakes a result. Used ONLY where no real mechanism exists yet
 *      (live weather, live currency rates — chat must NOT invent them).
 *
 * English-only UI (§17/§22.4): every name, description, ask-prompt,
 * button, placeholder and result string the user can SEE is English.
 * Gemini prompt TEMPLATES are model prompts, not UI text, so the
 * templates of the original 8 tools stay exactly as they were.
 */
object RaniTools {
    data class AiTool(val name: String, val ask: String?, val template: String)

    /** (name, ask-prompt shown to the user, Gemini prompt template with {x}). */
    val aiTools = listOf(
        AiTool("Generate Image", "Describe the image you want:", "Generate an image: {x}"),
        AiTool("Generate Video", "What video do you want? Describe the scene — a video can take a few minutes.", "Generate a video: {x}"),
        AiTool("Summarize Text", "Paste the text you want summarized:", "Summarize this text briefly:\n{x}"),
        AiTool("Translate", "What should I translate? Include the language too, like 'Hindi to English: ...'", "Translate: {x}"),
        AiTool("Write Code", "What code do you need? Include the language and what it should do.", "Write code for: {x}"),
        AiTool("Create PDF", "What should the PDF say? Give me a topic.", "Create a PDF document about: {x}"),
        AiTool("Analyze File", null, "Analyze this file and give key insights: {x}"),
        AiTool("Plan My Day", "Write today's tasks and priorities:", "Plan my day with these tasks, time-blocked: {x}"),
    )

    /** v2.0.0 §22.4 — one skill in the catalog. */
    enum class SkillKind { CHAT, NATIVE, COMING_SOON }

    /**
     * @param name English skill name shown on the card.
     * @param category one of [skillCategories].
     * @param description one-line English description shown on the card.
     * @param kind CHAT (prompt template), NATIVE (real on-device action)
     *   or COMING_SOON (honestly badged, never fakes a result).
     * @param ask English ask-prompt shown as the field label (or, for
     *   no-input skills, as the hint above the Run button). For
     *   COMING_SOON skills this is the honest "why not yet" message.
     * @param template Gemini prompt template with {x} (CHAT skills), the
     *   routine prompt (routine skills), or "" for pure native actions.
     * @param action native action id for NATIVE skills (see
     *   AiToolsViewModel.runSkill); null for plain CHAT skills.
     * @param needsInput false when the skill runs with no input (Take a
     *   Photo, Read My Screen Aloud, the routines, Daily Summary).
     */
    data class Skill(
        val name: String,
        val category: String,
        val description: String,
        val kind: SkillKind,
        val ask: String,
        val template: String,
        val action: String? = null,
        val needsInput: Boolean = true,
    )

    val skillCategories = listOf(
        "Phone Control", "Calls & Messages", "Knowledge & Answers",
        "Learning & Study", "Creative", "Productivity", "Health",
        "Finance", "Travel", "Entertainment", "Languages", "Daily Life",
    )

    /** The 7 original tools that the catalog table lists, built from the
     *  SAME AiTool entries — same ask-prompts, same templates, same
     *  native handlers — so they cannot drift from the originals. (The
     *  8th original, Analyze File, is NOT a skill card: it stays as its
     *  own file-picker section below the catalog, so the runtime total
     *  stays exactly the 65 skills below.) */
    private fun fromTool(
        name: String, category: String, description: String,
        kind: SkillKind, action: String? = null,
    ): Skill {
        val t = aiTools.first { it.name == name }
        return Skill(name, category, description, kind, t.ask ?: "Type here…", t.template, action)
    }

    /** A skill that runs an existing Automation routine (volume /
     *  brightness + spoken confirmation — the real routine mechanism). */
    private fun fromRoutine(skillName: String, routineName: String, category: String, description: String): Skill {
        val r = routines.first { it.name == routineName }
        return Skill(skillName, category, description, SkillKind.NATIVE, "Tap Run to start this routine.", r.prompt, action = "routine:$routineName", needsInput = false)
    }

    data class Routine(val name: String, val prompt: String, val volume: Int?, val brightness: Int?)

    /** (name, prompt shown + sent to RANI, local volume 0-100, local
     *  brightness 0-100). v2.0.0 §22.4: the prompts are shown in the
     *  Automation screen AND used as the routine skills' prompts, so
     *  they are user-facing text and are English. Declared BEFORE
     *  [skills] — object properties initialise in order, and fromRoutine
     *  reads this list. */
    val routines = listOf(
        Routine("Good Morning", "Tell me today's date, the weather and my pending tasks, with a warm good morning.", null, null),
        Routine("Focus Mode", "Focus mode on — do not disturb me, short replies only.", 20, 70),
        Routine("Meeting Mode", "Meeting mode on — I am in a meeting.", 0, 50),
        Routine("Night Mode", "Night mode on — speak slowly and quietly.", 15, 20),
        Routine("Daily Summary", "Give me a short summary of what I did today and suggestions for tomorrow.", null, null),
        Routine("Clean Up", "Give me tips to organise my Downloads folder.", null, null),
    )

    /** 65 skills — 6 + 5 + 5 + 5 + 6 + 6 + 5 + 5 + 5 + 5 + 6 + 6. The
     *  screen shows skills.size as the total, computed at runtime, never
     *  hardcoded — adding or removing a skill updates the count itself. */
    val skills = listOf(
        // ---- Phone Control (6, NATIVE — Live tools / accessibility) ----
        Skill("Open Any App", "Phone Control", "Open any app on your phone by name.", SkillKind.NATIVE, "Which app should I open?", "", action = "open_app"),
        Skill("Call a Contact", "Phone Control", "Call someone from your contacts by name.", SkillKind.NATIVE, "Who should I call?", "", action = "call_contact"),
        Skill("Set an Alarm", "Phone Control", "Set an alarm for any time.", SkillKind.NATIVE, "What time should the alarm ring? For example: 6 am or 6:30.", "", action = "set_alarm"),
        Skill("Set a Timer", "Phone Control", "Start a countdown timer.", SkillKind.NATIVE, "How long should the timer run? For example: 10 minutes.", "", action = "set_timer"),
        Skill("Open Wi-Fi / Bluetooth Settings", "Phone Control", "Open the Wi-Fi or Bluetooth settings page.", SkillKind.NATIVE, "Which settings should I open — Wi-Fi or Bluetooth?", "", action = "open_settings"),
        Skill("Take a Photo", "Phone Control", "Open the camera and take a photo.", SkillKind.NATIVE, "Tap Run and I will open the camera and take a photo.", "", action = "take_photo", needsInput = false),
        // ---- Calls & Messages (5, NATIVE) ----
        Skill("Draft a WhatsApp Message", "Calls & Messages", "Open a WhatsApp chat with your message drafted.", SkillKind.NATIVE, "Who should I message, and what should I say? Write it like: Ali — see you at 5.", "", action = "send_whatsapp"),
        Skill("Draft an SMS", "Calls & Messages", "Open a text message draft, ready to send.", SkillKind.NATIVE, "Who should I text, and what should I say? Write it like: Ali — see you at 5.", "", action = "send_sms"),
        Skill("Read My Screen Aloud", "Calls & Messages", "Read the text on your screen out loud.", SkillKind.NATIVE, "Tap Run and I will read what is on your screen.", "", action = "read_screen", needsInput = false),
        Skill("Type & Send on Screen", "Calls & Messages", "Type text and tap Send on the current screen.", SkillKind.NATIVE, "What should I type and send on the screen?", "", action = "type_text_send"),
        Skill("Search YouTube", "Calls & Messages", "Search YouTube and open the results.", SkillKind.NATIVE, "What should I search for on YouTube?", "", action = "search_youtube"),
        // ---- Knowledge & Answers (5) ----
        Skill("Ask Anything", "Knowledge & Answers", "Ask any question and get a clear answer.", SkillKind.CHAT, "What do you want to ask?", "Answer this question clearly and helpfully: {x}"),
        Skill("Explain It Simply", "Knowledge & Answers", "Anything explained in simple words.", SkillKind.CHAT, "What should I explain in simple words?", "Explain this in simple words anyone can understand: {x}"),
        Skill("Search the Web", "Knowledge & Answers", "Search the web in the browser.", SkillKind.NATIVE, "What should I search for on the web?", "", action = "search_web"),
        fromTool("Summarize Text", "Knowledge & Answers", "Turn long text into a short summary.", SkillKind.CHAT),
        fromTool("Translate", "Knowledge & Answers", "Translate text between languages.", SkillKind.CHAT),
        // ---- Learning & Study (5, CHAT) ----
        Skill("Explain a Topic", "Learning & Study", "A topic explained clearly, step by step.", SkillKind.CHAT, "Which topic should I explain?", "Explain this topic clearly, step by step, with examples: {x}"),
        Skill("Practice Quiz", "Learning & Study", "Quiz questions, one at a time.", SkillKind.CHAT, "Which subject should I quiz you on?", "Ask me quiz questions about {x}, one at a time. Wait for my answer after each question, then tell me if I was right before asking the next one."),
        Skill("Exam Revision Plan", "Learning & Study", "A day-by-day plan for your exam.", SkillKind.CHAT, "Which exam or subject is the revision plan for?", "Make a revision plan for {x}: what to study each day, with short practice tasks."),
        Skill("Solve a Maths Problem Step by Step", "Learning & Study", "Full working shown, never just the answer.", SkillKind.CHAT, "Write the maths problem here:", "Solve this maths problem step by step, showing all the working — never just the final answer: {x}"),
        Skill("Learn a New Word", "Learning & Study", "Meaning, pronunciation and an example.", SkillKind.CHAT, "Which word should I teach you?", "Teach me the word {x}: what it means, how to say it, and an example sentence using it."),
        // ---- Creative (6) ----
        fromTool("Generate Image", "Creative", "Create an image from a description.", SkillKind.NATIVE, action = "generate_image"),
        fromTool("Generate Video", "Creative", "Create a short video from a scene.", SkillKind.NATIVE, action = "generate_video"),
        Skill("Write a Story", "Creative", "A short story about anything.", SkillKind.CHAT, "What should the story be about?", "Write a short story about: {x}"),
        Skill("Write a Poem", "Creative", "A poem on any topic.", SkillKind.CHAT, "What should the poem be about?", "Write a poem about: {x}"),
        fromTool("Write Code", "Creative", "Code in any language, with an explanation.", SkillKind.CHAT),
        Skill("Write a Caption", "Creative", "A short, catchy caption for a post.", SkillKind.CHAT, "What is the photo or post about?", "Write a short, catchy caption for: {x}"),
        // ---- Productivity (6) ----
        fromTool("Plan My Day", "Productivity", "Your day, time-blocked around your tasks.", SkillKind.CHAT),
        Skill("Make a To-Do List", "Productivity", "A to-do list, saved on your phone.", SkillKind.CHAT, "What do you need to get done?", "Turn this into a short to-do list, one task per line: {x}", action = "save_todo_list"),
        Skill("Set a Reminder", "Productivity", "Save a reminder on your phone.", SkillKind.NATIVE, "What should I remind you about, and when? For example: Call Ammi at 5.", "", action = "set_reminder"),
        fromTool("Create PDF", "Productivity", "Make a real PDF file in Downloads.", SkillKind.NATIVE, action = "create_pdf"),
        Skill("Meeting Notes", "Productivity", "Clean notes with decisions and next steps.", SkillKind.CHAT, "Paste the meeting notes or points here:", "Turn these notes into clean, short meeting notes with decisions and next steps: {x}"),
        fromRoutine("Daily Summary", "Daily Summary", "Productivity", "A short summary of your day."),
        // ---- Health (5, CHAT — general information only, never a diagnosis) ----
        Skill("Workout Plan", "Health", "A simple workout plan. General information only.", SkillKind.CHAT, "What kind of workout do you want? For example: at home, 20 minutes.", "Make a simple workout plan for: {x}. Keep it safe for a beginner. General information only — not medical advice."),
        Skill("Healthy Meal Idea", "Health", "A healthy meal idea. General information only.", SkillKind.CHAT, "What ingredients do you have, or what do you feel like eating?", "Suggest a healthy meal idea for: {x}. General information only — not medical advice."),
        Skill("Sleep Tips", "Health", "Practical tips for better sleep. General information only.", SkillKind.CHAT, "What is keeping you awake, or how do you sleep now?", "Give me practical sleep tips for: {x}. General information only — not medical advice."),
        Skill("Mood Check-In", "Health", "Talk about how you feel. General information only.", SkillKind.CHAT, "How are you feeling right now?", "I am feeling: {x}. Talk with me about it kindly, suggest small things that may help, and never diagnose or prescribe anything."),
        Skill("Water Reminder", "Health", "Reminds you to drink water.", SkillKind.NATIVE, "When should I remind you to drink water? For example: every hour, or at 3 pm.", "", action = "water_reminder"),
        // ---- Finance (5, CHAT — estimates only, never live data) ----
        Skill("Monthly Budget Planner", "Finance", "Plan a month's budget. Estimates only.", SkillKind.CHAT, "What is your monthly income and main expenses?", "Help me plan a monthly budget from this. Give estimates only, never live prices: {x}"),
        Skill("Savings Plan", "Finance", "A simple plan to reach a savings goal. Estimates only.", SkillKind.CHAT, "What are you saving for, and how much can you set aside?", "Make a simple savings plan for: {x}. Estimates only, never live prices."),
        Skill("Expense Ideas", "Finance", "Ideas to cut everyday spending. Estimates only.", SkillKind.CHAT, "What do you spend money on?", "Suggest ways to cut everyday spending for: {x}. Estimates only, never live prices."),
        Skill("Explain a Money Term", "Finance", "A money term explained in simple words.", SkillKind.CHAT, "Which money term should I explain?", "Explain this money term in simple words: {x}"),
        Skill("Currency Converter", "Finance", "Convert between currencies. Coming soon.", SkillKind.COMING_SOON, "Currency conversion needs live rates, which are not connected yet — so I will not guess a rate.", ""),
        // ---- Travel (5, CHAT) ----
        Skill("Plan a Trip", "Travel", "A trip plan from general knowledge.", SkillKind.CHAT, "Where do you want to go, and for how long?", "Plan a trip to {x}: places to see, how many days, and rough costs from general knowledge."),
        Skill("Packing List", "Travel", "Everything to pack for a trip.", SkillKind.CHAT, "Where are you going, and for how long?", "Make a packing list for: {x}."),
        Skill("Translate for Travel", "Travel", "Say it in the local language, with pronunciation.", SkillKind.CHAT, "What do you need to say while travelling?", "Translate this for travel, with the pronunciation: {x}"),
        Skill("Best Time to Visit", "Travel", "The best season to go, from general knowledge.", SkillKind.CHAT, "Which place do you want to visit?", "From general knowledge, when is the best time to visit {x}, and why?"),
        Skill("Travel Checklist", "Travel", "Documents, bookings and packing, checked off.", SkillKind.CHAT, "Where are you travelling to?", "Make a travel checklist for: {x} — documents, bookings, packing and health."),
        // ---- Entertainment (5) ----
        Skill("Play Music", "Entertainment", "Play a song in the music app.", SkillKind.NATIVE, "Which song or artist should I play?", "", action = "play_music"),
        Skill("Tell a Joke", "Entertainment", "A short, clean joke.", SkillKind.CHAT, "What kind of joke do you want?", "Tell me a short, clean joke about: {x}"),
        Skill("Tell a Bedtime Story", "Entertainment", "A short, gentle story for the night.", SkillKind.CHAT, "What should the bedtime story be about?", "Tell a short, gentle bedtime story about: {x}"),
        Skill("Movie Recommendation", "Entertainment", "A movie pick from general knowledge.", SkillKind.CHAT, "What kind of movie do you like?", "Recommend a movie for someone who likes {x}, from your general knowledge."),
        Skill("Play a Quiz Game", "Entertainment", "A quiz game with score, one question at a time.", SkillKind.CHAT, "Which topic should the quiz game be about?", "Play a quiz game with me about {x}: ask one question at a time and keep score."),
        // ---- Languages (6, CHAT) ----
        Skill("Translate a Sentence", "Languages", "Translate any sentence.", SkillKind.CHAT, "Write the sentence and the language:", "Translate this sentence: {x}"),
        Skill("Learn Urdu Phrases", "Languages", "Say it in Urdu, with pronunciation.", SkillKind.CHAT, "What do you want to say in Urdu?", "Teach me how to say this in Urdu, with the pronunciation: {x}"),
        Skill("Learn English Phrases", "Languages", "Say it in English, with an example.", SkillKind.CHAT, "What do you want to say in English?", "Teach me how to say this in English, with a simple example: {x}"),
        Skill("Learn Pashto Phrases", "Languages", "Say it in Pashto, with pronunciation.", SkillKind.CHAT, "What do you want to say in Pashto?", "Teach me how to say this in Pashto, with the pronunciation: {x}"),
        Skill("Practice Conversation", "Languages", "Practise talking, one question at a time.", SkillKind.CHAT, "Which language should we practise?", "Practise a simple conversation with me in {x}: ask me one question at a time and correct me gently."),
        Skill("Check My Grammar", "Languages", "Check a sentence and explain the fix.", SkillKind.CHAT, "Paste the sentence to check:", "Check the grammar of this sentence and explain the fix in simple words: {x}"),
        // ---- Daily Life (6) ----
        fromRoutine("Good Morning Routine", "Good Morning", "Daily Life", "A warm start: date, weather and tasks."),
        fromRoutine("Night Mode", "Night Mode", "Daily Life", "Quiet and dim for the night."),
        fromRoutine("Focus Mode", "Focus Mode", "Daily Life", "Low volume, no interruptions."),
        fromRoutine("Meeting Mode", "Meeting Mode", "Daily Life", "Silent while you are in a meeting."),
        fromRoutine("Clean Up", "Clean Up", "Daily Life", "Tips to organise your Downloads folder."),
        Skill("Weather Today", "Daily Life", "Today's weather. Coming soon.", SkillKind.COMING_SOON, "Today's weather needs a live weather source, which is not connected yet — so I will not invent it.", ""),
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

    /** MYRA VoicePage listen modes + hints (wake word says "RANI" for RANI).
     *  v2.0.0 §22.4: the hints are shown in Settings / Voice Mode, so
     *  they are user-facing text and are English. */
    val listenModes = listOf("wake", "tap", "continuous", "stop")
    fun listenHint(mode: String): String = when (mode) {
        "wake" -> "Wake Word: RANI only speaks when you say 'RANI' (then the conversation keeps going for 30 seconds)."
        "tap" -> "Tap to Talk: the mic stays off — tap the orb or mic button, speak; after the reply the mic turns itself off."
        "continuous" -> "Continuous: the mic is always on, RANI answers everything you say."
        else -> "Stop Listening: the mic is off. Tap a mode or the mic button to listen again."
    }
    fun listenLabel(mode: String): String = when (mode) {
        "wake" -> "Wake Word"
        "tap" -> "Tap to Talk"
        "continuous" -> "Continuous"
        else -> "Stop Listening"
    }
}
