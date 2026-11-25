package com.badshah222.rani.ai.features.coder

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.features.chat.extractCodeFiles
import com.badshah222.rani.ai.features.chat.saveCodeZip
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val CODER_SYSTEM = """
You are RANI's Coder (MYRA Coder behaviour): an interactive coding partner. When the user asks for code, return COMPLETE ready-to-use files —
every file in its own fenced code block whose opening line names the file, like ```html index.html. Never cut a file short, never use placeholders
like "// ... rest of the code". Explain briefly in plain words before the code, in the user's language. If the request is vague, ask ONE short
clarifying question instead of guessing. Call the user Babu Jaan.
""".trimIndent()

enum class TaskState { PENDING, WORKING, DONE }
data class CodeTask(val name: String, val content: String, var state: TaskState)

/**
 * MYRA Coder — interactive coding session: prompt → complete files as tasks
 * (○ pending → ◐ working → ✔ done) → approve → ZIP download. @file attaches
 * one of your files as context.
 */
class CoderViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val history = MutableStateFlow<List<ChatMessage>>(emptyList())
    val tasks = MutableStateFlow<List<CodeTask>>(emptyList())
    val attachedName = MutableStateFlow<String?>(null)
    val attachedText = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)

    fun attach(uri: Uri, name: String?, ctx: android.content.Context) {
        viewModelScope.launch {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() ?: ""
            attachedName.value = name ?: "file"
            attachedText.value = text.take(12000)
            status.value = if (text.isNotEmpty()) "Attached: ${attachedName.value} (${text.length} chars) — now write your prompt." else "Could not read the file."
        }
    }

    fun send(prompt: String) {
        val p = prompt.trim(); if (p.isEmpty() || busy.value) return
        val full = if (attachedText.value.isNotBlank()) "File '${attachedName.value}' ka content:\n```\n${attachedText.value}\n```\n\nTask: $p" else p
        history.value = history.value + ChatMessage("user", p)
        tasks.value = emptyList()
        busy.value = true; status.value = "Writing code…"
        viewModelScope.launch {
            try {
                val reply = c.ai.chat(history.value, systemOverride = CODER_SYSTEM)
                val files = extractCodeFiles(reply.text)
                history.value = history.value + ChatMessage("assistant", reply.text)
                if (files.isEmpty()) { status.value = "No file was created this time — tell me again what you need."; return@launch }
                tasks.value = files.map { CodeTask(it.first, it.second, TaskState.PENDING) }
                status.value = "${files.size} files ready — press Approve below, then download the ZIP."
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "command", mapOf("tool" to "coder", "files" to files.size)) }
            } catch (e: AiException) { status.value = e.userMessage }
            catch (e: Exception) { status.value = "Code could not be created — check the internet or the key." }
            finally { busy.value = false }
        }
    }

    fun approveAll() { tasks.value = tasks.value.map { it.copy(state = TaskState.DONE) }; status.value = "Approved ✔ — now download the ZIP." }
    fun markWorking(name: String) { tasks.value = tasks.value.map { if (it.name == name) it.copy(state = TaskState.WORKING) else it } }
}

@Composable
fun CoderScreen(vm: CoderViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val history by vm.history.collectAsState(); val tasks by vm.tasks.collectAsState()
    val attached by vm.attachedName.collectAsState(); val busy by vm.busy.collectAsState(); val status by vm.status.collectAsState()
    var input by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            vm.attach(uri, name, ctx)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Coder", onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { Text("Describe what to build — RANI will write the complete code, one file at a time. Attach your file with @file.", color = Mute, style = MaterialTheme.typography.bodyMedium) }
            if (attached != null) item { Text("📎 Attached: $attached", color = Gold, style = MaterialTheme.typography.bodySmall) }
            items(history) { m ->
                Text(m.content.take(600), Modifier.fillMaxWidth(0.95f), color = if (m.role == "user") Color.White else VioletSoft, style = MaterialTheme.typography.bodyMedium)
            }
            if (tasks.isNotEmpty()) {
                item { Text("Tasks", style = MaterialTheme.typography.titleMedium, color = Gold) }
                items(tasks) { t ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (t.state) { TaskState.PENDING -> "○"; TaskState.WORKING -> "◐"; TaskState.DONE -> "✔" }, color = Gold, modifier = Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) { Text(t.name, style = MaterialTheme.typography.bodyLarge); Text("${t.content.lines().size} lines · ${t.content.length} chars", color = Mute, style = MaterialTheme.typography.bodySmall) }
                            if (t.state == TaskState.PENDING) TextButton({ vm.markWorking(t.name) }) { Text("Start") }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(vm::approveAll) { Text("Approve all ✔") }
                        Button({
                            val msg = saveCodeZip(ctx, tasks.map { it.name to it.content })
                            vm.status.value = msg
                        }) { Text("Download ZIP") }
                    }
                }
            }
            status?.let { item { Text(it, color = if (it.contains("nahi")) Bad else Ok, style = MaterialTheme.typography.bodySmall) } }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton({ picker.launch("*/*") }) { Icon(Icons.Default.AttachFile, "@file") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Kya code chahiye?") }, singleLine = true, shape = RoundedCornerShape(28.dp))
            Button({ vm.send(input); input = "" }, enabled = !busy) { Text("Send") }
        }
    }
}
