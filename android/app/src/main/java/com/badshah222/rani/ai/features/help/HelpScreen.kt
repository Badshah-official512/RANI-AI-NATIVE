package com.badshah222.rani.ai.features.help

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.Config
import com.badshah222.rani.ai.data.supabase.*
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HelpViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val tickets = MutableStateFlow<List<TicketRow>>(emptyList())
    val replies = MutableStateFlow<Map<String, List<TicketReply>>>(emptyMap())
    val msg = MutableStateFlow<Pair<Boolean, String>?>(null)
    val busy = MutableStateFlow(false)
    init { refresh() }

    fun refresh() = viewModelScope.launch {
        try { val t = c.cloud.listTickets(); tickets.value = t; replies.value = t.associate { it.id to runCatching { c.cloud.ticketReplies(it.id) }.getOrDefault(emptyList()) } }
        catch (e: CloudException) { msg.value = false to e.message.orEmpty() }
    }
    fun submit(subject: String, category: String, message: String, done: () -> Unit) = viewModelScope.launch {
        val uid = c.auth.current?.uid ?: return@launch
        if (subject.isBlank() || message.isBlank()) { msg.value = false to "Please fill in the subject and message."; return@launch }
        busy.value = true
        try { c.cloud.createTicket(uid, subject.trim(), category, message.trim()); msg.value = true to "Sent. Our team will reply here and by email."; done(); refresh() }
        catch (e: CloudException) { msg.value = false to "Could not send: ${e.message}" } finally { busy.value = false }
    }
    fun followUp(ticketId: String, text: String) = viewModelScope.launch {
        try { c.cloud.addTicketMessage(ticketId, text); refresh() } catch (e: CloudException) { msg.value = false to e.message.orEmpty() }
    }
}

private val FAQ = listOf(
    "Do I need an API key?" to "Yes. RANI AI has no shared key. Create your own free key at Google AI Studio (Gemini) or Groq Console, paste it in Settings > Connections, and press Test & save.",
    "Where is my API key stored?" to "Only on your phone, encrypted with Android Keystore. It is never uploaded to RANI servers and administrators cannot see it.",
    "Why does the mic not work?" to "Allow the Microphone permission (Settings > Permissions) and make sure Google speech services are installed. Some languages need internet for recognition.",
    "Why are all the voices female?" to "RANI is your AI girlfriend — saari voices female hain, default Aoede (MYRA wali). Male voices nahi hain, aur kabhi nahi hongi.",
    "Is my chat private?" to "Chats are saved on your phone and, when cloud sync is on, in your own protected account area. Admin analytics only show counts, never message text.",
    "Will the assistant call or text without asking?" to "No. Calls open the dialer and messages open the composer pre-filled; you always press the final button. \"Message likho aur bhejo\" par RANI pehle exact message dikha kar confirm karti hai, aur sirf Accessibility ON hone par khud type karke Send dabati hai.",
)
private val GUIDES = listOf(
    "Set up Gemini" to "1) Open https://aistudio.google.com/app/apikey and sign in.\n2) Create an API key and copy it.\n3) In RANI AI open Settings > Connections, choose Google Gemini, paste the key, press Test & save.\n4) Pick a model from the list.",
    "Set up Groq" to "1) Open https://console.groq.com/keys and sign in.\n2) Create an API key and copy it.\n3) In Settings > Connections choose Groq, paste the key, press Test & save.\n4) Pick a model.",
    "Set up voices" to "Settings > Voice & model — Live voice: saari voices female hain (Aoede default). Voice sunne ke liye Voice Call kholo. Chat replies ki awaaz ke liye Settings > Google Voice (optional, female voices).",
    "Permissions explained" to "Microphone: voice commands. Contacts: RANI reads ALL your contacts on this phone so she can call/message anyone by name — contacts never leave the phone. Notifications: support replies. Type + send only works when Accessibility is ON, and only after you confirm the exact message.",
    "Troubleshooting" to "Invalid key: create a new one and paste again. Rate limit: wait or switch provider. No voice: install voice data. Cloud sync off: sign in again with internet, the support team must have deployed the Firebase claims function. Still stuck? Send us a ticket below.",
    "Privacy" to "Your keys stay on the phone. Your profile, preferences, chat history and tickets are stored in your protected account area and only you can read your chats. See the About page for contact options.",
)

@Composable
fun HelpScreen(vm: HelpViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tickets by vm.tickets.collectAsState(); val replies by vm.replies.collectAsState(); val msg by vm.msg.collectAsState(); val busy by vm.busy.collectAsState()
    var subject by remember { mutableStateOf("") }; var body by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("general") }
    var open by remember { mutableStateOf<String?>(null) }; var follow by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Help center", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("FAQs", style = MaterialTheme.typography.titleMedium, color = Gold) }
            items(FAQ) { (q, a) -> Expand(q, a) }
            item { Text("Guides", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp)) }
            items(GUIDES) { (q, a) -> Expand(q, a) }
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Contact support", style = MaterialTheme.typography.titleMedium, color = Gold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("general", "api", "voice", "account", "bug").forEach { FilterChip(cat == it, { cat = it }, { Text(it) }) } }
                OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text("Subject") }, singleLine = true)
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Describe the problem") }, minLines = 3)
                Button({ vm.submit(subject, cat, body) { subject = ""; body = "" } }, enabled = !busy) { Text("Send to support") }
                msg?.let { Text(it.second, color = if (it.first) Ok else Bad, style = MaterialTheme.typography.bodySmall) }
                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.WHATSAPP))) }) { Text("Or chat on WhatsApp") }
            } } }
            item { Text("My tickets", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp)) }
            if (tickets.isEmpty()) item { Text("No tickets yet.", color = Mute) }
            items(tickets) { t ->
                Card(Modifier.fillMaxWidth().clickable { open = if (open == t.id) null else t.id }, colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(12.dp)) {
                    Row { Text(t.subject, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(t.status.replace('_', ' '), color = when (t.status) { "resolved" -> Ok; "open" -> Gold; else -> Blue }) }
                    if (open == t.id) {
                        Text(t.message, Modifier.padding(top = 6.dp), color = Mute)
                        replies[t.id].orEmpty().forEach { r -> Text((if (r.sender == "admin") "Support: " else "You: ") + r.message, Modifier.padding(top = 6.dp), color = if (r.sender == "admin") Gold else MaterialTheme.colorScheme.onSurface) }
                        OutlinedTextField(follow, { follow = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Reply") })
                        TextButton({ if (follow.isNotBlank()) { vm.followUp(t.id, follow); follow = "" } }) { Text("Send reply") }
                    }
                } }
            }
        }
    }
}

@Composable private fun Expand(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }, colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge); if (open) Text(body, Modifier.padding(top = 6.dp), color = Mute, style = MaterialTheme.typography.bodyMedium)
    } }
}
