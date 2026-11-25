package com.badshah222.rani.ai.features.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*

private data class QuickAction(val icon: ImageVector, val title: String, val example: String, val command: String?, val route: String? = null)

private val ACTIONS = listOf(
    QuickAction(Icons.Default.Apps, "Open an app", "\"YouTube kholo\"", "Open YouTube"),
    QuickAction(Icons.Default.Call, "Call someone", "\"Ali ko call karo\"", "Pick a contact"),
    QuickAction(Icons.Default.Message, "Send a message", "\"Papa ko WhatsApp karo\"", "Pick a contact"),
    QuickAction(Icons.Default.Send, "Type + send message", "\"Ali ko message likho aur bhejo\"", "Ali ko message likho aur bhejo: main ghar aa raha hoon"),
    QuickAction(Icons.Default.Contacts, "All contacts", "Naam se call / message", null, route = "contacts"),
    QuickAction(Icons.Default.RecordVoiceOver, "Voice modes", "Listen modes, voices, live model", null, route = "voicemode"),
    QuickAction(Icons.Default.AutoAwesome, "AI Tools", "Summarise, translate, PDF, image…", null, route = "tools"),
    QuickAction(Icons.Default.Language, "Website Builder", "WebForge — poori website banao", null, route = "webforge"),
    QuickAction(Icons.Default.Code, "Coder", "AI se code likhwao, ZIP lo", null, route = "coder"),
    QuickAction(Icons.Default.Folder, "My Files", "RANI ki banayi files", null, route = "files"),
    QuickAction(Icons.Default.PhotoCamera, "Media", "Camera vision, screenshots", null, route = "media"),
    QuickAction(Icons.Default.Search, "Web Search", "Google, YouTube, RANI se poocho", null, route = "search"),
    QuickAction(Icons.Default.MonitorHeart, "System Monitor", "CPU, RAM, battery, processes", null, route = "monitor"),
    QuickAction(Icons.Default.Schedule, "Automation", "Tasks, reminders, routines", null, route = "automation"),
    QuickAction(Icons.Default.Memory, "Memory", "Special days, favourites", null, route = "memory"),
    QuickAction(Icons.Default.PhotoLibrary, "Open gallery", "\"Gallery kholo\"", "Open gallery"),
    QuickAction(Icons.Default.VideoLibrary, "Open videos", "\"Meri videos kholo\"", "Open my videos"),
    QuickAction(Icons.Default.Folder, "Open files", "\"Files kholo\"", "Open files"),
    QuickAction(Icons.Default.FlashlightOn, "Flashlight", "\"Torch chalao\"", "Turn on the flashlight"),
    QuickAction(Icons.Default.Timer, "Set a timer", "\"5 minute ka timer\"", "Set a timer for 5 minutes"),
    QuickAction(Icons.Default.Alarm, "Set an alarm", "\"Subah 7 baje ka alarm\"", "Set an alarm for 7 am"),
    QuickAction(Icons.Default.Image, "Create image", "\"Ek golden crown logo banao\"", "Ek golden crown ka premium logo banao, image banao"),
    QuickAction(Icons.Default.ScreenShare, "Screen share", "\"Meri screen dekho\"", null, route = "screen"),
    QuickAction(Icons.Default.Public, "Assistant browser", "\"Browser kholo\"", null, route = "browser"),
    QuickAction(Icons.Default.Lock, "Secret Chat", "\"Heavy work — password protected\"", null, route = "secret"),
    QuickAction(Icons.Default.Mic, "Talk to RANI", "Start a voice conversation", null),
)

/** Phone-control quick actions, modelled on the reference app's Quick actions page. */
@Composable
fun QuickActionsScreen(vm: HomeViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    var ytQuery by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Quick actions", onBack)
        Text(
            "Tap one to start. Say it the way you'd say it to a friend — English, Urdu or both.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Mute, style = MaterialTheme.typography.bodyMedium,
        )
        // YouTube search (spec §14.2): type it, RANI searches YouTube for you.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ytQuery, { ytQuery = it }, Modifier.weight(1f), placeholder = { Text("YouTube par kya dhoondna hai?") }, singleLine = true, shape = RoundedCornerShape(28.dp))
            Button({ if (ytQuery.isNotBlank()) { vm.send("YouTube par ${ytQuery.trim()} search karo"); ytQuery = "" } }) { Text("Search") }
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ACTIONS) { a ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        when {
                            a.route != null -> onNav(a.route)
                            a.command == null -> onNav("voice")
                            else -> { vm.send(a.command); onNav("chat") }
                        }
                    },
                    colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoxIcon(a.icon)
                        Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(a.example, color = Mute, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text(
            "These are examples — you can always say your own words in Chat or on the voice screen.",
            Modifier.padding(horizontal = 16.dp), color = Mute, style = MaterialTheme.typography.bodySmall,
        )
    }
    ActionDialogs(vm)
}

@Composable
private fun BoxIcon(icon: ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = VioletDeep), shape = RoundedCornerShape(12.dp)) {
        Icon(icon, null, Modifier.padding(10.dp), tint = androidx.compose.ui.graphics.Color.White)
    }
}
