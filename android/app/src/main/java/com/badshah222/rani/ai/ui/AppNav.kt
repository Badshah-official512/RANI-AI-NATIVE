package com.badshah222.rani.ai.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.badshah222.rani.ai.MainActivity
import com.badshah222.rani.ai.features.about.AboutScreen
import com.badshah222.rani.ai.features.camera.CameraViewScreen
import com.badshah222.rani.ai.features.camera.CameraViewModel
import com.badshah222.rani.ai.features.controls.ControlsScreen
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
import com.badshah222.rani.ai.features.recordings.RecordingsScreen
import com.badshah222.rani.ai.features.search.WebSearchScreen
import com.badshah222.rani.ai.features.search.WebSearchViewModel
import com.badshah222.rani.ai.features.tools.AiToolsScreen
import com.badshah222.rani.ai.features.tools.AiToolsViewModel
import com.badshah222.rani.ai.features.visualhub.VisualHubScreen
import com.badshah222.rani.ai.features.visualhub.VisualHubViewModel
import com.badshah222.rani.ai.features.webforge.WebForgeScreen
import com.badshah222.rani.ai.features.webforge.WebForgeViewModel
import com.badshah222.rani.ai.features.world.YourWorldScreen
import com.badshah222.rani.ai.features.world.YourWorldViewModel
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

/**
 * Stonic navigation: ONE scrolling home page, everything else reached from
 * it. There is no bottom bar and no tab bar anywhere — Stonic's window has
 * none, and the old MYRA-style tab bar is removed.
 */
@Composable
private fun SignedInNav(session: SessionViewModel) {
    val nav = rememberNavController()
    // v1.4.0 — floating orb tap ("G Babu" wake) opens the voice call ON TOP of
    // the screen, BADSHAH-style: the orb sends EXTRA_WAKE, MainActivity sets
    // wakeVoiceFlow, and the app navigates straight to the call screen instead
    // of home. Reset after navigating so it fires once per tap.
    val wakeVoice by MainActivity.wakeVoiceFlow.collectAsState()
    LaunchedEffect(wakeVoice) {
        if (wakeVoice) {
            MainActivity.wakeVoiceFlow.value = false
            nav.navigate("voice") { launchSingleTop = true }
        }
    }
    // ViewModels are created here (nav-graph scope) so Home state survives opening Settings/Help.
    val home: HomeViewModel = viewModel(); val settings: SettingsViewModel = viewModel(); val help: HelpViewModel = viewModel(); val history: HistoryViewModel = viewModel(); val browser: BrowserViewModel = viewModel(); val screen: ScreenViewModel = viewModel(); val secret: SecretChatViewModel = viewModel(); val contacts: ContactsViewModel = viewModel()
    val tools: AiToolsViewModel = viewModel(); val webforge: WebForgeViewModel = viewModel(); val coder: CoderViewModel = viewModel()
    val files: FilesViewModel = viewModel(); val media: MediaViewModel = viewModel(); val search: WebSearchViewModel = viewModel()
    val monitor: SystemMonitorViewModel = viewModel(); val automation: AutomationViewModel = viewModel(); val memory: MemoryViewModel = viewModel()
    val world: YourWorldViewModel = viewModel(); val visualhub: VisualHubViewModel = viewModel()
    val camera: CameraViewModel = viewModel()

    // "Browser kholo…" asked in chat/voice: open the assistant's own browser.
    val pendingBrowser by home.pendingBrowser.collectAsState()
    LaunchedEffect(pendingBrowser) { if (pendingBrowser != null) nav.navigate("browser") }

    // "Meri screen dekho" asked in chat/voice: open Screen Share.
    val pendingScreen by home.pendingScreen.collectAsState()
    LaunchedEffect(pendingScreen) { if (pendingScreen) { nav.navigate("screen"); home.pendingScreen.value = false } }

    // "Contacts dikhao" asked in chat/voice: open the Contacts screen.
    val pendingContacts by home.pendingContacts.collectAsState()
    LaunchedEffect(pendingContacts) { if (pendingContacts) { nav.navigate("contacts"); home.pendingContacts.value = false } }

    Scaffold(containerColor = Ink) { pad ->
        Box(Modifier.padding(pad)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(session, home, world, memory, visualhub) { nav.navigate(it) } }
                composable("controls") { ControlsScreen(home, { nav.popBackStack() }) { nav.navigate(it) } }
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
                composable("camera") { CameraViewScreen(camera) { nav.popBackStack() } }
                composable("visualhub") { VisualHubScreen(visualhub, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("world") {
                    YourWorldScreen(world, { nav.popBackStack() }) { topic ->
                        home.send("Tell me about: $topic")
                        nav.navigate("chat")
                    }
                }
                composable("recordings") { RecordingsScreen { nav.popBackStack() } }
            }
        }
    }
}
