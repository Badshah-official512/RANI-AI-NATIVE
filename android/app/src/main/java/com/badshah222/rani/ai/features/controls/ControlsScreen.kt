package com.badshah222.rani.ai.features.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badshah222.rani.ai.features.home.HomeViewModel
import com.badshah222.rani.ai.ui.stonic.StonicSection
import com.badshah222.rani.ai.ui.theme.*

/**
 * All Controls — every switch in RANI AI, listed one by one, right after
 * sign-in. No hunting through menus: voice, safety, phone control and the
 * app itself, each row either a switch that works at once or a direct link
 * to its screen. English only, like the rest of the app.
 */
@Composable
fun ControlsScreen(vm: HomeViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    val emotion by vm.emotion.collectAsState()
    val liveOn by vm.liveOn.collectAsState()
    val floatingOn by vm.floatingOn.collectAsState()
    val awarenessOn by vm.awarenessOn.collectAsState()
    val recordingOn by vm.screenRecordingOn.collectAsState()
    val voicePrintOn by vm.voicePrintGateOn.collectAsState()
    val faceLockOn by vm.faceLockOn.collectAsState()
    val guardianOn by vm.guardianOn.collectAsState()
    val speakOn by vm.speakOn.collectAsState()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Panel, Ink)))) {
        AmbientLights(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Column {
                    Text("Controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Every switch, one by one", style = MaterialTheme.typography.bodySmall, color = Mute)
                }
            }
            Text(
                "Sign in once and everything is here — flip a switch and it works at once, or tap a row to open its screen.",
                style = MaterialTheme.typography.bodyMedium, color = Mute,
            )

            StonicSection("Voice & Live", Icons.Default.RecordVoiceOver) {
                ControlSwitch(Icons.Default.RecordVoiceOver, "Live voice", "Gemini Live real voice", liveOn, vm::setLive)
                ControlLink(Icons.Default.Mic, "Live model & voices", "Pick the Live model, voice and listen mode") { onNav("voicemode") }
                ControlLink(Icons.Default.Favorite, "Voices & emotions", "All female voices · now ${emotion.label} · 100+ emotions") { onNav("settings") }
                ControlSwitch(Icons.Default.VolumeUp, "Speak replies aloud", "RANI speaks every chat reply", speakOn, vm::setSpeakReplies)
                ControlLink(Icons.Default.Mic, "Talk to RANI", "Start a live voice session with RANI") { onNav("voice") }
                ControlLink(Icons.Default.Chat, "Chat", "Message RANI — text replies") { onNav("chat") }
            }

            StonicSection("Phone & Safety", Icons.Default.Security) {
                ControlSwitch(Icons.Default.BubbleChart, "Floating orb", "Always-on-screen orb — tap it to talk", floatingOn, vm::setFloating)
                ControlSwitch(Icons.Default.Visibility, "Awareness", "RANI watches and helps on her own", awarenessOn, vm::setAwareness)
                ControlSwitch(Icons.Default.Movie, "Screen recording", "Save what she sees — on this phone only", recordingOn, vm::setScreenRecording)
                ControlSwitch(Icons.Default.Fingerprint, "Voice-print gate", "Only your voice can start a call", voicePrintOn, vm::setVoicePrintGate)
                ControlSwitch(Icons.Default.Face, "Face lock", "Only your face unlocks RANI", faceLockOn, vm::setFaceLock)
                ControlSwitch(Icons.Default.Shield, "Screen Guardian", "Phone safety, even without your permission", guardianOn, vm::setGuardian)
                ControlLink(Icons.Default.Videocam, "Camera view", "See what RANI sees, live") { onNav("camera") }
                ControlLink(Icons.Default.ScreenShare, "Screen share", "Show RANI your screen") { onNav("screen") }
                ControlLink(Icons.Default.Accessibility, "Accessibility & permissions", "Type, send, tap — the full phone control") { onNav("permissions") }
            }

            StonicSection("App", Icons.Default.Apps) {
                ControlLink(Icons.Default.Settings, "Settings", "Keys, models, voices, session") { onNav("settings") }
                ControlLink(Icons.Default.Memory, "Memory", "Special days and favourites") { onNav("memory") }
                ControlLink(Icons.Default.Schedule, "Automations", "Tasks, reminders and routines") { onNav("automation") }
                ControlLink(Icons.Default.Movie, "Recordings", "Screen recordings RANI saved") { onNav("recordings") }
                ControlLink(Icons.Default.AutoAwesome, "Visual Hub", "Draw and explain") { onNav("visualhub") }
                ControlLink(Icons.Default.Contacts, "Contacts", "Call and message by name") { onNav("contacts") }
                ControlLink(Icons.Default.Help, "Help", "How everything works") { onNav("help") }
                ControlLink(Icons.Default.Info, "About", "RANI AI, version and credits") { onNav("about") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ControlSwitch(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel2.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, title, tint = VioletSoft, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Mute)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF22C55E), uncheckedTrackColor = Panel),
        )
    }
}

@Composable
private fun ControlLink(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel2.copy(alpha = 0.9f)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, title, tint = VioletSoft, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Mute)
        }
        Icon(Icons.Default.ChevronRight, "Open", tint = Mute)
    }
}
