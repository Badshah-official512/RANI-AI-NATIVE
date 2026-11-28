package com.badshah222.rani.ai.core

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * v3.0.0 §23.1 item 20 — Hisaab (money ledger), encrypted on-device.
 * Entries are income/expense rows with an amount (PKR), a note and a
 * timestamp. The file is an EncryptedFile (same pattern as MemoryStore) —
 * NEVER plaintext, never synced, never uploaded. Totals are computed
 * locally, never guessed.
 */
class HisaabStore(ctx: Context) {
    data class Entry(val id: String, val kind: String, val amount: Long, val note: String, val at: Long)

    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val file = EncryptedFile.Builder(
        java.io.File(appCtx.filesDir, "hisaab.json"),
        appCtx,
        MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private fun readAll(): JSONArray = synchronized(lock) {
        runCatching {
            val bytes = ByteArrayOutputStream().also { file.openFileInput().use { input -> input.copyTo(it) } }.toByteArray()
            JSONArray(String(bytes, Charsets.UTF_8))
        }.getOrDefault(JSONArray())
    }

    private fun writeAll(arr: JSONArray) = synchronized(lock) {
        file.openFileOutput().use { it.write(arr.toString().toByteArray(Charsets.UTF_8)) }
    }

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        val arr = readAll()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optString("id"), o.optString("kind", "expense"), o.optLong("amount"), o.optString("note"), o.optLong("at")))
        }
        return out.sortedByDescending { it.at }
    }

    fun add(kind: String, amount: Long, note: String) {
        if (amount <= 0) return
        val arr = readAll()
        arr.put(JSONObject().put("id", "h_${System.currentTimeMillis()}")
            .put("kind", if (kind == "income") "income" else "expense")
            .put("amount", amount).put("note", note.take(120)).put("at", System.currentTimeMillis()))
        scope.launch { runCatching { writeAll(arr) } }
    }

    fun delete(id: String) {
        val arr = readAll()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        scope.launch { runCatching { writeAll(kept) } }
    }

    fun totalIncome(): Long = entries().filter { it.kind == "income" }.sumOf { it.amount }
    fun totalExpense(): Long = entries().filter { it.kind == "expense" }.sumOf { it.amount }
}
