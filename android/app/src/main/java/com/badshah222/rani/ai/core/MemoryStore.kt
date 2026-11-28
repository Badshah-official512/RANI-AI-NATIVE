package com.badshah222.rani.ai.core

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.badshah222.rani.ai.data.supabase.SupabaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.9.0 §21.2 + §21.7 — ONE long-term memory system.
 *
 * On-device source of truth: ONE encrypted JSON file
 * (MasterKey/AES-GCM, same pattern as SecureStore — EncryptedFile,
 * no new dependency): last 10 full transcripts, last 50 summaries
 * (<= 500 chars, on-device extractive — no network call), and a facts
 * map. Secret Chat / Temporary Chat are NEVER captured — the collector
 * simply never subscribes to those screens.
 *
 * Facts use a FIXED category set with high/low confidence; inferred
 * (low) facts are phrased "you think…" in the recall block and RANI
 * confirms them gently instead of asserting them. Passwords, keys,
 * OTPs and banking details are NEVER extracted. Sync to Supabase
 * `rani_memories` is best-effort, owner-only rows, gated by the
 * "Sync memories to my account" switch (default ON when signed in).
 */
data class MemoryFact(
    val key: String,
    val value: String,
    val confidence: String, // "high" | "low"
    val source: String, // "said" | "inferred"
    val updatedAt: Long,
    val superseded: Boolean = false,
)

data class Turn(val who: String, val text: String, val atMs: Long = System.currentTimeMillis())

class MemoryStore(
    context: Context,
    private val store: SecureStore,
    private val cloud: SupabaseApi,
    private val uidProvider: () -> String?,
) {
    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file: File = File(File(appCtx.filesDir, "memory"), "rani_memory.json").apply {
        parentFile?.mkdirs()
    }
    private val lock = Any()

    /** Fixed category set (§21.7) — no free-form sprawl. */
    val categories = listOf(
        "name", "nickname", "likes", "dislikes", "favourite_food", "favourite_drink",
        "favourite_song", "favourite_film", "favourite_show", "birthday", "anniversary",
        "mood_baseline", "plans", "family", "friends", "routine", "study", "health", "pet_peeves",
    )

    // ---- in-memory session buffer (current call / chat) ----
    private val sessionTurns = ArrayList<Turn>()
    private var sessionSource = ""
    private var sessionStartedAt = 0L

    // ---- persisted state (cached in memory, written through) ----
    private var state: JSONObject = loadState()

    private fun encryptedFile(): EncryptedFile {
        val mk = MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedFile.Builder(
            appCtx, file, mk,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    private fun loadState(): JSONObject {
        return try {
            if (!file.exists() || file.length() == 0L) return freshState()
            val bytes = encryptedFile().openFileInput().use { it.readBytes() }
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.d("RaniMemory", "load failed, starting fresh: ${e.message}")
            freshState()
        }
    }

    private fun freshState(): JSONObject = JSONObject()
        .put("conversations", JSONArray())
        .put("summaries", JSONArray())
        .put("facts", JSONObject())
        .put("pending_facts", JSONArray())
        .put("mood_counts", JSONObject())
        // v3.0.0 §23.7: visual memory notes (text descriptions only — never
        // frames) + visual rows still waiting for migration 017 to sync.
        .put("visual_notes", JSONArray())
        .put("visual_pending", JSONArray())
        .put("updated_at", System.currentTimeMillis())

    private fun persistLocked() {
        try {
            state.put("updated_at", System.currentTimeMillis())
            encryptedFile().openFileOutput().use { it.write(state.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            Log.d("RaniMemory", "persist failed: ${e.message}")
        }
    }

    fun isOn(): Boolean = store.memoryOn

    // ---- capture ----
    /** Records one transcript turn. A growing partial transcript (same
     *  speaker, text only grew) replaces the last turn in place. */
    fun recordTurn(source: String, who: String, text: String) {
        if (!isOn() || text.isBlank()) return
        synchronized(lock) {
            if (sessionSource != source) {
                if (sessionTurns.isNotEmpty()) finalizeLocked(sessionSource)
                sessionSource = source
                sessionStartedAt = System.currentTimeMillis()
            }
            val last = sessionTurns.lastOrNull()
            if (last != null && last.who == who && text.startsWith(last.text)) {
                sessionTurns[sessionTurns.lastIndex] = Turn(who, text)
            } else if (last != null && last.who == who && last.text.startsWith(text)) {
                // Shorter repeat of the same turn — ignore.
            } else {
                sessionTurns.add(Turn(who, text))
                if (sessionTurns.size > 200) sessionTurns.removeAt(0)
            }
        }
    }

    /** Finalizes the current session: transcript + summary + facts are
     *  written to the store (background dispatcher — never blocks the call
     *  UI; a save failure is logged, never shown to the user). The session
     *  is SNAPSHOT synchronously here, before the coroutine launches —
     *  otherwise a new call started in between would sweep its turns
     *  into this session's transcript/summary/facts (F7 fix). */
    fun finalizeSession(source: String) {
        if (!isOn()) return
        retryPendingVisual()
        val turns: List<Turn>
        val startedAt: Long
        synchronized(lock) {
            if (sessionTurns.isEmpty()) return
            turns = sessionTurns.toList()
            startedAt = sessionStartedAt
            sessionTurns.clear()
            sessionStartedAt = 0L
            sessionSource = ""
        }
        scope.launch {
            synchronized(lock) {
                finalizeSnapshot(turns, startedAt, source)
            }
        }
    }

    private fun finalizeLocked(source: String) {
        if (sessionTurns.isEmpty()) return
        val turns = sessionTurns.toList()
        sessionTurns.clear()
        val startedAt = sessionStartedAt
        sessionStartedAt = 0L
        sessionSource = ""
        finalizeSnapshot(turns, startedAt, source)
    }

    /** Writes one snapshotted session (turns already detached from the
     *  live buffer by the caller). Caller must hold [lock]. */
    private fun finalizeSnapshot(turns: List<Turn>, startedAt: Long, source: String) {
        if (turns.isEmpty()) return
        try {
            val summary = buildSummary(turns)
            val facts = extractFacts(turns)
            val askFirst = store.memoryAskFirst
            // Conversations: last 10 full transcripts.
            val convs = state.optJSONArray("conversations") ?: JSONArray()
            val convoObj = JSONObject()
                .put("started_at", startedAt)
                .put("source", source)
                .put("turns", JSONArray().apply {
                    for (t in turns) put(JSONObject().put("who", t.who).put("text", t.text).put("at", t.atMs))
                })
            convs.put(convoObj)
            while (convs.length() > 10) convs.remove(0)
            state.put("conversations", convs)
            // Summaries: last 100 (v3.0.0 §23.7 — on-device only; sync
            // sends the latest 20).
            val sums = state.optJSONArray("summaries") ?: JSONArray()
            sums.put(JSONObject().put("at", System.currentTimeMillis()).put("source", source).put("text", summary))
            while (sums.length() > 100) sums.remove(0)
            state.put("summaries", sums)
            // Facts (or pending when ask-first is on).
            val factsObj = state.optJSONObject("facts") ?: JSONObject()
            val pending = state.optJSONArray("pending_facts") ?: JSONArray()
            for (f in facts) {
                val entry = JSONObject()
                    .put("value", f.value).put("confidence", f.confidence)
                    .put("source", f.source).put("updated_at", f.updatedAt)
                if (askFirst && f.confidence == "low") {
                    pending.put(JSONObject().put("key", f.key).put("value", f.value)
                        .put("confidence", f.confidence).put("source", f.source)
                        .put("updated_at", f.updatedAt))
                } else {
                    // v3.0.0 §23.7 contradiction handling: when a new fact
                    // contradicts an old one, the new one wins and the old
                    // is kept, marked superseded (never deleted — "Forget
                    // everything" still removes both).
                    val old = factsObj.optJSONObject(f.key)
                    if (old != null && old.optString("value") != f.value) {
                        old.put("superseded", true)
                        factsObj.put(f.key + "_old", old)
                    }
                    factsObj.put(f.key, entry) // newest wins under the same key
                }
            }
            state.put("facts", factsObj)
            state.put("pending_facts", pending)
            // Mood baseline: a mood stated in 2+ different sessions becomes a baseline.
            // F11(a): the baseline REFRESHES when a different mood reaches
            // count >= 2 — a once-set baseline must not freeze forever.
            val mood = moodWord(turns)
            if (mood != null) {
                val counts = state.optJSONObject("mood_counts") ?: JSONObject()
                val n = counts.optInt(mood, 0) + 1
                counts.put(mood, n)
                state.put("mood_counts", counts)
                if (n >= 2) {
                    val newBaseline = "often feels $mood"
                    val existing = factsObj.optJSONObject("mood_baseline")?.optString("value").orEmpty()
                    if (existing != newBaseline) {
                        factsObj.put("mood_baseline", JSONObject().put("value", newBaseline)
                            .put("confidence", "low").put("source", "inferred")
                            .put("updated_at", System.currentTimeMillis()))
                        state.put("facts", factsObj)
                    }
                }
            }
            persistLocked()
            // Best-effort Supabase sync (own owner-only rows).
            syncAsync(source, turns, summary, facts)
        } catch (e: Exception) {
            Log.d("RaniMemory", "finalize failed: ${e.message}")
        }
    }

    private fun syncAsync(source: String, turns: List<Turn>, summary: String, facts: List<MemoryFact>) {
        val uid = uidProvider() ?: return
        if (!store.memorySync) return
        scope.launch {
            val title = turns.firstOrNull { it.who == "user" }?.text?.take(60).orEmpty()
            val transcriptJson = JSONArray().apply {
                for (t in turns) put(JSONObject().put("who", t.who).put("text", t.text))
            }.toString()
            runCatching {
                cloud.syncMemory(uid, "conversation", title.ifBlank { "Chat with RANI" }, transcriptJson, null, source)
            }
            runCatching {
                cloud.syncMemory(uid, "summary", title.ifBlank { "Summary" }, summary, null, source)
            }
            for (f in facts) {
                runCatching {
                    val factsJson = JSONObject().put(f.key, f.value)
                        .put("confidence", f.confidence).put("source", f.source)
                    cloud.syncMemory(uid, "fact", f.key, f.value, factsJson, source)
                }
            }
        }
    }

    // ---- recall ----
    /**
     * ONE recall block, max 1,200 chars, most recent first: max 15 facts
     * (newest/highest-confidence first) + last summary + closing line.
     * Empty when there is nothing to remember — the MEMORY section is then
     * omitted entirely. Rebuilt per session, never mid-call.
     */
    // ---- recall ----
    /**
     * v3.0.0 §23.7 — ONE recall block, max 2,000 chars, most recent first:
     * max 25 facts (newest/highest-confidence first, superseded excluded)
     * + last 3 conversation summaries + up to 3 visual memory notes +
     * up to 3 proactive items + special days + favourites. Empty when
     * there is nothing to remember — the MEMORY section is then omitted
     * entirely. Rebuilt per session, never mid-call. ONE block for both
     * Live (&memory=) and chat (systemPrompt) — same content.
     */
    fun buildRecallBlock(): String {
        if (!isOn()) return ""
        val facts = facts().filter { !it.superseded }
        val sums = synchronized(lock) { state.optJSONArray("summaries") ?: JSONArray() }
        val visuals = visualNotes()
        val proactive = runCatching { JSONArray(store.proactiveItemsJson) }.getOrNull() ?: JSONArray()
        if (facts.isEmpty() && sums.length() == 0 && visuals.isEmpty() && proactive.length() == 0) return ""
        val sb = StringBuilder()
        val cap = 2000
        fun addLine(line: String) {
            if (line.isBlank()) return
            if (sb.length + line.length + 1 > cap) return
            sb.append(line).append("\n")
        }
        val sorted = facts.sortedWith(
            compareByDescending<MemoryFact> { it.confidence == "high" }
                .thenByDescending { it.updatedAt },
        )
        for (f in sorted.take(25)) {
            addLine(if (f.confidence == "low") {
                "- ${f.key}: you think it is \"${f.value}\" (not certain — confirm gently)"
            } else {
                "- ${f.key}: ${f.value}"
            })
        }
        // Last 3 conversation summaries (not just the last one).
        for (i in (sums.length() - 3).coerceAtLeast(0) until sums.length()) {
            val text = sums.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isNotBlank()) addLine("Last time you talked: $text")
        }
        // Visual memory notes (text only — never frames).
        for (v in visuals.take(3)) addLine("You have seen: $v")
        // Proactive items (§23.6).
        for (i in 0 until minOf(3, proactive.length())) {
            addLine("Needs his attention: ${proactive.optString(i)}")
        }
        // Special days + favourites — so she never forgets a date he told her.
        try {
            val days = JSONArray(store.specialDaysJson)
            for (i in 0 until days.length()) {
                val o = days.optJSONObject(i) ?: continue
                addLine("Special day: ${o.optString("name")} — ${o.optInt("day")}/${o.optInt("month")}")
            }
            val favs = JSONObject(store.favouritesJson)
            val keys = favs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                addLine("He likes $k: ${favs.optString(k)}")
            }
        } catch (e: Exception) { }
        val convs = synchronized(lock) { state.optJSONArray("conversations") ?: JSONArray() }
        if (convs.length() > 0) {
            val turns = convs.optJSONObject(convs.length() - 1)?.optJSONArray("turns")
            val closing = turns?.let { if (it.length() > 0) it.optJSONObject(it.length() - 1)?.optString("text").orEmpty() else "" }.orEmpty()
            if (closing.isNotBlank()) addLine("He last said: \"$closing\"")
        }
        val out = sb.toString().trim()
        return if (out.length > cap) out.take(cap) else out
    }

    fun facts(): List<MemoryFact> {
        val obj = synchronized(lock) { state.optJSONObject("facts") ?: JSONObject() }
        val out = ArrayList<MemoryFact>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = obj.optJSONObject(k) ?: continue
            out.add(MemoryFact(k, o.optString("value"), o.optString("confidence", "high"),
                o.optString("source", "said"), o.optLong("updated_at", 0L),
                o.optBoolean("superseded", false)))
        }
        return out.sortedWith(
            compareByDescending<MemoryFact> { it.confidence == "high" }
                .thenByDescending { it.updatedAt },
        )
    }

    // ---- visual memory notes (v3.0.0 §23.7 item 3) ----
    /** Saves a TEXT description of something she saw (Awareness / screen
     *  watch, only from a description she actually gave, or a frame he
     *  explicitly asked her to look at). Never the image/frame itself.
     *  Cap 20 on-device, oldest evicted first. Sync is best-effort kind
     *  "visual" — until migration 017 is run the row fails the old CHECK
     *  and stays in visual_pending for a later retry; never a crash. */
    fun addVisualNote(text: String, source: String = "awareness") {
        if (!isOn() || text.isBlank()) return
        val note = text.trim().take(300)
        synchronized(lock) {
            val arr = state.optJSONArray("visual_notes") ?: JSONArray()
            arr.put(JSONObject().put("at", System.currentTimeMillis()).put("source", source).put("text", note))
            while (arr.length() > 20) arr.remove(0)
            state.put("visual_notes", arr)
            persistLocked()
        }
        scope.launch {
            val uid = uidProvider() ?: return@launch
            if (!store.memorySync) return@launch
            val ok = runCatching {
                cloud.syncMemory(uid, "visual", note.take(60), note, null, source)
            }.getOrDefault(false)
            if (!ok) {
                synchronized(lock) {
                    val pend = state.optJSONArray("visual_pending") ?: JSONArray()
                    pend.put(JSONObject().put("at", System.currentTimeMillis()).put("source", source).put("text", note))
                    state.put("visual_pending", pend)
                    persistLocked()
                }
            }
        }
    }

    /** Retries visual rows that failed to sync before migration 017. */
    fun retryPendingVisual() {
        val uid = uidProvider() ?: return
        if (!store.memorySync) return
        scope.launch {
            val pend = synchronized(lock) { state.optJSONArray("visual_pending") ?: JSONArray() }
            if (pend.length() == 0) return@launch
            val remaining = JSONArray()
            for (i in 0 until pend.length()) {
                val o = pend.optJSONObject(i) ?: continue
                val ok = runCatching {
                    cloud.syncMemory(uid, "visual", o.optString("text").take(60), o.optString("text"), null, o.optString("source", "awareness"))
                }.getOrDefault(false)
                if (!ok) remaining.put(o)
            }
            synchronized(lock) {
                state.put("visual_pending", remaining)
                persistLocked()
            }
        }
    }

    fun visualNotes(): List<String> {
        val arr = synchronized(lock) { state.optJSONArray("visual_notes") ?: JSONArray() }
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out.reversed()
    }

    fun deleteVisualNote(text: String) {
        synchronized(lock) {
            val arr = state.optJSONArray("visual_notes") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("text") != text) kept.put(o)
            }
            state.put("visual_notes", kept)
            persistLocked()
        }
    }

    /** "Yaad rakho: ..." (v3.0.0 §23.1 P1 item 7) — saves one fact from
     *  chat. "X is Y" / "X: Y" splits into key/value; anything else is
     *  saved as a note. High confidence, synced like any other fact. */
    fun saveManualFact(text: String, source: String = "chat") {
        if (!isOn()) return
        val raw = text.trim()
        if (raw.isBlank() || isSecret(raw)) return
        val now = System.currentTimeMillis()
        val m = Regex("(?i)^(.{2,40}?)\s+(?:is|hai|ka|ki|:)\s+(.{2,100})$").find(raw)
        val key = (m?.groupValues?.get(1)?.trim()?.lowercase()?.replace(Regex("[^a-z0-9 ]"), "")?.replace(" ", "_") ?: "note")
            .takeIf { it.isNotBlank() && it.length <= 40 } ?: "note"
        val value = cleanValue(m?.groupValues?.get(2) ?: raw) ?: return
        if (isSecret(value)) return
        synchronized(lock) {
            val factsObj = state.optJSONObject("facts") ?: JSONObject()
            val old = factsObj.optJSONObject(key)
            if (old != null && old.optString("value") != value) {
                old.put("superseded", true)
                factsObj.put(key + "_old", old)
            }
            factsObj.put(key, JSONObject().put("value", value).put("confidence", "high")
                .put("source", "said").put("updated_at", now))
            state.put("facts", factsObj)
            persistLocked()
        }
        val uid = uidProvider()
        if (uid != null && store.memorySync) {
            scope.launch {
                runCatching {
                    cloud.syncMemory(uid, "fact", key, value,
                        JSONObject().put(key, value).put("confidence", "high").put("source", "said"), source)
                }
            }
        }
    }

    /** Recent conversations (title + turns) for the Call Logs screen
     *  (v3.0.0 §23.1 item 25) — reads the stored transcripts, newest first. */
    fun recentConversations(): List<Pair<String, String>> {
        val convs = synchronized(lock) { state.optJSONArray("conversations") ?: JSONArray() }
        val out = ArrayList<Pair<String, String>>()
        for (i in convs.length() - 1 downTo 0) {
            val o = convs.optJSONObject(i) ?: continue
            val turns = o.optJSONArray("turns") ?: JSONArray()
            val sb = StringBuilder()
            for (j in 0 until turns.length()) {
                val t = turns.optJSONObject(j) ?: continue
                sb.append(if (t.optString("who") == "user") "Him: " else "RANI: ")
                    .append(t.optString("text")).append("\n")
            }
            val title = turns.optJSONObject(0)?.optString("text").orEmpty().take(60).ifBlank { "Conversation" }
            out.add(title to sb.toString().trim())
        }
        return out
    }

    fun pendingFacts(): List<MemoryFact> {
        val arr = synchronized(lock) { state.optJSONArray("pending_facts") ?: JSONArray() }
        val out = ArrayList<MemoryFact>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(MemoryFact(o.optString("key"), o.optString("value"),
                o.optString("confidence", "low"), o.optString("source", "inferred"),
                o.optLong("updated_at", 0L)))
        }
        return out
    }

    fun deleteFact(key: String) {
        synchronized(lock) {
            val obj = state.optJSONObject("facts") ?: JSONObject()
            obj.remove(key)
            state.put("facts", obj)
            // F2: also drop it from pending_facts — the Memory screen's
            // pending list deletes through this same call, and without
            // this the item stayed on screen after refresh.
            val pending = state.optJSONArray("pending_facts") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until pending.length()) {
                val o = pending.optJSONObject(i) ?: continue
                if (o.optString("key") != key) kept.put(o)
            }
            state.put("pending_facts", kept)
            persistLocked()
        }
    }

    fun approvePending(key: String) {
        synchronized(lock) {
            val pending = state.optJSONArray("pending_facts") ?: JSONArray()
            val kept = JSONArray()
            var moved: JSONObject? = null
            for (i in 0 until pending.length()) {
                val o = pending.optJSONObject(i) ?: continue
                if (o.optString("key") == key && moved == null) moved = o else kept.put(o)
            }
            state.put("pending_facts", kept)
            moved?.let {
                val factsObj = state.optJSONObject("facts") ?: JSONObject()
                factsObj.put(key, JSONObject().put("value", it.optString("value"))
                    .put("confidence", it.optString("confidence"))
                    .put("source", it.optString("source"))
                    .put("updated_at", System.currentTimeMillis()))
                state.put("facts", factsObj)
            }
            persistLocked()
        }
    }

    /** "Forget everything" — wipes on-device AND the user's Supabase rows. */
    fun forgetEverything() {
        synchronized(lock) {
            sessionTurns.clear()
            state = freshState()
            runCatching { file.delete() }
        }
        val uid = uidProvider()
        if (uid != null) scope.launch { runCatching { cloud.deleteMemories(uid) } }
    }

    // ---- summary (on-device, extractive, no network call) ----
    private fun buildSummary(turns: List<Turn>): String {
        val factWords = listOf(
            "like", "love", "favourite", "favorite", "pasand", "birthday", "tomorrow",
            "going to", "paper", "brother", "sister", "mother", "father", "friend",
            "always", "every day", "never", "feel", "sad", "tired", "happy", "stressed",
        )
        val picked = ArrayList<String>()
        fun add(t: Turn) {
            val s = "${if (t.who == "user") "He" else "RANI"}: ${t.text.trim()}"
            if (s.length > 3 && picked.none { it == s }) picked.add(s)
        }
        // Opening exchange (what the call was about).
        turns.take(2).forEach { add(it) }
        // Turns containing fact keywords.
        for (t in turns) {
            val low = t.text.lowercase()
            if (factWords.any { low.contains(it) }) add(t)
        }
        // Closing exchange.
        turns.takeLast(2).forEach { add(it) }
        // v3.0.0 §23.7 item 4 — structured summary lines: what she
        // promised, what he asked her to remember, and his mood.
        for (t in turns) {
            val low = t.text.lowercase()
            if (t.who == "assistant" && (low.contains("i will ") || low.contains("i promise") || low.contains("i'll "))) {
                picked.add("RANI promised: ${t.text.trim().take(160)}")
            }
            if (t.who == "user" && (low.contains("yaad rakho") || low.contains("remember this") || low.contains("remember that"))) {
                picked.add("He asked RANI to remember: ${t.text.trim().take(160)}")
            }
        }
        moodWord(turns)?.let { picked.add("His mood: $it") }
        val joined = picked.joinToString(" ")
        return if (joined.length > 500) joined.take(497) + "..." else joined
    }

    private fun moodWord(turns: List<Turn>): String? {
        val moods = listOf("sad", "tired", "happy", "stressed", "angry", "excited", "lonely", "bored")
        for (t in turns) {
            if (t.who != "user") continue
            val low = t.text.lowercase()
            for (m in moods) {
                if (low.contains("i feel $m") || low.contains("i am $m") || low.contains("feeling $m")) return m
            }
        }
        return null
    }

    // ---- fact extraction (§21.7, pattern-based, on-device) ----
    private fun extractFacts(turns: List<Turn>): List<MemoryFact> {
        val out = LinkedHashMap<String, MemoryFact>()
        val now = System.currentTimeMillis()
        fun put(key: String, value: String, confidence: String = "high", source: String = "said") {
            val v = cleanValue(value) ?: return
            if (isSecret(v)) return
            out[key] = MemoryFact(key, v, confidence, source, now)
        }
        // v3.0.0 §23.7 item 2 — multi-turn facts: a fact split across two
        // turns ("meri ami ka naam…" / "…Fatima hai") is joined before
        // extraction, so the pair is read as one sentence.
        val userTurns = turns.filter { it.who == "user" }
        val joinedUserTurns = ArrayList<Turn>()
        for (i in userTurns.indices) {
            joinedUserTurns.add(userTurns[i])
            if (i + 1 < userTurns.size) {
                joinedUserTurns.add(Turn("user", userTurns[i].text.trim() + " " + userTurns[i + 1].text.trim()))
            }
        }
        for (t in turns + joinedUserTurns) {
            if (t.who != "user") continue
            val raw = t.text.trim()
            val low = raw.lowercase()
            // Never extract from a turn that mentions secrets.
            if (low.contains("password") || low.contains("otp") || low.contains("bank account")
                || low.contains("card number") || low.contains("api key") || low.contains("secret")
            ) continue
            // Name / nickname.
            Regex("(?i)my name is ([A-Za-z ]{2,30})").find(raw)?.let { put("name", it.groupValues[1]) }
            Regex("(?i)call me ([A-Za-z ]{2,30})").find(raw)?.let { put("nickname", it.groupValues[1]) }
            // Favourites.
            Regex("(?i)my favou?rite food is ([^.,!]{2,60})").find(raw)?.let { put("favourite_food", it.groupValues[1]) }
            Regex("(?i)my favou?rite drink is ([^.,!]{2,60})").find(raw)?.let { put("favourite_drink", it.groupValues[1]) }
            Regex("(?i)my favou?rite song is ([^.,!]{2,60})").find(raw)?.let { put("favourite_song", it.groupValues[1]) }
            Regex("(?i)my favou?rite (?:film|movie) is ([^.,!]{2,60})").find(raw)?.let { put("favourite_film", it.groupValues[1]) }
            Regex("(?i)my favou?rite show is ([^.,!]{2,60})").find(raw)?.let { put("favourite_show", it.groupValues[1]) }
            // Likes / dislikes.
            // F3: capture-BEFORE patterns — in "mujhe biryani pasand hai"
            // nothing follows "pasand hai", so the after-patterns below
            // can never match Roman Urdu.
            // F14: the captures are tempered — they can never span a
            // second "mujhe" or a "nahi", so "mujhe chai pasand hai aur
            // mujhe coffee nahi pasand" gives likes=chai,
            // dislikes=coffee, and "mujhe biryani nahi pasand hai"
            // gives dislikes=biryani only (no bogus likes="biryani
            // nahi"). The after-patterns run only for a key the
            // capture-before patterns did not already match, so they
            // can never overwrite a good capture with trailing text.
            // [^.,!] keeps one capture from spanning a second clause
            // ("mujhe biryani pasand hai, mujhe cricket nahi pasand").
            val likesHit = Regex("(?i)mujhe ((?:(?!mujhe|nahi)[^.,!])+?) pasand hai").find(raw)
                ?.let { put("likes", it.groupValues[1]); true } ?: false
            val dislikesHit = Regex("(?i)mujhe ((?:(?!mujhe)[^.,!])+?) nahi pasand").find(raw)
                ?.let { put("dislikes", it.groupValues[1]); true } ?: false
            if (!likesHit) {
                Regex("(?i)(?:i like|i love|i really like)\\s+([^.,!]{2,60})").find(raw)?.let {
                    val v = it.groupValues[1].trim()
                    if (v.isNotEmpty() && !v.startsWith("to ")) put("likes", v)
                }
            }
            if (!dislikesHit) {
                Regex("(?i)(?:i hate|i don't like|i dont like)\\s+([^.,!]{2,60})").find(raw)?.let {
                    put("dislikes", it.groupValues[1])
                }
            }
            // Birthday / anniversary.
            Regex("(?i)my birthday is ([^.,!]{2,40})").find(raw)?.let { put("birthday", it.groupValues[1]) }
            Regex("(?i)my birthday is on ([^.,!]{2,40})").find(raw)?.let { put("birthday", it.groupValues[1]) }
            // F11(b): the category list has "anniversary" but no pattern
            // ever produced it — only birthday was extracted.
            Regex("(?i)my anniversary is ([^.,!]{2,40})").find(raw)?.let { put("anniversary", it.groupValues[1]) }
            Regex("(?i)my anniversary is on ([^.,!]{2,40})").find(raw)?.let { put("anniversary", it.groupValues[1]) }
            // Plans.
            Regex("(?i)(?:tomorrow i (?:have|am going to|will)|i am going to|my paper is on|i have a paper on)\\s+([^.,!]{2,80})").find(raw)?.let {
                put("plans", it.groupValues[1])
            }
            // Family / friends.
            Regex("(?i)my (brother|sister|mother|father|amma|abbu|ami|bhai|behen) (?:is |named )?([A-Za-z ]{2,30})").find(raw)?.let {
                put("family", "${it.groupValues[1]} ${it.groupValues[2]}".trim())
            }
            Regex("(?i)my (?:best )?friend (?:is |named )?([A-Za-z ]{2,30})").find(raw)?.let {
                put("friends", it.groupValues[1])
            }
            // Routine / habits.
            Regex("(?i)(?:i always|every day i|i never)\\s+([^.,!]{2,80})").find(raw)?.let {
                put("routine", it.groupValues[1])
            }
            // Study.
            Regex("(?i)my (?:favourite|favorite) subject is ([^.,!]{2,40})").find(raw)?.let {
                put("study", "favourite subject: ${it.groupValues[1]}")
            }
            Regex("(?i)i (?:study|am studying) ([^.,!]{2,40})").find(raw)?.let {
                put("study", it.groupValues[1])
            }
            // Health — only what he volunteers.
            Regex("(?i)i have (?:a |an )?([^.,!]{2,50})").find(raw)?.let {
                val v = it.groupValues[1]
                if (v.contains("fever") || v.contains("cold") || v.contains("headache") || v.contains("pain")) {
                    put("health", v, "low", "inferred")
                }
            }
            // Pet peeves.
            Regex("(?i)i (?:really )?(?:hate|can't stand|dont like) when ([^.,!]{2,80})").find(raw)?.let {
                put("pet_peeves", it.groupValues[1])
            }
        }
        return out.values.toList()
    }

    private fun cleanValue(v: String): String? {
        var s = v.trim().trimEnd('.', '!', '?', ',', ';', ':').trim()
        if (s.length < 2 || s.length > 120) return null
        // Drop leading filler words the regex may have caught.
        s = s.removePrefix("to ").removePrefix("the ").trim()
        return s.ifBlank { null }
    }

    /** Secret shapes are discarded before storage — never a fact. */
    private fun isSecret(v: String): Boolean {
        if (Regex("\\d{6,}").containsMatchIn(v)) return true
        if (Regex("[A-Za-z0-9_\\-]{24,}").containsMatchIn(v)) return true
        val low = v.lowercase()
        return low.contains("password") || low.contains("otp") || low.contains("bank")
            || low.contains("card") || low.contains("api key") || low.contains("secret")
    }
}
