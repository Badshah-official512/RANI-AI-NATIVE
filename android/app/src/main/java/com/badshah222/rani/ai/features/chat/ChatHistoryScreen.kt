package com.badshah222.rani.ai.features.chat

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.data.supabase.CloudException
import com.badshah222.rani.ai.data.supabase.RemoteConvo
import com.badshah222.rani.ai.data.supabase.RemoteMsg
import com.badshah222.rani.ai.features.home.HomeViewModel
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val convos = MutableStateFlow<List<RemoteConvo>>(emptyList())
    val opened = MutableStateFlow<Pair<RemoteConvo, List<RemoteMsg>>?>(null)
    val error = MutableStateFlow<String?>(null)
    init { refresh() }
    fun refresh() = viewModelScope.launch { try { convos.value = c.cloud.listConversations(); error.value = null } catch (e: CloudException) { error.value = e.message } }
    fun open(cv: RemoteConvo) = viewModelScope.launch { runCatching { opened.value = cv to c.cloud.listMessages(cv.id) } }
    fun close() { opened.value = null }
    fun delete(cv: RemoteConvo) = viewModelScope.launch { runCatching { c.cloud.deleteConversation(cv.id) }; opened.value = null; refresh() }
}

@Composable
fun ChatHistoryScreen(home: HomeViewModel, vm: HistoryViewModel, onBack: () -> Unit) {
    val local by home.messages.collectAsState(); val convos by vm.convos.collectAsState(); val err by vm.error.collectAsState(); val opened by vm.opened.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) convos else convos.filter { it.title.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Conversations", onBack)
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text("Search conversations") }, leadingIcon = { Icon(Icons.Default.Search, "Search") },
            singleLine = true, shape = RoundedCornerShape(24.dp),
        )
        LazyColumn(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ home.newChat() }) { Text("New chat") }; OutlinedButton({ vm.refresh() }) { Text("Refresh") } } }
            item { Text("On this phone", style = MaterialTheme.typography.titleMedium, color = VioletSoft, modifier = Modifier.padding(top = 4.dp)) }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Current chat · ${local.size} messages", fontWeight = FontWeight.SemiBold)
                        Text(local.lastOrNull()?.let { (if (it.role == "user") "You: " else "RANI: ") + it.content } ?: "No messages yet — say hello from the Home tab.", style = MaterialTheme.typography.bodySmall, color = Mute, maxLines = 2)
                    }
                }
            }
            item { Text("Synced conversations", style = MaterialTheme.typography.titleMedium, color = VioletSoft, modifier = Modifier.padding(top = 8.dp)) }
            err?.let { e -> item { Text("Cloud history unavailable: $e", color = Mute, style = MaterialTheme.typography.bodySmall) } }
            if (convos.isNotEmpty() && shown.isEmpty()) item { Text("No conversations match your search.", color = Mute, style = MaterialTheme.typography.bodySmall) }
            items(shown) { cv ->
                Card(onClick = { vm.open(cv) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(cv.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(cv.updatedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall, color = Mute)
                    }
                }
            }
        }
    }
    opened?.let { (cv, msgs) ->
        AlertDialog(onDismissRequest = vm::close, title = { Text(cv.title) },
            text = { LazyColumn { items(msgs) { m -> Text((if (m.role == "user") "You: " else "RANI: ") + m.content, Modifier.padding(vertical = 4.dp)) } } },
            confirmButton = { TextButton(vm::close) { Text("Close") } }, dismissButton = { TextButton({ vm.delete(cv) }) { Text("Delete", color = Bad) } })
    }
}
