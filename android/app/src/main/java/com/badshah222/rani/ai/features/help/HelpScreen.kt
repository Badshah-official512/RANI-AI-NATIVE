package com.badshah222.rani.ai.features.help

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
import com.badshah222.rani.ai.core.Config
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*

/**
 * v3.0.0 §23.1 item 31 (dev-finish) — ticket support UNWIRED.
 *
 * The previous build posted tickets into the SHARED BADSHAH
 * `support_tickets` Supabase table (SupabaseApi.createTicket /
 * listTickets / addTicketMessage). The spec says that needs the boss's
 * decision first — RANI tickets must not land in the shared BADSHAH
 * table without his say-so, and no `rani_*` tickets table exists yet.
 * So this screen no longer submits or lists tickets at all: it shows
 * FAQs, guides, and the honest note below + the WhatsApp contact
 * option. The SupabaseApi ticket methods stay in place (unused) for
 * whichever table the boss chooses; nothing here calls them.
 *
 * DECISION NEEDED (boss): RANI tickets in the shared BADSHAH
 * support_tickets table, or a new rani_support_tickets table
 * (migration, same owner-only pattern as 015/016)?
 */
private val FAQ = listOf(
    "Do I need an API key?" to "Yes. RANI AI has no shared key. Create your own free key at Google AI Studio (Gemini) or Groq Console, paste it in Settings > Connections, and press Test & save.",
    "Where is my API key stored?" to "Only on your phone, encrypted with Android Keystore. It is never uploaded to RANI servers and administrators cannot see it.",
    "Why does the mic not work?" to "Allow the Microphone permission (Settings > Permissions) and make sure Google speech services are installed. Some languages need internet for recognition.",
    "Why are all the voices female?" to "RANI is your AI girlfriend — all voices are female, default Aoede. There are no male voices, and there never will be.",
    "Is my chat private?" to "Chats are saved on your phone and, when cloud sync is on, in your own protected account area. Admin analytics only show counts, never message text.",
    "Will the assistant call or text without asking?" to "No. Calls open the dialer and messages open the composer pre-filled; you always press the final button. For \"type and send a message\" RANI first shows you the exact message to confirm, and only types and taps Send herself when Accessibility is ON.",
)
private val GUIDES = listOf(
    "Set up Gemini" to "1) Open https://aistudio.google.com/app/apikey and sign in.\n2) Create an API key and copy it.\n3) In RANI AI open Settings > Connections, choose Google Gemini, paste the key, press Test & save.\n4) Pick a model from the list.",
    "Set up Groq" to "1) Open https://console.groq.com/keys and sign in.\n2) Create an API key and copy it.\n3) In Settings > Connections choose Groq, paste the key, press Test & save.\n4) Pick a model.",
    "Set up voices" to "Settings > Voice & model — Live voice: all voices are female (Aoede default). Open Talk to RANI to hear the voice. For the chat-reply voice, see Settings > Google Voice (optional, female voices).",
    "Permissions explained" to "Microphone: voice commands. Contacts: RANI reads ALL your contacts on this phone so she can call/message anyone by name — contacts never leave the phone. Notifications: support replies. Type + send only works when Accessibility is ON, and only after you confirm the exact message.",
    "Troubleshooting" to "Invalid key: create a new one and paste again. Rate limit: wait or switch provider. No voice: install voice data. Cloud sync off: sign in again with internet, the support team must have deployed the Firebase claims function. Still stuck? Message us on WhatsApp below.",
    "Privacy" to "Your keys stay on the phone. Your profile, preferences, chat history and tickets are stored in your protected account area and only you can read your chats. See the About page for contact options.",
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Help center", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("FAQs", style = MaterialTheme.typography.titleMedium, color = Gold) }
            items(FAQ) { (q, a) -> Expand(q, a) }
            item { Text("Guides", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp)) }
            items(GUIDES) { (q, a) -> Expand(q, a) }
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Contact support", style = MaterialTheme.typography.titleMedium, color = Gold)
                Text("Ticket support is not connected yet — in-app tickets are switched off until the team decides where they should go. For now, message us on WhatsApp and we will help you there.", color = Mute, style = MaterialTheme.typography.bodyMedium)
                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.WHATSAPP))) }) { Text("Chat on WhatsApp") }
            } } }
        }
    }
}

@Composable private fun Expand(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }, colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge); if (open) Text(body, Modifier.padding(top = 6.dp), color = Mute, style = MaterialTheme.typography.bodyMedium)
    } }
}
