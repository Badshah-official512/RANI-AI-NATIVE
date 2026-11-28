package com.badshah222.rani.ai.ui

import android.app.Activity
import androidx.compose.foundation.background // FIX (CI run #46): missing import — .background() used in InAppLiveBar (line 221)
import androidx.compose.foundation.border // FIX (CI run #46): missing import — .border() used in InAppLiveBar (line 222); added now to pre-empt the next error in the same chain
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip // FIX (CI run #45): missing import — .clip() used in InAppLiveBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.badshah222.rani.ai.MainActivity
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.LiveState
import com.badshah222.rani.ai.features.onboarding.OnboardingScreen
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
import com.badshah222.rani.ai.features.tools.BriefingScreen
import com.badshah222.rani.ai.features.tools.CallLogsScreen
import com.badshah222.rani.ai.features.tools.CvScreen
import com.badshah222.rani.ai.features.tools.DostScreen
import com.badshah222.rani.ai.features.tools.FocusScreen
import com.badshah222.rani.ai.features.tools.FreeToolsHubScreen
import com.badshah222.rani.ai.features.tools.FreeToolsViewModel
import com.badshah222.rani.ai.features.tools.FunBoxScreen
import com.badshah222.rani.ai.features.tools.HisaabScreen
import com.badshah222.rani.ai.features.tools.LyricsScreen
import com.badshah222.rani.ai.features.tools.MaloomatScreen
import com.badshah222.rani.ai.features.tools.ModesScreen
import com.badshah222.rani.ai.features.tools.NamazScreen
import com.badshah222.rani.ai.features.tools.NewsScreen
import com.badshah222.rani.ai.features.tools.PinScreen
import com.badshah222.rani.ai.features.tools.RemindersScreen
import com.badshah222.rani.ai.features.tools.ShortenerScreen
import com.badshah222.rani.ai.features.tools.TikTokScreen
import com.badshah222.rani.ai.features.tools.VoiceStudioScreen
import com.badshah222.rani.ai.features.tools.WeatherScreen
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
    // v1.8.0 §20.1.4 — the call lives on the Home hero now, so the
    // floating orb tap ("G Babu" wake) navigates to "home", not "voice".
    // Reset after navigating so it fires once per tap.
    val wakeVoice by MainActivity.wakeVoiceFlow.collectAsState()
    LaunchedEffect(wakeVoice) {
        if (wakeVoice) {
            MainActivity.wakeVoiceFlow.value = false
            nav.navigate("home") { launchSingleTop = true }
        }
    }
    // ViewModels are created here (nav-graph scope) so Home state survives opening Settings/Help.
    val home: HomeViewModel = viewModel(); val settings: SettingsViewModel = viewModel(); val history: HistoryViewModel = viewModel(); val browser: BrowserViewModel = viewModel(); val screen: ScreenViewModel = viewModel(); val secret: SecretChatViewModel = viewModel(); val contacts: ContactsViewModel = viewModel()
    val tools: AiToolsViewModel = viewModel(); val webforge: WebForgeViewModel = viewModel(); val coder: CoderViewModel = viewModel()
    val freetools: FreeToolsViewModel = viewModel()
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
            // v3.0.0 §23.2 Fix 4 — in-app LIVE bar: while a call is live and
            // the user is on any screen other than Home, a slim dot + status
            // + caption strip sits above the NavHost. NO End button (the
            // hero pill + dot-field carry the state). Tapping the bar
            // returns to Home, where the hero slot shows the same call.
            val liveActive by LiveState.active.collectAsState()
            val liveState by LiveState.state.collectAsState()
            val liveCaption by LiveState.caption.collectAsState()
            val currentEntry by nav.currentBackStackEntryAsState()
            val route = currentEntry?.destination?.route
            Column(Modifier.fillMaxSize()) {
                if (liveActive && route != "home" && route != "onboarding") {
                    InAppLiveBar(
                        status = when (liveState) {
                            "speaking" -> "RANI is speaking"
                            "listening" -> "RANI is listening"
                            "thinking" -> "RANI is thinking"
                            "connecting" -> "Connecting…"
                            else -> "RANI is listening"
                        },
                        caption = liveCaption,
                        onTap = { nav.navigate("home") { launchSingleTop = true } },
                    )
                }
                // v3.0.0 §23.1 item 5: the Live page asked to start screen
                // sharing — open the Screen Share screen (system consent).
                val screenReq by LiveState.screenShareRequested.collectAsState()
                LaunchedEffect(screenReq) {
                    if (screenReq) {
                        LiveState.screenShareRequested.value = false
                        nav.navigate("screen")
                    }
                }
                Box(Modifier.weight(1f)) {
            // v1.8.0 §20.3: signed-in + onboarding not done → the onboarding
            // runs first (one question per screen); otherwise Home.
            val appCtx = LocalContext.current.applicationContext
            val store = remember { (appCtx as RaniApp).container.store }
            // v3.0.0 §23.1 item 30: App Lock — when ON, the PIN screen
            // shows FIRST (in-memory unlock flag; the PIN itself is never
            // stored, only its SHA-256 hash).
            var unlocked by remember { mutableStateOf(false) }
            if (store.appLockEnabled && !unlocked) {
                PinScreen { unlocked = true }
            } else {
            NavHost(nav, startDestination = if (store.onboardingDone) "home" else "onboarding") {
                composable("onboarding") {
                    OnboardingScreen(onDone = {
                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                    })
                }
                composable("home") { HomeScreen(session, home, world, memory, visualhub) { nav.navigate(it) } }
                composable("controls") { ControlsScreen(home, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("chat") { ChatScreen(home, { nav.popBackStack() }, { nav.navigate("voice") }) }
                composable("voice") { VoiceScreen(home) { nav.popBackStack() } }
                composable("quick") { QuickActionsScreen(home, { nav.popBackStack() }, { nav.navigate(it) }) }
                composable("browser") { BrowserScreen(home, browser) { nav.popBackStack() } }
                composable("screen") { ScreenShareScreen(screen) { nav.popBackStack() } }
                composable("secret") { SecretChatScreen(secret, { nav.popBackStack() }, { nav.navigate(it) }) }
                composable("settings") { SettingsScreen(session, settings, { nav.popBackStack() }, { nav.navigate("permissions") }, { nav.navigate("onboarding") }) }
                composable("help") { HelpScreen { nav.popBackStack() } }
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
                // v3.0.0 §23.1 P2 — free-tools suite.
                composable("freetools") { FreeToolsHubScreen(freetools, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("weather") { WeatherScreen(freetools) { nav.popBackStack() } }
                composable("news") { NewsScreen(freetools) { nav.popBackStack() } }
                composable("namaz") { NamazScreen(freetools) { nav.popBackStack() } }
                composable("focus") { FocusScreen(freetools) { nav.popBackStack() } }
                composable("modes") { ModesScreen(freetools, home, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("briefing") { BriefingScreen(freetools) { nav.popBackStack() } }
                composable("calllogs") { CallLogsScreen(freetools) { nav.popBackStack() } }
                composable("reminders") { RemindersScreen(freetools) { nav.popBackStack() } }
                composable("hisaab") { HisaabScreen(freetools) { nav.popBackStack() } }
                composable("tiktok") { TikTokScreen(freetools) { nav.popBackStack() } }
                composable("lyrics") { LyricsScreen(freetools) { nav.popBackStack() } }
                composable("maloomat") { MaloomatScreen(freetools) { nav.popBackStack() } }
                composable("funbox") { FunBoxScreen(freetools) { nav.popBackStack() } }
                composable("shortener") { ShortenerScreen(freetools) { nav.popBackStack() } }
                composable("cv") { CvScreen(freetools) { nav.popBackStack() } }
                composable("voicestudio") { VoiceStudioScreen(freetools, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("dost") { DostScreen(freetools) { nav.popBackStack() } }
            }
            } // else — App Lock gate
                } // Box(weight(1f)) — NavHost
            } // Column — call bar + NavHost
        }
    }
}

/**
 * v3.0.0 §23.2 Fix 4 — slim in-app LIVE bar above the NavHost (renamed
 * from InAppCallBar to InAppLiveBar). Stonic styling (dark panel, violet
 * stroke), English only, 44 dp touch targets. Dot + status + caption only
 * — no End button, no "on the call" wording. The user's proof, on every
 * screen, that the Live session is still alive.
 */
@Composable
private fun InAppLiveBar(status: String, caption: String, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xE6171130))
            .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF8B3DFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 0.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
            drawCircle(color = androidx.compose.ui.graphics.Color(0xFF4ADE80))
        }
        Text(status, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (caption.isNotBlank()) {
            Text(
                caption, Modifier.weight(1f), color = Mute,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}
