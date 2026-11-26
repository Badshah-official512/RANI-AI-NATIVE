package com.badshah222.rani.ai.features.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.data.voice.LiveSession
import com.badshah222.rani.ai.ui.character.CharState
import com.badshah222.rani.ai.ui.character.RaniCharacter
import com.badshah222.rani.ai.ui.orb.VoiceOrb
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The full-screen voice call. Primary engine: Gemini Live — a real-time conversation
 * in a natural human voice, with the full-figure character on screen and a small
 * voice-pulse orb as the level indicator. If Live is unavailable for this
 * phone/key, it quietly falls back to the classic listen → think → speak mode
 * and says so once, instead of looping errors. The voice-print gate runs once
 * at the start (requestLive / requestCall) — never on every turn.
 */
@Composable
fun VoiceScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val classicState by vm.charState.collectAsState(); val classicLevel by vm.micLevel.collectAsState()
    val partial by vm.partial.collectAsState()
    val liveState by vm.live.state.collectAsState(); val liveLevel by vm.live.level.collectAsState()
    val liveUser by vm.live.userText.collectAsState(); val liveAssistant by vm.live.assistantText.collectAsState()
    val liveError by vm.live.error.collectAsState(); val muted by vm.live.muted.collectAsState()
    val attemptInfo by vm.live.attemptInfo.collectAsState(); val liveLastError by vm.live.lastError.collectAsState()
    val fallback by vm.liveFallback.collectAsState()
    val emotion by vm.emotion.collectAsState()
    val inCall by vm.inCall.collectAsState(); val callMuted by vm.callMuted.collectAsState()

    var liveMode by remember { mutableStateOf(vm.liveAvailable()) }
    var started by remember { mutableStateOf(false) }

    fun begin() {
        if (started) return; started = true
        // v1.4.0 — NATIVE call only (boss's order: no web page). The Live call
        // runs on the native Java-WebSocket engine (LiveSession) — a raw socket
        // inside the APK, no web page at all. The WebView path is removed.
        if (liveMode) vm.startLiveSession() else vm.requestCall()
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else vm.status.value = "Microphone permission denied. Open Permissions to enable it."
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Live could not run → switch to classic mode once, no error loop.
    LaunchedEffect(fallback) {
        if (fallback != null && liveMode) {
            liveMode = false
            vm.status.value = fallback!!
            if (started) { vm.beginCall(); vm.startListening() }
        }
    }
    // Classic call loop: if the recognizer drops, reopen the mic at once — the call stays
    // alive, listening and replying turn by turn, until the Boss himself presses End.
    LaunchedEffect(classicState, liveMode) {
        if (!liveMode && classicState == CharState.Error && inCall && !callMuted) {
            delay(900); vm.dismissError()
            if (inCall && !callMuted) vm.startListening()
        }
    }

    val orbState = if (liveMode) when (liveState) {
        LiveSession.State.Connecting -> CharState.Thinking
        LiveSession.State.Listening -> CharState.Listening
        LiveSession.State.Thinking -> CharState.Thinking
        LiveSession.State.Speaking -> CharState.Speaking
        LiveSession.State.Error -> CharState.Error
        LiveSession.State.Idle -> CharState.Idle
    } else classicState
    val orbLevel = if (liveMode) liveLevel else classicLevel
    val name = vm.assistantName
    val statusText = if (liveMode) when (liveState) {
        LiveSession.State.Connecting -> "Connecting…"
        LiveSession.State.Listening -> "$name is listening…"
        LiveSession.State.Thinking -> "$name is thinking…"
        LiveSession.State.Speaking -> "$name is speaking…"
        LiveSession.State.Error -> liveError ?: "Live voice stopped"
        LiveSession.State.Idle -> "$name is ready"
    } else when (classicState) {
        CharState.Listening -> "$name is listening…"
        CharState.Thinking -> "$name is thinking…"
        CharState.Speaking -> "$name is speaking…"
        CharState.Working -> "$name is working…"
        CharState.Error -> "Something went wrong"
        CharState.Idle -> "$name is ready"
    }
    val transcript = if (liveMode) {
        when {
            liveAssistant.isNotEmpty() -> liveAssistant
            liveUser.isNotEmpty() -> "You: $liveUser"
            else -> ""
        }
    } else partial

    val stars = remember { List(70) { Offset(Random.nextFloat(), Random.nextFloat()) to Random.nextFloat() } }
    val driftT = rememberInfiniteTransition(label = "drift")
    val drift by driftT.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val floatY by driftT.animateFloat(-10f, 10f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "floatY")
    val spin by driftT.animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val rgb = rememberRgbColor()
    var burst by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Panel2, Ink))).statusBarsPadding().navigationBarsPadding()) {
        AmbientLights(Modifier.fillMaxSize())
        // Edge glow: the whole screen rim lights up while the assistant is listening.
        val glowOn = if (liveMode) liveState == com.badshah222.rani.ai.data.voice.LiveSession.State.Listening else classicState == CharState.Listening
        if (glowOn) {
            Box(Modifier.fillMaxSize().border(3.dp, Violet.copy(alpha = 0.45f + 0.3f * kotlin.math.abs(kotlin.math.sin(spin * Math.PI / 180.0)).toFloat()), RoundedCornerShape(28.dp)))
        }
        Canvas(Modifier.fillMaxSize()) {
            stars.forEach { (p, a) ->
                val y = (p.y + drift * (0.03f + a * 0.06f)) % 1f
                drawCircle(Color.White.copy(alpha = 0.10f + a * 0.15f), radius = 1.2f + a * 1.6f, center = Offset(p.x * size.width, y * size.height))
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onBack) { Icon(Icons.Default.KeyboardArrowDown, "Minimize") }
            }
            Spacer(Modifier.weight(0.7f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Antigravity orbit system: two tilted rings + satellite sparks that swell with the voice.
                val emoColor = emotion.composeColor
                Canvas(Modifier.size(340.dp).offset(y = floatY.dp)) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val baseR = size.minDimension / 2f * 0.86f
                    val pulse = 1f + orbLevel * 0.10f
                    rotate(degrees = spin, pivot = Offset(cx, cy)) {
                        drawArc(emoColor.copy(alpha = 0.55f), 20f, 130f, false, topLeft = Offset(cx - baseR * pulse, cy - baseR * 0.62f * pulse), size = Size(baseR * 2 * pulse, baseR * 1.24f * pulse), style = Stroke(width = 3f))
                        drawArc(CyanAcc.copy(alpha = 0.4f), 210f, 110f, false, topLeft = Offset(cx - baseR * pulse, cy - baseR * 0.62f * pulse), size = Size(baseR * 2 * pulse, baseR * 1.24f * pulse), style = Stroke(width = 2f))
                    }
                    rotate(degrees = -spin * 0.7f, pivot = Offset(cx, cy)) {
                        drawCircle(VioletSoft.copy(alpha = 0.30f), radius = baseR * 1.02f * pulse, center = Offset(cx, cy), style = Stroke(width = 1.5f))
                    }
                    for (i in 0 until 10) {
                        val ang = Math.toRadians((spin * (0.7 + i * 0.06) + i * 36.0)) 
                        val r = baseR * (0.98f + 0.06f * kotlin.math.sin(ang * 2)) * pulse
                        drawCircle(if (i % 2 == 0) emoColor else CyanAcc, radius = 2.5f + orbLevel * 4f, center = Offset(cx + (r * kotlin.math.cos(ang)).toFloat(), cy + (r * 0.66f * kotlin.math.sin(ang)).toFloat()))
                    }
                }
                TapLightBurst(burst, rgb, Modifier.size(300.dp))
                // The character, full figure (never circular) — the orb is only the
                // small voice-pulse indicator below the status text.
                RaniCharacter(
                    orbState, orbLevel, vm.lowPower,
                    Modifier.fillMaxWidth().height(380.dp).offset(y = floatY.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { burst++ },
                    fullFigure = true,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VoiceOrb(orbState, orbLevel, vm.lowPower, Modifier.size(56.dp).clip(CircleShape))
            }
            Spacer(Modifier.height(10.dp))
            Text(statusText, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.titleMedium)
            Text(
                if (liveMode) "Real voice · Gemini Live" else "Classic voice mode",
                Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.bodySmall,
            )
            // App version on the call screen — proves which build is installed
            // (v1.1.0+ = BADSHAH pooled-key system; older = own key only).
            Text(
                "RANI AI GF v${com.badshah222.rani.ai.BuildConfig.VERSION_NAME}",
                Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.bodySmall,
            )
            // While connecting, show exactly which model is being tried — and the
            // real reason the last try failed — so a stuck call never looks silent.
            if (liveMode && liveState == LiveSession.State.Connecting) {
                if (attemptInfo.isNotEmpty()) Text(attemptInfo, Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.bodySmall)
                liveLastError?.let { Text("Last try: $it", Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, color = Bad, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            // The emotion she is speaking in right now — visible on the call.
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Panel.copy(alpha = 0.85f))
                        .border(1.dp, emotion.composeColor.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(emotion.composeColor))
                    Text("Emotion · ${emotion.label}", style = MaterialTheme.typography.labelMedium, color = emotion.composeColor)
                }
            }
            // Live equalizer — bars dance with the voice level while talking/listening.
            if (orbState == CharState.Speaking || orbState == CharState.Listening) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 5) {
                        val wave = kotlin.math.abs(kotlin.math.sin(Math.toRadians((spin * 2 + i * 38).toDouble()))).toFloat()
                        val h = 6f + orbLevel * 20f * wave + 4f * wave
                        Box(Modifier.padding(horizontal = 3.dp).size(5.dp, h.dp).background(if (i % 2 == 0) emotion.composeColor else CyanAcc, RoundedCornerShape(3.dp)))
                    }
                }
            }
            if (!liveMode && fallback != null) {
                Text(
                    "Why Live voice did not start: $fallback",
                    Modifier.fillMaxWidth().padding(top = 10.dp).background(Bad.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).padding(10.dp),
                    textAlign = TextAlign.Center, color = Bad, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (transcript.isNotEmpty()) Text(transcript, Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = TextAlign.Center, color = VioletSoft, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                if (liveMode) {
                    ControlButton(if (muted) Icons.Default.Mic else Icons.Default.MicOff, if (muted) "Unmute" else "Mute", Panel2, Color.White) { vm.live.setMuted(!muted) }
                    ControlButton(Icons.Default.CallEnd, "End", Bad, Color.White, big = true) { vm.stopLiveSession(); onBack() }
                } else {
                    ControlButton(if (callMuted) Icons.Default.Mic else Icons.Default.MicOff, if (callMuted) "Unmute" else "Mute", Panel2, Color.White) { vm.setCallMuted(!callMuted) }
                    ControlButton(Icons.Default.CallEnd, "End", Bad, Color.White, big = true) { vm.endCall(); onBack() }
                }
                ControlButton(Icons.Default.KeyboardArrowDown, "Minimize", Panel2, Color.White) { onBack() }
            }
        }
    }
    ActionDialogs(vm)
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, tint: Color, big: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledIconButton(onClick, Modifier.size(if (big) 72.dp else 60.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg, contentColor = tint), shape = CircleShape) {
            Icon(icon, label, Modifier.size(if (big) 30.dp else 24.dp))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = Mute)
    }
}
