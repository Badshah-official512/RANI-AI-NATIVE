package com.badshah222.rani.ai.features.automation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.RaniTools
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class TaskItem(val title: String, val done: Boolean)

/**
 * MYRA AutomationPage — tasks / reminders (add, done, delete) + 6 routines
 * with MYRA's exact prompts, volume and brightness. Routines apply the
 * device settings on this phone and RANI confirms by voice.
 */
class AutomationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val status = MutableStateFlow<String?>(null)

    init { load() }

    private fun load() {
        tasks.value = runCatching {
            val arr = JSONArray(c.store.tasksJson)
            List(arr.length()) { i -> val o = arr.optJSONObject(i); TaskItem(o.optString("title"), o.optBoolean("done")) }
        }.getOrDefault(emptyList())
    }
    private fun save(list: List<TaskItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("done", it.done)) }
        c.store.tasksJson = arr.toString()
        tasks.value = list
    }
    fun addTask(title: String) { val t = title.trim(); if (t.isNotEmpty()) save(tasks.value + TaskItem(t, false)) }
    fun toggleTask(i: Int) { val l = tasks.value.toMutableList(); l[i] = l[i].copy(done = !l[i].done); save(l) }
    fun deleteTask(i: Int) { save(tasks.value.filterIndexed { idx, _ -> idx != i }) }

    fun runRoutine(r: RaniTools.Routine, ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        r.volume?.let { v ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max / 100).coerceIn(0, max), 0)
        }
        r.brightness?.let { b ->
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (b * 255 / 100).coerceIn(10, 255))
            } else {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                status.value = "Brightness ke liye pehle RANI ko 'Modify system settings' ki permission do."
                return
            }
        }
        viewModelScope.launch { c.tts.speak("${r.name} on, Babu Jaan.") }
        status.value = "${r.name} on — ${r.prompt}"
    }
}

@Composable
fun AutomationScreen(vm: AutomationViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tasks by vm.tasks.collectAsState(); val status by vm.status.collectAsState()
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Automation — Tasks & Routines", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Naya task / reminder…") }, singleLine = true, shape = RoundedCornerShape(28.dp))
                    Button({ vm.addTask(draft); draft = "" }) { Text("Add") }
                }
            }
            items(tasks.size) { i ->
                val t = tasks[i]
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.done, { vm.toggleTask(i) })
                        Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (t.done) Mute else MaterialTheme.colorScheme.onBackground)
                        IconButton({ vm.deleteTask(i) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            status?.let { item { Text(it, color = Ok, style = MaterialTheme.typography.bodySmall) } }
            item { Text("Routines — MYRA style", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp)) }
            items(RaniTools.routines) { r ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text(r.prompt, color = Mute, style = MaterialTheme.typography.bodySmall)
                            val bits = listOfNotNull(r.volume?.let { "Volume $it%" }, r.brightness?.let { "Brightness $it%" })
                            if (bits.isNotEmpty()) Text(bits.joinToString(" · "), color = Gold, style = MaterialTheme.typography.bodySmall)
                        }
                        Button({ vm.runRoutine(r, ctx) }) { Text("Run") }
                    }
                }
            }
        }
    }
}
