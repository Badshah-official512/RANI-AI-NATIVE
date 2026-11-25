package com.badshah222.rani.ai.features.search

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.domain.RaniTools
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.features.webforge.WebForgeShared
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * MYRA WebSearchPage — search engines, quick sites, "RANI se poocho"
 * (Gemini se seedha jawab), aur website builder prompt.
 */
class WebSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val engine = MutableStateFlow(RaniTools.engines.first())
    val answer = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun askRani(q: String) {
        val query = q.trim(); if (query.isEmpty() || busy.value) return
        busy.value = true; answer.value = null
        viewModelScope.launch {
            try {
                val reply = c.ai.chat(listOf(ChatMessage("user", query)))
                answer.value = reply.text
            } catch (e: AiException) { answer.value = "Jawab nahi mila: ${e.userMessage}" }
            catch (e: Exception) { answer.value = "Jawab nahi mila — internet ya key check karo." }
            finally { busy.value = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSearchScreen(vm: WebSearchViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    val ctx = LocalContext.current
    val engine by vm.engine.collectAsState(); val answer by vm.answer.collectAsState(); val busy by vm.busy.collectAsState()
    var query by remember { mutableStateOf("") }
    fun openUrl(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Web Search", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Kya dhoondna hai?") }, singleLine = true, shape = RoundedCornerShape(28.dp))
            }
            item {
                Text("Search engine", style = MaterialTheme.typography.labelLarge, color = Gold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaniTools.engines.forEach { e -> FilterChip(engine.key == e.key, { vm.engine.value = e }, { Text(e.label) }) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (query.isNotBlank()) openUrl(engine.urlTemplate.replace("{q}", URLEncoder.encode(query.trim(), "UTF-8"))) }) { Text("${engine.label} par search karo") }
                    OutlinedButton({ vm.askRani(query) }, enabled = !busy) { Text("RANI se poocho") }
                }
            }
            answer?.let { item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) } } }
            item {
                Text("Quick sites", style = MaterialTheme.typography.labelLarge, color = Gold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaniTools.quickSites.forEach { s -> FilterChip(false, { openUrl(s.url) }, { Text(s.name) }) }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Website builder", style = MaterialTheme.typography.titleMedium, color = Gold)
                        Text("Isi topic par poori website banwani hai? WebForge kholo — prompt pehle se bhara hoga.", color = Mute, style = MaterialTheme.typography.bodySmall)
                        Button({
                            if (query.isNotBlank()) { WebForgeShared.prompt = "Ek website banao: ${query.trim()}"; onNav("webforge") }
                        }) { Text("Is par website banao") }
                    }
                }
            }
        }
    }
}
