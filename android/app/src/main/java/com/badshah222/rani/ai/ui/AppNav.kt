package com.badshah222.rani.ai.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.badshah222.rani.ai.features.contacts.ContactsScreen
import com.badshah222.rani.ai.features.contacts.ContactsViewModel
import com.badshah222.rani.ai.features.automation.AutomationScreen
import com.badshah222.rani.ai.features.automation.AutomationViewModel
import com.badshah222.rani.ai.features.coder.CoderScreen
import com.badshah222.rani.ai.features.coder.CoderViewModel
import com.badshah222.rani.ai.features.files.FilesScreen
import com.badshah222.rani.ai.features.files.FilesViewModel
import com.badshah222.rani.ai.features.home.VoiceModeScreen
import com.badshah222.rani.ai.features.media.MediaScreen
import com.badshah222.rani.ai.features.media.MediaViewModel
import com.badshah222.rani.ai.features.memory.MemoryScreen
import com.badshah222.rani.ai.features.memory.MemoryViewModel
import com.badshah222.rani.ai.features.monitor.SystemMonitorScreen
import com.badshah222.rani.ai.features.monitor.SystemMonitorViewModel
import com.badshah222.rani.ai.features.search.WebSearchScreen
import com.badshah222.rani.ai.features.search.WebSearchViewModel
import com.badshah222.rani.ai.features.tools.AiToolsScreen
import com.badshah222.rani.ai.features.tools.AiToolsViewModel
import com.badshah222.rani.ai.features.webforge.WebForgeScreen
import com.badshah222.rani.ai.features.webforge.WebForgeViewModel
import com.badshah222.rani.ai.features.browser.BrowserScreen
import com.badshah222.rani.ai.features.browser.BrowserViewModel
import com.badshah222.rani.ai.features.screen.ScreenShareScreen
import com.badshah222.rani.ai.features.screen.ScreenViewModel
import com.badshah222.rani.ai.features.auth.AuthScreen
import com.badshah222.rani.ai.features.auth.SessionViewModel
import com.badshah222.rani.ai.features.chat.ChatHistoryScreen
import com.badshah222.rani.ai.features.chat.ChatScreen
import com.badshah222.rani.ai.features.chat.HistoryViewModel
import com.badshah222.rani.ai.features.chat.SecretChatScreen
import com.badshah222.rani.ai.features.chat.SecretChatViewModel
import com.badshah222.rani.ai.features.help.HelpScreen
import com.badshah222.rani.ai.features.help.HelpViewModel
import com.badshah222.rani.ai.features.home.HomeScreen
import com.badshah222.rani.ai.features.home.HomeViewModel
import com.badshah222.rani.ai.features.home.QuickActionsScreen
import com.badshah222.rani.ai.features.home.VoiceScreen
import com.badshah222.rani.ai.features.permissions.PermissionsScreen
import com.badshah222.rani.ai.features.settings.SettingsScreen
import com.badshah222.rani.ai.features.settings.SettingsViewModel
import com.badshah222.rani.ai.ui.character.CharState
import com.badshah222.rani.ai.ui.orb.VoiceOrb
import com.badshah222.rani.ai.ui.theme.*

@Composable
fun AppNav(activity: Activity) {
    val session: SessionViewModel = viewModel()
    val user by session.user.collectAsState(); val blocked by session.blocked.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            user == null -> AuthScreen(session, activity)
            blocked -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Account blocked", style = MaterialTheme.typography.headlineSmall, color = Bad)
                Text("This account has been blocked by the RANI AI team. If you think this is a mistake, contact support through the WhatsApp link on the About page.", textAlign = TextAlign.Center, color = Mute, modifier = Modifier.padding(16.dp))
                Button({ session.signOut() }) { Text("Sign out") }
            }
            else -> SignedInNav(session)
        }
    }
}

private val TABS = setOf("home", "chat", "history", "settings")

@Composable
private fun SignedInNav(session: SessionViewModel) {
    val nav = rememberNavController()
    // ViewModels are created here (nav-graph scope) so Home state survives opening Settings/Help.
    val home: HomeViewModel = viewModel(); val settings: SettingsViewModel = viewModel(); val help: HelpViewModel = viewModel(); val history: HistoryViewModel = viewModel(); val browser: BrowserViewModel = viewModel(); val screen: ScreenViewModel = viewModel(); val secret: SecretChatViewModel = viewModel(); val contacts: ContactsViewModel = viewModel()
    val tools: AiToolsViewModel = viewModel(); val webforge: WebForgeViewModel = viewModel(); val coder: CoderViewModel = viewModel()
    val files: FilesViewModel = viewModel(); val media: MediaViewModel = viewModel(); val search: WebSearchViewModel = viewModel()
    val monitor: SystemMonitorViewModel = viewModel(); val automation: AutomationViewModel = viewModel(); val memory: MemoryViewModel = viewModel()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    fun tab(r: String) { nav.navigate(r) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }

    // "Browser kholo…" asked in chat/voice: open the assistant's own browser.
    val pendingBrowser by home.pendingBrowser.collectAsState()
    LaunchedEffect(pendingBrowser) { if (pendingBrowser != null) nav.navigate("browser") }

    // "Meri screen dekho" asked in chat/voice: open Screen Share.
    val pendingScreen by home.pendingScreen.collectAsState()
    LaunchedEffect(pendingScreen) { if (pendingScreen) { nav.navigate("screen"); home.pendingScreen.value = false } }

    // "Contacts dikhao" asked in chat/voice: open the Contacts screen.
    val pendingContacts by home.pendingContacts.collectAsState()
    LaunchedEffect(pendingContacts) { if (pendingContacts) { nav.navigate("contacts"); home.pendingContacts.value = false } }

    Scaffold(
        bottomBar = { if (route in TABS) LiaBottomBar(route, { tab(it) }, { nav.navigate("voice") }) },
        containerColor = Ink,
    ) { pad ->
        Box(Modifier.padding(pad)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(session, home) { nav.navigate(it) } }
                composable("chat") { ChatScreen(home, { nav.popBackStack() }, { nav.navigate("voice") }) }
                composable("voice") { VoiceScreen(home) { nav.popBackStack() } }
                composable("quick") { QuickActionsScreen(home, { nav.popBackStack() }, { nav.navigate(it) }) }
                composable("browser") { BrowserScreen(home, browser) { nav.popBackStack() } }
                composable("screen") { ScreenShareScreen(screen) { nav.popBackStack() } }
                composable("secret") { SecretChatScreen(secret, { nav.popBackStack() }, { nav.navigate(it) }) }
                composable("settings") { SettingsScreen(session, settings, { nav.popBackStack() }, { nav.navigate("permissions") }) }
                composable("help") { HelpScreen(help) { nav.popBackStack() } }
                composable("about") { AboutScreen { nav.popBackStack() } }
                composable("history") { ChatHistoryScreen(home, history) { nav.popBackStack() } }
                composable("contacts") { ContactsScreen(contacts) { nav.popBackStack() } }
                composable("tools") { AiToolsScreen(tools) { nav.popBackStack() } }
                composable("webforge") { WebForgeScreen(webforge) { nav.popBackStack() } }
                composable("coder") { CoderScreen(coder) { nav.popBackStack() } }
                composable("files") { FilesScreen(files) { nav.popBackStack() } }
                composable("media") { MediaScreen(media, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("search") { WebSearchScreen(search, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("monitor") { SystemMonitorScreen(monitor) { nav.popBackStack() } }
                composable("automation") { AutomationScreen(automation) { nav.popBackStack() } }
                composable("memory") { MemoryScreen(memory) { nav.popBackStack() } }
                composable("voicemode") { VoiceModeScreen(home, settings) { nav.popBackStack() } }
                composable("permissions") { PermissionsScreen { nav.popBackStack() } }
            }
        }
    }
}

/** Floating pill bottom bar with the voice orb in the centre, modelled on the reference app. */
@Composable
private fun LiaBottomBar(current: String, onTab: (String) -> Unit, onVoice: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel2).padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(Modifier.weight(1f), Icons.Default.Home, "Home", current == "home") { onTab("home") }
            TabItem(Modifier.weight(1f), Icons.Default.Chat, "Chat", current == "chat") { onTab("chat") }
            Spacer(Modifier.width(62.dp))
            TabItem(Modifier.weight(1f), Icons.Default.History, "History", current == "history") { onTab("history") }
            TabItem(Modifier.weight(1f), Icons.Default.Settings, "Settings", current == "settings") { onTab("settings") }
        }
        VoiceOrb(
            CharState.Idle, 0f, false,
            Modifier.align(Alignment.TopCenter).offset(y = (-16).dp).size(58.dp).clip(CircleShape).background(Ink).clickable { onVoice() },
        )
    }
}

@Composable
private fun TabItem(modifier: Modifier, icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) VioletSoft else Mute, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) VioletSoft else Mute, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
