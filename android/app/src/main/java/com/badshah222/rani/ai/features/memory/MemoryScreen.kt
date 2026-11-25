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
 * memory toggles, aur "Sab bhool jao". Sab kuch sirf is phone par rehta hai —
 * memory kabhi upload nahi hoti.
 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    private val uid get() = c.auth.current?.uid
    val days = MutableStateFlow<List<SpecialDay>>(emptyList())
    val favourites = MutableStateFlow<Map<String, String>>(emptyMap())
    val memoryOn = MutableStateFlow(c.store.memoryOn)
    val memoryAskFirst = MutableStateFlow(c.store.memoryAskFirst)
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
    }
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

    /** "Sab bhool jao" — chat history + special days + favourites, sab delete. */
    fun forgetEverything() {
        viewModelScope.launch {
            uid?.let { c.chats.clear(it) }
            c.store.specialDaysJson = "[]"; c.store.favouritesJson = "{}"; c.store.tasksJson = "[]"
            load(); status.value = "I forgot everything. Let us start fresh. 💜"
        }
    }
}

@Composable
fun MemoryScreen(vm: MemoryViewModel, onBack: () -> Unit) {
    val days by vm.days.collectAsState(); val favs by vm.favourites.collectAsState()
    val memoryOn by vm.memoryOn.collectAsState(); val askFirst by vm.memoryAskFirst.collectAsState(); val status by vm.status.collectAsState()
    var dayName by remember { mutableStateOf("") }; var dayMonth by remember { mutableStateOf("") }; var dayDay by remember { mutableStateOf("") }
    var favKey by remember { mutableStateOf("") }; var favVal by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Memory", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("RANI remembers your conversations, favourites and special days — on this phone only. Nothing is ever uploaded.", color = Mute, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Yaad rakhna ON hai", Modifier.weight(1f)); Switch(memoryOn, vm::setMemoryOn) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Ask before saving", Modifier.weight(1f)); Switch(askFirst, vm::setMemoryAskFirst) }
                status?.let { Text(it, color = Ok, style = MaterialTheme.typography.bodySmall) }
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
                        Text("🎉 ${d.name} — ${d.day}/${d.month}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton({ vm.deleteDay(i) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            item { Text("Favourites", style = MaterialTheme.typography.titleMedium, color = Gold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(favKey, { favKey = it }, Modifier.weight(1f), placeholder = { Text("Pasandeeda… (jaise khana)") }, singleLine = true)
                    OutlinedTextField(favVal, { favVal = it }, Modifier.weight(1f), placeholder = { Text("Kya? (jaise biryani)") }, singleLine = true)
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
            title = { Text("Sab bhool jao?") },
            text = { Text("Chat history, special days, favourites and tasks — all will be deleted. This cannot be undone.") },
            confirmButton = { TextButton({ confirmForget = false; vm.forgetEverything() }) { Text("Yes, delete everything", color = Bad) } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } },
        )
    }
}
