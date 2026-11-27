package com.badshah222.rani.ai.features.memory

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SpecialDay(val name: String, val month: Int, val day: Int)

/**
 * MYRA ConversationMemory — special days (birthdays, anniversaries), favourites,
 * memory toggles, and "Forget everything".
 *
 * v1.9.0 §21.2/§21.7 — the long-term memory system: facts RANI learned
 * from conversations (by category, with confidence), pending facts when
 * "Ask before saving" is on, the account-sync switch, and "Forget
 * everything" wiping on-device AND the user's Supabase rows. Everything
 * else stays on this phone.
 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    private val uid get() = c.auth.current?.uid
    val days = MutableStateFlow<List<SpecialDay>>(emptyList())
    val favourites = MutableStateFlow<Map<String, String>>(emptyMap())
    val memoryOn = MutableStateFlow(c.store.memoryOn)
    val memoryAskFirst = MutableStateFlow(c.store.memoryAskFirst)
    val memorySync = MutableStateFlow(c.store.memorySync)
    val facts = MutableStateFlow<List<com.badshah222.rani.ai.core.MemoryFact>>(emptyList())
    val pendingFacts = MutableStateFlow<List<com.badshah222.rani.ai.core.MemoryFact>>(emptyList())
    val status = MutableStateFlow<String?>(null)

    init { load() }

    private fun load() {
        days.value = runCatching {
            val arr = JSONArray(c.store.specialDaysJson)
            List(arr.length()) { i -> val o = arr.optJSONObject(i); SpecialDay(o.optString("name"), o.optInt("month"), o.optInt("day")) }
        }.getOrDefault(emptyList())
        favourites.value = runCatching {
            val o = JSONObject(c.store.favouritesJson)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
        facts.value = c.memory.facts()
        pendingFacts.value = c.memory.pendingFacts()
    }

    /** Reloads the fact lists (call after a call ends / a fact changes). */
    fun refreshFacts() { facts.value = c.memory.facts(); pendingFacts.value = c.memory.pendingFacts() }

    fun deleteFact(key: String) { c.memory.deleteFact(key); refreshFacts(); status.value = "Forgot \"$key\"." }
    fun approveFact(key: String) { c.memory.approvePending(key); refreshFacts(); status.value = "Saved \"$key\"." }
    fun addDay(name: String, month: Int, day: Int) {
        val n = name.trim(); if (n.isEmpty() || month !in 1..12 || day !in 1..31) { status.value = "Write a name and a valid date (month 1-12, day 1-31)."; return }
        val arr = JSONArray(c.store.specialDaysJson)
        arr.put(JSONObject().put("name", n).put("month", month).put("day", day))
        c.store.specialDaysJson = arr.toString(); load(); status.value = "Saved $n — I will wish you first on that day. 💜"
    }
    fun deleteDay(i: Int) {
        val arr = JSONArray(c.store.specialDaysJson)
        val out = JSONArray()
        for (j in 0 until arr.length()) if (j != i) out.put(arr.optJSONObject(j))
        c.store.specialDaysJson = out.toString(); load()
    }
    fun addFavourite(key: String, value: String) {
        val k = key.trim(); val v = value.trim()
        if (k.isEmpty() || v.isEmpty()) return
        val o = JSONObject(c.store.favouritesJson); o.put(k, v)
        c.store.favouritesJson = o.toString(); load()
    }
    fun deleteFavourite(key: String) {
        val o = JSONObject(c.store.favouritesJson); o.remove(key)
        c.store.favouritesJson = o.toString(); load()
    }
    fun setMemoryOn(v: Boolean) { c.store.memoryOn = v; memoryOn.value = v }
    fun setMemoryAskFirst(v: Boolean) { c.store.memoryAskFirst = v; memoryAskFirst.value = v }
    fun setMemorySync(v: Boolean) { c.store.memorySync = v; memorySync.value = v }

    /** "Forget everything" — chat history + special days + favourites +
     *  tasks + long-term memory (on-device AND the Supabase rows). */
    fun forgetEverything() {
        viewModelScope.launch {
            uid?.let { c.chats.clear(it) }
            c.store.specialDaysJson = "[]"; c.store.favouritesJson = "{}"; c.store.tasksJson = "[]"
            c.memory.forgetEverything()
            load(); status.value = "I forgot everything. Let us start fresh."
        }
    }
}

@Composable
fun MemoryScreen(vm: MemoryViewModel, onBack: () -> Unit) {
    val days by vm.days.collectAsState(); val favs by vm.favourites.collectAsState()
    val memoryOn by vm.memoryOn.collectAsState(); val askFirst by vm.memoryAskFirst.collectAsState()
    val memorySync by vm.memorySync.collectAsState()
    val facts by vm.facts.collectAsState(); val pending by vm.pendingFacts.collectAsState()
    val status by vm.status.collectAsState()
    var dayName by remember { mutableStateOf("") }; var dayMonth by remember { mutableStateOf("") }; var dayDay by remember { mutableStateOf("") }
    var favKey by remember { mutableStateOf("") }; var favVal by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    // Facts refresh when the screen opens (a call may have just ended).
    LaunchedEffect(Unit) { vm.refreshFacts() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Memory", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("RANI remembers your conversations, favourites and special days. The on-device store is the source of truth — with sync on, a copy also lives in your own account (owner-only). Nothing else ever leaves this phone.", color = Mute, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Remembering is ON", Modifier.weight(1f)); Switch(memoryOn, vm::setMemoryOn) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Ask before saving", Modifier.weight(1f)); Switch(askFirst, vm::setMemoryAskFirst) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Sync memories to my account", Modifier.weight(1f)); Switch(memorySync, vm::setMemorySync) }
                Text("When sync is off, memories stay only on this phone.", color = Mute, style = MaterialTheme.typography.bodySmall)
                status?.let { Text(it, color = Ok, style = MaterialTheme.typography.bodySmall) }
            }
            // ---- v1.9.0 §21.7 — what RANI remembers about you ----
            item { Text("What RANI remembers", style = MaterialTheme.typography.titleMedium, color = Gold) }
            if (facts.isEmpty()) {
                item { Text("Nothing saved yet — talk to RANI and she will remember the things that matter.", color = Mute, style = MaterialTheme.typography.bodyMedium) }
            }
            items(facts) { f ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.key.replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (if (f.confidence == "low") "You think: " else "") + f.value,
                                style = MaterialTheme.typography.bodyMedium, color = Mute,
                            )
                        }
                        IconButton({ vm.deleteFact(f.key) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            if (pending.isNotEmpty()) {
                item { Text("Waiting for your approval", style = MaterialTheme.typography.titleMedium, color = Gold) }
                items(pending) { f ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.key.replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                                Text("You think: ${f.value}", style = MaterialTheme.typography.bodyMedium, color = Mute)
                            }
                            TextButton({ vm.approveFact(f.key) }) { Text("Save") }
                            IconButton({ vm.deleteFact(f.key) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
            item { Text("Special days", style = MaterialTheme.typography.titleMedium, color = Gold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(dayName, { dayName = it }, Modifier.weight(1f), placeholder = { Text("Birthday / Anniversary…") }, singleLine = true)
                    OutlinedTextField(dayMonth, { dayMonth = it.filter { ch -> ch.isDigit() }.take(2) }, Modifier.width(56.dp), placeholder = { Text("MM") }, singleLine = true)
                    OutlinedTextField(dayDay, { dayDay = it.filter { ch -> ch.isDigit() }.take(2) }, Modifier.width(56.dp), placeholder = { Text("DD") }, singleLine = true)
                    Button({ vm.addDay(dayName, dayMonth.toIntOrNull() ?: 0, dayDay.toIntOrNull() ?: 0); dayName = ""; dayMonth = ""; dayDay = "" }) { Text("Add") }
                }
            }
            items(days.size) { i ->
                val d = days[i]
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.name} — ${d.day}/${d.month}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton({ vm.deleteDay(i) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            item { Text("Favourites", style = MaterialTheme.typography.titleMedium, color = Gold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(favKey, { favKey = it }, Modifier.weight(1f), placeholder = { Text("Favourite… (e.g. food)") }, singleLine = true)
                    OutlinedTextField(favVal, { favVal = it }, Modifier.weight(1f), placeholder = { Text("What? (e.g. biryani)") }, singleLine = true)
                    Button({ vm.addFavourite(favKey, favVal); favKey = ""; favVal = "" }) { Text("Add") }
                }
            }
            items(favs.entries.toList()) { (k, v) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$k: $v", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton({ vm.deleteFavourite(k) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            item {
                OutlinedButton({ confirmForget = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)) { Text("Forget everything — delete memory") }
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget everything?") },
            text = { Text("Chat history, special days, favourites, tasks and everything RANI remembers about you — on this phone and in your account — will be deleted. This cannot be undone.") },
            confirmButton = { TextButton({ confirmForget = false; vm.forgetEverything() }) { Text("Yes, delete everything", color = Bad) } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } },
        )
    }
}
