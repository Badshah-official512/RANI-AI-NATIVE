package com.badshah222.rani.ai.data.local

import android.content.Context
import com.badshah222.rani.ai.domain.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private on-device chat history (works offline / when cloud sync is unavailable). One JSON file per signed-in user. */
class ChatStore(private val context: Context) {
    private fun file(uid: String) = File(context.filesDir, "chat_${uid.filter { it.isLetterOrDigit() }}.json")

    fun load(uid: String): List<ChatMessage> = runCatching {
        val f = file(uid); if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText()); (0 until a.length()).map { a.getJSONObject(it).let { o -> ChatMessage(o.getString("r"), o.getString("c"), o.optLong("t")) } }
    }.getOrDefault(emptyList())

    fun save(uid: String, msgs: List<ChatMessage>) {
        val a = JSONArray(); msgs.takeLast(300).forEach { a.put(JSONObject().put("r", it.role).put("c", it.content).put("t", it.timeMs)) }
        file(uid).writeText(a.toString())
    }
    fun clear(uid: String) { file(uid).delete() }
}
