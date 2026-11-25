package com.badshah222.rani.ai.features.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badshah222.rani.ai.domain.LiveModels
import com.badshah222.rani.ai.domain.LiveVoices
import com.badshah222.rani.ai.domain.RaniTools
import com.badshah222.rani.ai.features.settings.SettingsViewModel
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.orb.VoiceOrb
import com.badshah222.rani.ai.ui.theme.*

/**
 * MYRA VoicePage — the voice screen: orb, listen modes + hints, female voices,
 * persona fixed Girlfriend, live-model RadioList, mic sensitivity + gain,
 * live mic level, session panel. Full settings live in Settings.
 */
@Composable
fun VoiceModeScreen(home: HomeViewModel, settings: SettingsViewModel, onBack: () -> Unit) {
    val state by home.charState.collectAsState(); val level by home.micLevel.collectAsState()
    val listenMode by settings.listenMode.collectAsState(); val micSensitivity by settings.micSensitivity.collectAsState(); val micGain by settings.micGain.collectAsState()
    val liveVoiceName by settings.liveVoiceName.collectAsState(); val liveModelKey by settings.liveModelKey.collectAsState()
    val liveModel by settings.liveModel.collectAsState(); val liveEnabled by settings.liveEnabled.collectAsState()
    val liveCandidates by settings.liveCandidates.collectAsState(); val liveMsg by settings.liveMsg.collectAsState(); val liveTesting by settings.liveTesting.collectAsState()
    val lowPower by settings.lowPower.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Voice — RANI ki awaaz", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    VoiceOrb(state, level, lowPower, Modifier.size(180.dp))
                }
                Text("Persona: Girlfriend — RANI is always your girlfriend.", Modifier.fillMaxWidth(), color = VioletSoft, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Listen mode", style = MaterialTheme.typography.titleMedium, color = Gold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RaniTools.listenModes.forEach { m -> FilterChip(listenMode == m, { settings.setListenMode(m) }, { Text(RaniTools.listenLabel(m)) }) }
                        }
                        Text(RaniTools.listenHint(listenMode), color = Mute, style = MaterialTheme.typography.bodySmall)
                        Text("Live mic level", style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = VioletSoft)
                        Text("Mic sensitivity: $micSensitivity", style = MaterialTheme.typography.bodySmall)
                        Slider(micSensitivity.toFloat(), { settings.setMicSensitivity(it.toInt()) }, valueRange = 5f..120f)
                        Text("Mic gain: ${"%.1f".format(micGain)}x", style = MaterialTheme.typography.bodySmall)
                        Slider(micGain, { settings.setMicGain(it) }, valueRange = 1f..5f)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RANI ki voices — saari female", style = MaterialTheme.typography.titleMedium, color = Gold)
                        LiveVoices.female.forEach { v ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(liveVoiceName == v.name, { settings.setLiveVoice(v) })
                                Column { Text(v.name, style = MaterialTheme.typography.bodyLarge); Text(v.style, style = MaterialTheme.typography.bodySmall, color = Mute) }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live model", style = MaterialTheme.typography.titleMedium, color = Gold)
                        LiveModels.all.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(liveModelKey == m.key, { settings.setLiveModelKey(m.key) })
                                Column { Text(m.label, style = MaterialTheme.typography.bodyLarge); Text("${m.modelId} · ${m.version}", style = MaterialTheme.typography.bodySmall, color = Mute) }
                            }
                        }
                        Text("Session (live)", style = MaterialTheme.typography.titleMedium, color = Gold)
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Use Live voice on Voice Call", Modifier.weight(1f)); Switch(liveEnabled, settings::setLiveEnabled) }
                        Text("Live model: ${liveModel ?: "abhi confirm nahi"}", style = MaterialTheme.typography.bodySmall, color = if (liveModel != null) Ok else Mute)
                        Text("Session resumption is ON — if a call drops, RANI resumes from the same point.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        if (liveCandidates.isNotEmpty()) Text("Tried models: ${liveCandidates.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Mute)
                        Button(settings::testLive, enabled = !liveTesting) { if (liveTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test Live voice") }
                        liveMsg?.let { Text(it.second, color = if (it.first) Ok else Bad, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
