package com.badshah222.rani.ai.data.ai

import java.net.URLEncoder

/**
 * Pollinations.ai — free image generation that needs NO key.
 * Used as the fallback when the user's Gemini key has no image model.
 * RANI chats only on the user's own keys.
 */
object Pollinations {
    fun imageUrl(prompt: String): String {
        val enc = URLEncoder.encode(prompt.take(300), "UTF-8").replace("+", "%20")
        val seed = (System.currentTimeMillis() % 100000).toString()
        return "https://image.pollinations.ai/prompt/$enc?width=1024&height=1024&nologo=true&seed=$seed"
    }
}
