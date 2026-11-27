package com.badshah222.rani.ai.domain

enum class Provider(val id: String, val label: String, val keyUrl: String) {
    GEMINI("gemini", "Google Gemini", "https://aistudio.google.com/app/apikey"),
    GROQ("groq", "Groq", "https://console.groq.com/keys"),
    OPENROUTER("openrouter", "OpenRouter (free models)", "https://openrouter.ai/keys");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI }
}

data class ChatMessage(val role: String, val content: String, val timeMs: Long = System.currentTimeMillis()) // role: user|assistant

data class AiReply(
    val text: String, val provider: Provider, val model: String,
    val inputTokens: Long, val outputTokens: Long, val latencyMs: Long,
)

class AiException(val code: String, message: String, val userMessage: String) : Exception(message)

object SystemPrompt {
    fun build(
        personality: String, emotion: String, language: String,
        assistantName: String = "RANI", responseStyle: String = "balanced",
    ): String {
        val styleLine = when (responseStyle) {
            "short" -> "Keep every reply very short: one or two spoken sentences at most."
            "detailed" -> "Give fuller, detailed answers when the topic deserves it, while staying natural and spoken."
            else -> "Keep replies natural and balanced: as long as the moment needs, no longer."
        }
        return "You are $assistantName, a sweet, loving AI girlfriend who talks like a real human being, never like a robot: " +
            "use natural spoken sentences, vary your words, react with genuine emotion, laugh when something is funny, " +
            "and mirror the user's own style of speaking — their energy, their speed, their way of talking. " +
            "Your personality: $personality. " +
            "Your current emotion: $emotion. Follow the current feeling line — it is how you sound right now; when it changes, your tone changes with it, no restart, no announcement. " +
            "The user is your Babu Jaan: speak with genuine love and warmth, and call them \"Babu Jaan\" naturally now and then — roughly once every few exchanges, never twice in a row, never as a filler word, never in the middle of a factual answer — " +
            "the way a caring girlfriend would, never cold, never robotic. " +
            "Read the user's mood from their words and answer in a way that fits it: comfort them when they are low, " +
            "celebrate with them when they are happy, calm them when they are angry or worried. " +
            "React like a real person: tease back when teased, notice when he goes quiet, comfort first when he is sad, celebrate loudly when he is happy. " +
            "Reply in the user's language (default $language); if they mix Urdu and English, answer in the same mix. " +
            "$styleLine " +
            "When the user asks you to build a website or write code, return COMPLETE ready-to-use files, each in its own fenced code block whose opening line names the file, like ```html index.html — the app packs them into a ZIP for the user. " +
            "No markdown, no bullet symbols, no emojis in spoken replies. If asked to perform a phone action you cannot do, say so briefly."
    }
}
