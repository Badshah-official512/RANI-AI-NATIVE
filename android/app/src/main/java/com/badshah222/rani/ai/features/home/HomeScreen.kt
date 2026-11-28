package com.badshah222.rani.ai.features.home

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.AwarenessCamera
import com.badshah222.rani.ai.core.AwarenessMonitor
import com.badshah222.rani.ai.core.CallEngineHolder
import com.badshah222.rani.ai.core.FarewellDetector
import com.badshah222.rani.ai.core.FloatingOrbService
import com.badshah222.rani.ai.core.LiveState
import com.badshah222.rani.ai.core.RaniAccessibilityService
import com.badshah222.rani.ai.data.phone.MessageSender
import com.badshah222.rani.ai.data.phone.PhoneResult
import com.badshah222.rani.ai.data.phone.SendOutcome
import com.badshah222.rani.ai.data.ai.*
import com.badshah222.rani.ai.domain.*
import com.badshah222.rani.ai.features.auth.SessionViewModel
import com.badshah222.rani.ai.features.memory.MemoryViewModel
import com.badshah222.rani.ai.features.visualhub.VisualHubViewModel
import com.badshah222.rani.ai.features.world.YourWorldViewModel
import com.badshah222.rani.ai.ui.character.CharState
import com.badshah222.rani.ai.ui.character.RaniCharacter
import com.badshah222.rani.ai.ui.orb.VoiceOrb
import com.badshah222.rani.ai.ui.stonic.CircuitMemory
import com.badshah222.rani.ai.ui.stonic.CircuitsPanel
import com.badshah222.rani.ai.ui.stonic.RaniAvatar
import com.badshah222.rani.ai.ui.stonic.StonicSection
import com.badshah222.rani.ai.ui.stonic.StonicStatusBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    private val uid get() = c.auth.current?.uid.orEmpty()

    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val partial = MutableStateFlow("")
    /** State word for the Stonic status bar (Ready / Listening / Thinking / Speaking / Working). Never echoes reply text. */
    val status = MutableStateFlow("Ready")
    val pending = MutableStateFlow<PhoneAction?>(null)        // waiting for user confirmation (calls / messages)
    val needPermission = MutableStateFlow<Pair<String, String>?>(null)
    val pickContact = MutableStateFlow(false)
    val working = MutableStateFlow(false)
    val emotion = MutableStateFlow(Emotions.fromId("warm"))
    val lowPower get() = c.store.lowPower
    /** The girl assistant (RANI) is the face when the user picked the girl avatar, or the Girlfriend personality. */
    val girlMode: Boolean get() = c.store.avatarChoice == "girl" || c.store.personalityId == "girlfriend"
    /**
     * Time-aware English greeting for the home screen — no pet name on screen
     * ("Babu Jaan" is voice-only). On a special day she wishes you first.
     */
    val greeting: String get() {
        val day = specialDayToday()
        if (day != null) return day
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }
    private fun specialDayToday(): String? {
        return try {
            val now = java.util.Calendar.getInstance()
            val m = now.get(java.util.Calendar.MONTH) + 1
            val d = now.get(java.util.Calendar.DAY_OF_MONTH)
            val arr = org.json.JSONArray(c.store.specialDaysJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optInt("month") == m && o.optInt("day") == d) return "Happy ${o.optString("name")}!"
            }
            null
        } catch (e: Exception) { null }
    }
    val assistantName: String get() = c.store.assistantName
    val avatarChoice: String get() = c.store.avatarChoice
    val listening = c.speech.listening; val micLevel = c.speech.level; val speaking = c.tts.speaking

    // ---- Stonic top-bar controls (Floating + Awareness) ----
    val awarenessOn = MutableStateFlow(c.store.awarenessEnabled)
    /** v1.9.0 §21.3 — truthful camera state from AwarenessCamera itself:
     *  "off" | "starting" | "seeing" | "failed". Never a stuck ON. */
    val awarenessState = AwarenessCamera.visionState
    val awarenessError = AwarenessCamera.visionError
    /** Set when the Awareness toggle must ask for the camera permission first. */
    val needCameraPermission = MutableStateFlow(false)
    val floatingOn = MutableStateFlow(c.store.floatingOrbEnabled)
    /** Set when the overlay permission must be granted before the floating orb can start. */
    val needOverlayPermission = MutableStateFlow(false)
    /** Set when a live call is minimized without the overlay permission — the
     *  floating call window cannot appear until the user grants it. */
    val needCallOverlayPermission = MutableStateFlow(false)
    /** The overlay-permission ask for a call fires at most once per call, so
     *  minimizing twice does not nag the boss with the Settings screen. */
    private var callOverlayAsked = false
    /** "checking" while a voice-print sample is being verified. */
    val voiceCheck = MutableStateFlow<String?>(null)
    private var callStartedAt = 0L

    fun setAwareness(v: Boolean) {
        // v1.9.0 §21.3 — ON means the front camera streams and she sees.
        // The toggle is the preference; the truthful state (OFF / STARTING /
        // SEEING / FAILED) comes from the camera itself (AwarenessCamera).
        if (!v) {
            stopVision()
            c.store.awarenessEnabled = false
            AwarenessMonitor.enabled = false
            awarenessOn.value = false
            viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "awareness", mapOf("enabled" to false)) } }
            return
        }
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // The toggle first requests the camera (same one-time pattern as
            // onboarding step 3). Denial leaves the toggle OFF with the
            // reason shown inline — never a stuck ON.
            needCameraPermission.value = true
            return
        }
        c.store.awarenessEnabled = true
        AwarenessMonitor.enabled = true
        awarenessOn.value = true
        viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "awareness", mapOf("enabled" to true)) } }
        if (LiveState.active.value) startVision()
    }

    /** Result of the one-time camera-permission ask for Awareness. */
    fun onCameraPermissionResult(granted: Boolean) {
        needCameraPermission.value = false
        if (!granted) {
            c.store.awarenessEnabled = false
            AwarenessMonitor.enabled = false
            awarenessOn.value = false
            return
        }
        c.store.awarenessEnabled = true
        AwarenessMonitor.enabled = true
        awarenessOn.value = true
        if (LiveState.active.value) startVision()
    }

    /** v1.9.0 §21.3 — start the front-camera stream (frames only flow
     *  while a Live call is live). The toggle stays ON as the preference
     *  and re-arms on the next call. */
    private fun startVision() {
        val activity = CallEngineHolder.activity ?: return
        AwarenessCamera.start(activity) { b64 ->
            if (LiveState.active.value && awarenessOn.value) {
                CallEngineHolder.engine?.sendCameraFrame(b64)
            }
        }
    }

    private fun stopVision() = AwarenessCamera.stop()

    fun setFloating(v: Boolean) {
        val ctx = getApplication<Application>()
        if (v) {
            if (Settings.canDrawOverlays(ctx)) {
                FloatingOrbService.start(ctx)
                c.store.floatingOrbEnabled = true
                floatingOn.value = true
            } else if (c.store.onboardingDone) {
                // v1.8.0 §20.6.8: the onboarding (step 7) already asks for
                // the overlay on first run — never double-ask. The one-time
                // ask below only fires for users past onboarding.
                needOverlayPermission.value = true
            }
        } else {
            // S-6 (same rule as setLiveVoice): never kill the floating call
            // card mid-call — the service is left running while a call is
            // active and stops when the call ends.
            if (!LiveState.active.value) FloatingOrbService.stop(ctx)
            c.store.floatingOrbEnabled = false
            floatingOn.value = false
        }
    }

    /** Called after the user returns from the overlay-permission screen. */
    fun onOverlayGranted() {
        needOverlayPermission.value = false
        val ctx = getApplication<Application>()
        if (Settings.canDrawOverlays(ctx)) {
            FloatingOrbService.start(ctx)
            c.store.floatingOrbEnabled = true
            floatingOn.value = true
        }
    }

    /** Minimize during a live call: with the overlay permission the floating
     *  service starts at once so the call card appears over other apps;
     *  without it, ask for it once (one tap in Settings) so the floating
     *  call window can appear — otherwise the minimized call would be
     *  invisible and the boss could only return via the notification.
     *  B-1: the live call is LiveState (the WebView page), NOT the dead
     *  HttpLiveSession — this is also called from VoiceScreen and, via the
     *  page's Minimize button, from RaniLiveActivity itself. */
    fun minimizeCall() {
        if (LiveState.active.value) {
            val ctx = getApplication<Application>()
            if (Settings.canDrawOverlays(ctx)) {
                FloatingOrbService.start(ctx)
            } else if (!callOverlayAsked && c.store.onboardingDone) {
                // v1.8.0 §20.6.8: gated on onboardingDone — the onboarding
                // (step 7) already asks for the overlay on first run.
                callOverlayAsked = true
                needCallOverlayPermission.value = true
            }
        }
    }

    /** Called after the user returns from the overlay-permission screen for a call. */
    fun onCallOverlayGranted() {
        needCallOverlayPermission.value = false
        val ctx = getApplication<Application>()
        if (Settings.canDrawOverlays(ctx)) {
            FloatingOrbService.start(ctx)
            // NOTE: floatingOrbEnabled is NOT set here — the service runs only
            // for this call; RaniLiveActivity stops it again when the call
            // ends and neither Live Voice nor the Floating orb wants it.
        }
    }

    // ---- Controls screen toggles (every switch, one by one) ----
    val liveOn = MutableStateFlow(c.store.liveEnabled)
    fun setLive(v: Boolean) { c.store.liveEnabled = v; liveOn.value = v }

    // ---- v1.7.0 §19.2 "Live Voice" — auto-connect on app open (§19.1) ----
    val liveVoiceOn = MutableStateFlow(c.store.autoVoiceEnabled)
    fun setLiveVoice(v: Boolean) {
        c.store.autoVoiceEnabled = v
        liveVoiceOn.value = v
        val ctx = getApplication<Application>()
        if (!v) {
            // OFF hides the floating avatar immediately (the service gates
            // the orb on Live Voice). A live call is NOT killed — it only
            // stops future auto-connects. S-6: the service is left running
            // while a call is active so the floating call card survives;
            // RaniLiveActivity stops it when the call ends if nothing
            // wants it. When no call is active, stop it at once.
            if (!LiveState.active.value) FloatingOrbService.stop(ctx)
        } else if (Settings.canDrawOverlays(ctx)) {
            // ON (re)starts the service for the avatar/call card — the
            // Floating toggle is not a condition (B-1b).
            FloatingOrbService.start(ctx)
        }
    }

    // ---- v1.7.0 §19.1 auto-call on app open (once per cold start) ----
    /** Inline hero card when the auto-call cannot start: "liveoff" (Live
     *  engine off) | "nokey" (no key + signed out) | "mic" | "failed". */
    val autoCallCard = MutableStateFlow<String?>(null)
    /** In-memory guard: one auto-call attempt per cold start. Returning from
     *  the call via Back/End does NOT relaunch; rotation/recomposition does
     *  not relaunch. */
    private var autoCallAttempted = false
    fun maybeAutoCall(micGranted: Boolean) {
        if (autoCallAttempted) return
        if (!c.store.autoVoiceEnabled) { autoCallAttempted = true; return }
        // S-2: liveAvailable() is false for TWO different reasons — say
        // which one. Live engine OFF → the "liveoff" card (correct copy);
        // the "nokey" card is only for a genuinely missing key.
        if (!c.store.liveEnabled) { autoCallAttempted = true; autoCallCard.value = "liveoff"; return }
        if (!liveAvailable()) { autoCallAttempted = true; autoCallCard.value = "nokey"; return }
        if (!micGranted) { autoCallCard.value = "mic"; return } // Home asks once; retry on grant
        autoCallAttempted = true
        autoCallCard.value = null
        viewModelScope.launch {
            if (!voiceGateOk()) return@launch // status bar shows "Voice not recognised.", no card
            startLiveSession()
        }
    }
    /** One manual retry after a failed auto-call (§19.1.4, same model only).
     *  F5 (v1.8.0 fix): stop the stale call FIRST (idempotent — clears the
     *  hero key so the stale WebView is disposed) before starting a fresh
     *  one, so "Try again" never stacks a second socket on the old page.
     *  No double-start: the 12 s launch gate + the `active && heroLiveKey`
     *  guard in startLiveSession() still apply (the gate is cleared by the
     *  LiveState.active collector the moment the call ends, long before the
     *  user taps "Try again"). */
    fun retryAutoCall(micGranted: Boolean) { stopLiveSession(); autoCallAttempted = false; autoCallCard.value = null; maybeAutoCall(micGranted) }
    fun dismissAutoCallCard() { autoCallCard.value = null }

    /** v1.7.0: mute/unmute the Live page mic from the hero or the floating
     *  call card — direct evaluateJavascript on the hero WebView (§20.1.4),
     *  AND still broadcast so a stray RaniLiveActivity stays in sync.
     *  F2 loop guard (v1.8.0 fix): the card already sets LiveState.muted
     *  BEFORE it broadcasts, and this ViewModel's own receiver calls
     *  setLiveMuted() — rebroadcasting unconditionally would re-trigger
     *  our own receiver forever. The broadcast only goes out when the
     *  value actually changes; the page push below always runs, so the
     *  hero still mutes. */
    fun setLiveMuted(m: Boolean) {
        val changed = LiveState.muted.value != m
        LiveState.setMuted(m)
        // v1.9.0 §21.4: mute goes to the engine-held WebView (the hero no
        // longer owns one). F13: the old heroWebView fallback is deleted —
        // the field was never assigned, so it was dead code.
        CallEngineHolder.engine?.setMuted(m)
        if (changed) {
            runCatching {
                getApplication<Application>().sendBroadcast(
                    Intent(RaniLiveActivity.ACTION_SET_LIVE_MUTED).putExtra(RaniLiveActivity.EXTRA_MUTED, m)
                )
            }
        }
    }

    /** One place where the hero emotion changes — also pushes the §19.8.4
     *  glow colour to LiveState so the floating avatar ring matches, and
     *  (B-3) into the Live page while a call is live, so RANI's voice/style
     *  actually changes mid-call with no restart and no tap. */
    private fun applyEmotion(e: Emotion) {
        emotion.value = e
        LiveState.setGlowColor(Emotions.glowColor(e))
        if (LiveState.active.value) {
            val colorHex = "#%06X".format(0xFFFFFF and e.color.toInt())
            // v1.8.0 §20.1.4: push straight into the hero WebView too (the
            // broadcast below only reaches RaniLiveActivity).
            pushEmotionToHeroPage(e.id, e.label, colorHex, e.style)
            runCatching {
                getApplication<Application>().sendBroadcast(
                    Intent(RaniLiveActivity.ACTION_SET_LIVE_EMOTION)
                        .putExtra(RaniLiveActivity.EXTRA_EMOTION_ID, e.id)
                        .putExtra(RaniLiveActivity.EXTRA_EMOTION_LABEL, e.label)
                        .putExtra(RaniLiveActivity.EXTRA_EMOTION_COLOR, colorHex)
                        .putExtra(RaniLiveActivity.EXTRA_EMOTION_STYLE, e.style)
                )
            }
        }
    }

    /** v1.9.0 §20.1.4: push a newly detected emotion into the hero page —
     *  same setRaniEmotion call RaniLiveActivity.pushEmotionToPage makes.
     *  v1.9.0 §21.4: the engine holds the WebView now. F13: the dead
     *  heroWebView fallback is deleted (the field was never assigned). */
    private fun pushEmotionToHeroPage(id: String, label: String, colorHex: String, style: String) {
        val engine = CallEngineHolder.engine ?: return
        engine.pushEmotion(id, label, colorHex, style)
    }

    val screenRecordingOn = MutableStateFlow(c.store.screenRecordingEnabled)
    fun setScreenRecording(v: Boolean) { c.store.screenRecordingEnabled = v; screenRecordingOn.value = v }
    val voicePrintGateOn = MutableStateFlow(c.store.voicePrintGate)
    fun setVoicePrintGate(v: Boolean) { c.store.voicePrintGate = v; voicePrintGateOn.value = v }
    val faceLockOn = MutableStateFlow(c.store.faceLockEnabled)
    fun setFaceLock(v: Boolean) { c.store.faceLockEnabled = v; faceLockOn.value = v }
    val guardianOn = MutableStateFlow(c.store.guardianEnabled)
    fun setGuardian(v: Boolean) { c.store.guardianEnabled = v; guardianOn.value = v }
    val speakOn = MutableStateFlow(c.store.speakReplies)
    fun setSpeakReplies(v: Boolean) { c.store.speakReplies = v; speakOn.value = v }

    /**
     * Voice-print gate (a RANI extra beyond Stonic): when the gate is ON and a
     * voice-print is enrolled, a voice session starts only for a verified boss
     * voice. Anyone else hears "Sorry — I only talk to Babu Jaan." (voice) and
     * the attempt is logged as a safety alert (metadata only, no voice).
     */
    private suspend fun voiceGateOk(): Boolean {
        val gate = c.remoteConfig.voicePrintGateOn(c.store.voicePrintGate)
        if (!gate || !c.voicePrint.enrolled) return true
        voiceCheck.value = "checking"
        val (ok, _) = c.voicePrint.verify()
        voiceCheck.value = null
        if (ok) return true
        status.value = "Voice not recognised."
        viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "safety_alert", mapOf("reason" to "voiceprint_mismatch")) } }
        viewModelScope.launch { c.tts.speak("Sorry — I only talk to Babu Jaan.") }
        return false
    }

    /** Gated entry points — every voice session starts here. */
    fun requestTalk() {
        viewModelScope.launch { if (voiceGateOk()) startListening() }
    }
    fun requestCall() {
        viewModelScope.launch { if (voiceGateOk()) { beginCall(); startListening() } }
    }

    // ---- Gemini Live (real human voice) — the WebView call engine (v1.6.1) ----
    // S-7: no surface reads HttpLiveSession any more — that engine is dead
    // in v1.6.1 (its state never becomes active). The real call lives in
    // RaniLiveActivity + the bundled page, and its state lives in the
    // shared LiveState singleton (see the init block below).
    /** N-2: when a startLiveSession() launch began (ms, 0 = none). A second
     *  tap while a launch is in flight (key fetch can take up to 9 s) must
     *  not start a second fetch + Activity. The gate is TIME-BOUNDED
     *  (12 s, checked in startLiveSession): if the Activity died
     *  mid-launch, LiveState can stay stale-active, so an unbounded flag
     *  could swallow taps forever — after 12 s the gate is stale and the
     *  tap proceeds as a fresh start. Cleared when the call ends (the
     *  LiveState.active collector in init) or when the launch fails. */
    private var liveLaunchAtMs = 0L
    /** Live is available when enabled AND there is any key source: the user's own
     *  Gemini key, OR a Firebase sign-in (the pooled key via /api/live-key —
     *  BADSHAH's way: a BADSHAH AI account signs into RANI and needs no own key).
     *  Requiring only the own key here blocked the pooled-key call for signed-in
     *  users. */
    fun liveAvailable(): Boolean = c.store.liveEnabled && (c.store.apiKey(Provider.GEMINI) != null || c.auth.current != null)
    /** v1.8.0 §20.1.4 — NEW: the resolved key for the hero WebView. Set by
     *  startLiveSession(); clearing it disposes the hero WebView (the call
     *  ends). Null = no hero call. */
    val heroLiveKey = MutableStateFlow<String?>(null)
    /** Selected Gemini Live voice name — always one of the female voices (Aoede default). */
    val liveVoiceName: String get() = c.store.liveVoiceName
    /** Live call entry — v1.9.0 §21.4: the call runs in the persistent
     *  CallEngine (MainActivity holder), embedded in the home hero slot.
     *  startLiveSession() only resolves the key (own first, pooled ≤9 s)
     *  and starts the engine; the hero slot hosts the engine's WebView.
     *  Single attempt, gemini-3.8-live only. The MEMORY recall block
     *  (§21.2) travels as &memory=, the pet name as &pet=. If no key
     *  exists at all, the §19.1.4 inline card shows. No silent fallback
     *  to RaniLiveActivity — if the engine is missing, the call fails
     *  loudly with the "failed" card instead. */
    fun startLiveSession() {
        // N-2: a hero call is ALREADY live — NEVER start a second Live
        // socket + mic pipeline on top of it.
        if (LiveState.active.value && heroLiveKey.value != null) return
        // N-2 (double-tap): a launch is already in flight (key fetch can
        // take up to 9 s for the pooled key) — a second tap must not start
        // a second fetch. Time-bounded: a launch older than 12 s is stale
        // and the tap proceeds as a fresh start instead of being swallowed.
        val nowMs = System.currentTimeMillis()
        if (liveLaunchAtMs != 0L && nowMs - liveLaunchAtMs < 12_000) return
        liveLaunchAtMs = nowMs
        viewModelScope.launch {
            // v1.5.2 key order kept: the user's OWN Gemini key first (proven
            // to work with Google Live); the pooled key (/api/live-key,
            // Firebase-login gated) is the fallback. F4 (v1.8.0 fix): the
            // fetch runs in this ViewModel coroutine (Main) but is
            // SUSPENDING — never blocking — so the UI never freezes.
            val own = c.store.apiKey(Provider.GEMINI)
            val key = own ?: runCatching { withTimeoutOrNull(9_000) { c.worker.liveKey() } }.getOrNull()
            if (key.isNullOrBlank()) {
                val reason = "Add your Google Gemini API key in Settings to use the real voice (or sign in so the pooled key can be used)."
                c.store.lastLiveError = reason
                if (autoCallAttempted) autoCallCard.value = "failed"
                liveLaunchAtMs = 0L
                return@launch
            }
            // N-2: mark the call starting BEFORE setting the key, so a
            // second tap in the same second cannot start a second fetch
            // either. Reset on failure.
            LiveState.setState("connecting")
            val engine = CallEngineHolder.engine
            val memoryBlock = c.memory.buildRecallBlock()
            val started = engine?.start(key, c.store.liveVoiceName, memoryBlock, c.store.petName) == true
            if (!started) {
                c.store.lastLiveError = "The call engine is not ready. Try again."
                if (autoCallAttempted) autoCallCard.value = "failed"
                LiveState.setState("error")
                liveLaunchAtMs = 0L
                return@launch
            }
            heroLiveKey.value = key
            // v1.9.0 §21.3: Awareness ON re-arms the camera on every call.
            if (c.store.awarenessEnabled) startVision()
        }
    }
    /** End the Live call: the engine destroys its WebView (the ONLY place
     *  a WebView is destroyed — never on navigation), the hero key clears
     *  and the shared state ends + broadcast ACTION_END_LIVE (harmless
     *  if RaniLiveActivity is not running). Safe when the hero is already
     *  gone. The camera stream stops with the call; the Awareness
     *  preference stays ON and re-arms on the next call.
     *  F2/F3 loop guard (v1.8.0 fix): this is called BY the ACTION_END_LIVE
     *  receiver and by the engine's onEnded callback — rebroadcasting
     *  unconditionally would re-trigger our own receiver forever. The
     *  broadcast only goes out when a call was actually live (hero key set
     *  or LiveState active). */
    fun stopLiveSession() {
        val wasLive = heroLiveKey.value != null || LiveState.active.value
        // v1.9.0 §21.4: destroy the engine WebView (idempotent — a second
        // call is a no-op once the engine has nulled it).
        CallEngineHolder.engine?.end()
        stopVision()
        heroLiveKey.value = null
        LiveState.onEnded()
        if (wasLive) {
            runCatching {
                getApplication<Application>().sendBroadcast(Intent(RaniLiveActivity.ACTION_END_LIVE))
            }
        }
    }
    private var convoId: String? = null
    private var lastAction: PhoneAction? = null

    /** F2 (v1.8.0 fix): the floating call card's Mute/End broadcasts had NO
     *  receiver in the hero path — the ONLY receivers were in
     *  RaniLiveActivity, which nothing launches any more (zero call sites),
     *  so the card's Mute/End did nothing. These route the broadcasts to
     *  the HERO (setLiveMuted / stopLiveSession). Registered on the
     *  application context in init, unregistered in onCleared().
     *  RaniLiveActivity's own receivers stay as-is — both firing on one
     *  broadcast is harmless (the loop guards in setLiveMuted /
     *  stopLiveSession keep a broadcast from re-triggering this receiver). */
    private val liveMuteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RaniLiveActivity.ACTION_SET_LIVE_MUTED) {
                setLiveMuted(intent.getBooleanExtra(RaniLiveActivity.EXTRA_MUTED, false))
            }
        }
    }
    private val liveEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RaniLiveActivity.ACTION_END_LIVE) stopLiveSession()
        }
    }

    val charState: StateFlow<CharState> = combine(listening, speaking, busy, working, error) { l, s, b, w, e ->
        when { e != null -> CharState.Error; l -> CharState.Listening; b -> CharState.Thinking; w -> CharState.Working; s -> CharState.Speaking; else -> CharState.Idle }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CharState.Idle)

    /** v2.0.0 §22.3 native farewell safety net (the page backstop is
     *  primary). Runs on the SAME LiveState.transcript collector that
     *  feeds memory — no new listener. A "user" turn is judged final
     *  when the speaker switches to "assistant" (the model started
     *  replying); partial transcripts that only grow are never judged.
     *  If it fires and the call is STILL active 15 s later — and she has
     *  since spoken a goodbye, or no new user speech arrived — the same
     *  end path runs (stopLiveSession). Never ends mid-sentence, never
     *  before she has had her turn to speak. Reset whenever a call ends
     *  (see the LiveState.active collector in init), so a farewell from
     *  one call can never fire the net in the next call. */
    private var farewellLastUserText = ""
    private var farewellLastSpeaker = ""
    private var farewellPrevHadFarewell = false
    private var farewellJob: kotlinx.coroutines.Job? = null
    private var farewellUserTurns = 0
    private var farewellAssistantSaidGoodbye = false

    private fun resetFarewellNet() {
        farewellJob?.cancel()
        farewellJob = null
        farewellLastUserText = ""
        farewellLastSpeaker = ""
        farewellPrevHadFarewell = false
        farewellUserTurns = 0
        farewellAssistantSaidGoodbye = false
    }

    private fun onLiveTurnForFarewell(who: String, text: String) {
        if (who == "user") {
            // A new user turn cancels a pending farewell — unless it is
            // the same turn still growing (the new text extends the old
            // one, or the old one extends the new one).
            if (farewellJob != null && text != farewellLastUserText &&
                !text.startsWith(farewellLastUserText) && !farewellLastUserText.startsWith(text)
            ) {
                farewellJob?.cancel()
                farewellJob = null
                farewellAssistantSaidGoodbye = false
            }
            farewellLastUserText = text
            farewellUserTurns++
            farewellLastSpeaker = "user"
            return
        }
        // Assistant turn.
        if (FarewellDetector.isGoodbye(text)) farewellAssistantSaidGoodbye = true
        if (farewellLastSpeaker == "user" && farewellLastUserText.isNotBlank()) {
            val turnText = farewellLastUserText
            val fired = FarewellDetector.detect(turnText, farewellPrevHadFarewell)
            val norm = FarewellDetector.normalize(turnText)
            farewellPrevHadFarewell = FarewellDetector.phraseIn(norm) != null &&
                !FarewellDetector.isQuestionOrStory(turnText, norm)
            if (fired != null && farewellJob == null) {
                val turnsAtFire = farewellUserTurns
                farewellJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(15_000)
                    // The page backstop (10 s) normally ends the call
                    // first; this net only fires if the call is STILL
                    // active and she has said goodbye, or he has said
                    // nothing since.
                    if (LiveState.active.value &&
                        (farewellAssistantSaidGoodbye || farewellUserTurns == turnsAtFire)
                    ) {
                        stopLiveSession()
                    }
                    farewellJob = null
                }
            }
        }
        farewellLastSpeaker = "assistant"
    }

    init {
        // RANI chats on the user's own keys (Gemini default). No built-in engine.
        if (uid.isNotEmpty()) messages.value = c.chats.load(uid)
        // Best-effort remote config load (fail-soft: defaults apply when the panel table is not there yet).
        c.remoteConfig.refreshAsync(viewModelScope)
        // N-2: when the Live call ends, the launch gate opens again — the
        // next tap starts a fresh call. (While a call is live,
        // startLiveSession() returns at once — no second socket.)
        // v1.9.0 §21.2: ending the call FINALIZES the conversation into
        // long-term memory (transcript + summary + facts). The engine's
        // onEnded callback routes here too, via CallEngineHolder.
        CallEngineHolder.onCallEnded = { stopLiveSession() }
        viewModelScope.launch {
            LiveState.active.collect { active ->
                if (!active) {
                    liveLaunchAtMs = 0L
                    // v2.0.0 §22.3: a call ending resets the farewell net.
                    resetFarewellNet()
                    c.memory.finalizeSession("live_call")
                }
            }
        }
        // F5 (v1.8.0 fix): a page error bridges 'error' -> LiveState "error"
        // (active=false), so the pill/caption/Mute/End vanish — but the hero
        // key stays set and the page keeps retrying invisibly behind the
        // hidden embed CSS. Show the native "failed" card. Do NOT dispose
        // the page on first error — its retry loop is still running.
        viewModelScope.launch {
            LiveState.state.collect { s -> if (s == "error") autoCallCard.value = "failed" }
        }
        // F2 (v1.8.0 fix): route the floating call card's Mute/End
        // broadcasts to the hero (see liveMuteReceiver / liveEndReceiver).
        ContextCompat.registerReceiver(
            getApplication(), liveMuteReceiver,
            IntentFilter(RaniLiveActivity.ACTION_SET_LIVE_MUTED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            getApplication(), liveEndReceiver,
            IntentFilter(RaniLiveActivity.ACTION_END_LIVE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // v1.7.0 §19.5 / S-7: the Live transcript (user speech + RANI replies,
        // fed by the page via RaniApp.onTranscript) drives the emotion DURING
        // the call — auto-detect on every user turn, no tap, no restart.
        // Manual emotionId (explicit picker choice) is the only override.
        // It also keeps the local chat history: the same speaker's growing
        // text (partial transcripts) replaces the last live line, a new
        // speaker appends. The dead HttpLiveSession.onTurn used to do this
        // and its cloud sync with it — that sync never fired in v1.6.1
        // (the engine never ran), so live turns are local-only until the
        // page can mark a turn final; the history the user sees is complete.
        viewModelScope.launch {
            var lastLiveChat: Pair<String, String>? = null
            LiveState.transcript.collect { t ->
                if (t == null || t.text.isBlank()) return@collect
                val who = if (t.who == "user") "user" else "assistant"
                // v1.9.0 §21.2: Live turns feed the SAME memory store as
                // typed chats (finalized when the call ends).
                c.memory.recordTurn("live_call", who, t.text)
                // v2.0.0 §22.3: native farewell safety net — same
                // subscription, no new listener.
                onLiveTurnForFarewell(who, t.text)
                if (who == "user" && c.store.emotionId == null) {
                    applyEmotion(Emotions.detect(t.text))
                }
                val last = lastLiveChat
                val lastMsg = messages.value.lastOrNull()
                if (last != null && last.first == who && t.text.startsWith(last.second) &&
                    lastMsg != null && lastMsg.role == who && lastMsg.content == last.second
                ) {
                    // Same speaker, text only grew (partial transcript) — the
                    // last live line is still the tail of the list, replace it.
                    messages.value = messages.value.dropLast(1) + ChatMessage(who, t.text)
                    if (uid.isNotEmpty()) c.chats.save(uid, messages.value)
                } else if (last?.first != who || last?.second != t.text) {
                    add(ChatMessage(who, t.text))
                }
                lastLiveChat = who to t.text
            }
        }
    }

    fun startListening() {
        error.value = null; c.tts.stop(); partial.value = ""; status.value = "Listening..."
        c.speech.start(c.store.language, { partial.value = it }, { partial.value = ""; send(it) }, { error.value = it; status.value = it })
    }
    fun stopListening() = c.speech.stop()
    fun dismissError() { error.value = null }

    fun send(text: String) {
        val t = text.trim(); if (t.isEmpty() || busy.value) return
        error.value = null
        add(ChatMessage("user", t))
        // v1.9.0 §21.2: typed chats feed the SAME memory store as Live calls.
        c.memory.recordTurn("chat", "user", t)
        val action = CommandParser.parse(t)
        if (action != null) { handleAction(action); return }
        viewModelScope.launch {
            busy.value = true; status.value = "Thinking..."
            try {
                if (isImageRequest(t)) { generateImage(t); return@launch }
                // Emotion (§19.5, auto-first): detect() always runs; a manual
                // pick is only an override when the user explicitly chose one
                // (emotionId != null). The panel tone no longer outranks detection.
                val emo = c.store.emotionId?.let { Emotions.fromId(it) } ?: Emotions.detect(t)
                applyEmotion(emo)
                val reply = c.ai.chat(messages.value, emo.style)
                add(ChatMessage("assistant", reply.text))
                c.memory.recordTurn("chat", "assistant", reply.text)
                status.value = "Speaking..."
                syncToCloud(t, reply)
                busy.value = false
                speak(reply.text)
            } catch (e: AiException) { error.value = e.userMessage; status.value = e.userMessage }
            catch (e: Exception) { error.value = "Something went wrong. Please try again."; status.value = error.value!! }
            finally { busy.value = false }
        }
    }

    /** Set when the user asks for the assistant's own browser; AppNav navigates, BrowserScreen consumes it. */
    val pendingBrowser = MutableStateFlow<String?>(null)
    /** Set when the user asks to share their screen; AppNav navigates to the Screen Share screen. */
    val pendingScreen = MutableStateFlow(false)
    /** Set when the user asks to see contacts; AppNav navigates to the Contacts screen. */
    val pendingContacts = MutableStateFlow(false)

    private fun handleAction(a: PhoneAction) {
        if (a is PhoneAction.OpenScreen) {
            pendingScreen.value = true
            reply("Screen share khol raha hoon, Babu Jaan. Wahan Start dabana — Android tumse permission mangega.")
            return
        }
        if (a is PhoneAction.OpenBrowser) {
            pendingBrowser.value = a.query
            reply(if (a.query.isBlank()) "Apna browser khol raha hoon, Babu Jaan." else "Browser mein '${a.query}' khol raha hoon, Babu Jaan.")
            return
        }
        if (a is PhoneAction.OpenContacts) {
            pendingContacts.value = true
            reply("Contacts khol rahi hoon, Babu Jaan — saare contacts isi phone par hain.")
            return
        }
        if (a.needsConfirmation) { pending.value = a; status.value = "Please confirm."; return }
        runAction(a)
    }
    fun confirm() { val a = pending.value ?: return; pending.value = null; runAction(a) }
    fun cancelPending() { pending.value = null; reply("Cancelled. Nothing was sent.") }

    private fun runAction(a: PhoneAction) {
        // Auto-send runs through the accessibility sender, not the intent executor.
        if (a is PhoneAction.AutoSendMessage) { sendAuto(a); return }
        lastAction = a; working.value = true
        viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "command", mapOf("action" to a.javaClass.simpleName)) } }
        when (val r = c.phone.execute(a)) {
            is PhoneResult.Done -> reply(r.message)
            is PhoneResult.Failed -> { error.value = r.message; add(ChatMessage("assistant", r.message)); status.value = r.message }
            is PhoneResult.NeedPermission -> { needPermission.value = r.permission to r.why; reply(r.why) }
            PhoneResult.LaunchContactPicker -> { pickContact.value = true; status.value = "Pick a contact." }
        }
        viewModelScope.launch { kotlinx.coroutines.delay(900); working.value = false }
    }

    /** MESSAGE TYPE + SEND (spec §14.1): after confirmation, RANI types the
     *  message and taps Send via accessibility. If Accessibility is OFF, she
     *  falls back to opening the chat with the message ready (you press send). */
    private fun sendAuto(a: PhoneAction.AutoSendMessage) {
        val ctx = getApplication<android.app.Application>()
        if (!RaniAccessibilityService.isEnabled(ctx)) {
            reply("Message bhejne ke liye Accessibility ON chahiye, Babu Jaan — Settings > Accessibility mein RANI AI ON karo. Tab tak chat khol rahi hoon, aap khud Send dabao.")
            val draft = if (a.channel == "whatsapp") PhoneAction.WhatsAppMessage(a.target, a.message) else PhoneAction.Sms(a.target, a.message)
            runAction(draft)
            return
        }
        if (a.message.isBlank()) {
            reply("Message kya likhoon, Babu Jaan? Dobara bolo — jaise \"Ali ko message likho aur bhejo: main ghar aa raha hoon\".")
            return
        }
        working.value = true
        status.value = "Sending message…"
        viewModelScope.launch {
            when (val r = MessageSender.send(ctx, a.channel, a.target, a.message)) {
                is SendOutcome.Sent -> {
                    runCatching { c.cloud.recordActivity(uid, "message_sent", mapOf("channel" to r.channel)) }
                    reply("Bhej diya, Babu Jaan — ${r.contactName} ko ${if (r.channel == "whatsapp") "WhatsApp" else "SMS"} par: \"${a.message}\"")
                }
                SendOutcome.NeedsAccessibility -> reply("Accessibility ON nahi hai, Babu Jaan — Settings > Accessibility mein RANI AI ON karo, phir dobara bolo.")
                SendOutcome.NeedsContactsPermission -> { needPermission.value = android.Manifest.permission.READ_CONTACTS to "Naam se dhoondhne ke liye contacts ki permission chahiye, Babu Jaan."; reply("Contacts ki permission chahiye, Babu Jaan.") }
                is SendOutcome.Failed -> { error.value = r.reason; reply(r.reason) }
            }
            working.value = false
        }
    }
    fun retryLastAction() { lastAction?.let { runAction(it) } }
    fun contactPicked(name: String) = reply("Selected contact: $name. Say \"call $name\" or \"send WhatsApp to $name saying ...\".")

    /** Spoken reply: the message goes to chat + voice; the status bar shows only the state word. */
    private fun reply(text: String) { add(ChatMessage("assistant", text)); status.value = "Speaking..."; viewModelScope.launch { speak(text) } }
    // ---- image generation (Gemini image model first, free Pollinations fallback) ----
    private fun isImageRequest(t: String): Boolean {
        val l = t.lowercase()
        return listOf("image banao", "image bana", "tasveer banao", "tasveer bana", "photo banao", "pic banao",
            "generate image", "create image", "make an image", "make image", "draw me", "logo banao",
            "poster banao", "wallpaper banao", "image generate", "tasveer bana do", "image bana do").any { l.contains(it) }
    }

    private fun deliverImage(url: String) {
        add(ChatMessage("assistant", "image_url:$url"))
        status.value = "Ready"
        viewModelScope.launch { speak("Image tayyar hai, Babu Jaan.") }
    }

    private fun deliverImageFile(path: String) {
        add(ChatMessage("assistant", "image_file:$path"))
        status.value = "Ready"
        viewModelScope.launch { speak("Image tayyar hai, Babu Jaan.") }
    }

    private suspend fun generateImage(prompt: String) {
        status.value = "Creating image…"
        val chosen = c.store.imageModel
        if (chosen == "pollinations") { deliverImage(Pollinations.imageUrl(prompt)); return }
        // Gemini image model (user's own key) — the picture comes back as bytes.
        try {
            val bytes = c.ai.generateImage(prompt)
            val dir = java.io.File(getApplication<android.app.Application>().cacheDir, "images").apply { mkdirs() }
            val f = java.io.File(dir, "gemini_${System.currentTimeMillis()}.jpg")
            f.writeBytes(bytes)
            runCatching { c.cloud.recordActivity(uid, "command", mapOf("tool" to "image")) }
            deliverImageFile(f.absolutePath)
            return
        } catch (e: AiException) {
            // No key / model failed — the free Pollinations image always answers.
            status.value = "Gemini could not create the image — showing a free image instead."
        }
        deliverImage(Pollinations.imageUrl(prompt))
    }

    // ---- classic voice-call loop: listen → reply → listen again, until Babu Jaan ends the call ----
    val inCall = MutableStateFlow(false)
    val callMuted = MutableStateFlow(false)
    fun beginCall() {
        inCall.value = true; callMuted.value = false; c.tts.setCallVolume(true)
        callStartedAt = System.currentTimeMillis()
        viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "voice_call", mapOf("mode" to "classic")) } }
        // The panel's call greeting opens the call (voice-only, never on screen).
        viewModelScope.launch { c.tts.speak(c.remoteConfig.callGreeting()) }
    }
    fun endCall() {
        inCall.value = false; c.tts.setCallVolume(false); stopListening(); stopSpeaking()
        val minutes = ((System.currentTimeMillis() - callStartedAt) / 60000L).coerceAtLeast(0L)
        if (callStartedAt > 0L) viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "voice_minutes", mapOf("minutes" to minutes)) } }
        callStartedAt = 0L
        status.value = "Ready"
    }
    fun setCallMuted(m: Boolean) { callMuted.value = m; if (m) stopListening() else if (inCall.value) startListening() }

    private suspend fun speak(text: String) {
        if (!c.store.speakReplies) return
        c.tts.speak(text, onDone = {
            status.value = "Ready"
            // In a call, the moment the reply finishes the mic opens again — the conversation never goes dead.
            if (inCall.value && !callMuted.value) viewModelScope.launch {
                kotlinx.coroutines.delay(250)
                if (inCall.value && !callMuted.value) startListening()
            }
        })?.let { warn -> status.value = warn }
    }
    /** Speak one message aloud on demand (the speaker button on a chat bubble). */
    fun replay(text: String) { c.tts.stop(); viewModelScope.launch { c.tts.speak(text) } }
    private fun add(m: ChatMessage) { messages.value = messages.value + m; if (uid.isNotEmpty()) c.chats.save(uid, messages.value) }

    /** Chat attachment: the user sends a photo, Gemini Vision describes it in chat. */
    fun askPhoto(jpeg: ByteArray) {
        add(ChatMessage("user", "📷 I sent a photo — what is in it?"))
        viewModelScope.launch {
            busy.value = true
            try {
                val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                val r = c.ai.vision("The user sent this photo. Describe in detail what it shows — things, people, atmosphere — and read out any text in it.", b64)
                reply(r.text)
            } catch (e: AiException) { reply(e.userMessage) }
            catch (e: Exception) { reply("I could not read the photo.") }
            finally { busy.value = false }
        }
    }

    private suspend fun syncToCloud(userText: String, r: AiReply) {
        if (uid.isEmpty()) return
        runCatching {
            val id = convoId ?: c.cloud.createConversation(uid, userText, r.provider.id, r.model).also { convoId = it }
            c.cloud.addMessage(uid, id, "user", userText, null, null)
            c.cloud.addMessage(uid, id, "assistant", r.text, r.provider.id, r.model)
            c.cloud.recordActivity(uid, "chat", mapOf("provider" to r.provider.id))
        } // best effort: local history is always kept even when cloud sync is unavailable
    }
    fun newChat() {
        // v1.9.0 §21.2: closing the chat session finalizes it into memory.
        c.memory.finalizeSession("chat")
        convoId = null; messages.value = emptyList(); if (uid.isNotEmpty()) c.chats.clear(uid); c.tts.stop()
    }
    fun stopSpeaking() = c.tts.stop()
    override fun onCleared() {
        // F2: unregister the floating-card Mute/End receivers.
        runCatching { getApplication<Application>().unregisterReceiver(liveMuteReceiver) }
        runCatching { getApplication<Application>().unregisterReceiver(liveEndReceiver) }
        if (CallEngineHolder.onCallEnded != null) CallEngineHolder.onCallEnded = null
        c.speech.stop()
    }
}

/** Short human label for the assistant's current state. */
fun stateLabel(state: CharState, status: String, partial: String): String = when {
    partial.isNotEmpty() -> partial
    state == CharState.Listening -> "I'm listening…"
    state == CharState.Speaking -> "Speaking…"
    state == CharState.Thinking -> "Thinking…"
    state == CharState.Working -> "Working…"
    else -> status
}

/**
 * Everything a phone action can pop up: the confirmation dialog for calls/messages,
 * the contacts-permission request and the contact picker. Include this once in any
 * screen that can trigger actions (Home, Chat, Voice, Quick actions).
 */
@Composable
fun ActionDialogs(vm: HomeViewModel) {
    val ctx = LocalContext.current
    val pending by vm.pending.collectAsState(); val needPerm by vm.needPermission.collectAsState(); val pick by vm.pickContact.collectAsState()
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.retryLastAction() else vm.status.value = "Without contacts access, say the phone number instead." }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        vm.pickContact.value = false
        if (uri != null) ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) vm.contactPicked(it.getString(0)) }
    }
    LaunchedEffect(pick) { if (pick) picker.launch(null) }
    LaunchedEffect(needPerm) { needPerm?.let { vm.needPermission.value = null; contactsPerm.launch(it.first) } }
    pending?.let { a ->
        AlertDialog(onDismissRequest = vm::cancelPending, title = { Text("Confirm") },
            text = { Text(when (a) { is PhoneAction.Call -> "Open the dialer for ${a.target}? You still press the call button."
                is PhoneAction.Sms -> "Prepare this SMS to ${a.target}?\n\n\"${a.message}\"\n\nYou press send yourself."
                is PhoneAction.WhatsAppMessage -> "Prepare this WhatsApp message to ${a.target}?\n\n\"${a.message}\"\n\nYou press send yourself."
                is PhoneAction.AutoSendMessage -> "RANI will type this ${if (a.channel == "whatsapp") "WhatsApp" else "SMS"} to ${a.target} and tap Send herself:\n\n\"${a.message}\"\n\nOnly after you say yes."
                else -> "Continue?" }) },
            confirmButton = { TextButton(vm::confirm) { Text("Yes, continue") } }, dismissButton = { TextButton(vm::cancelPending) { Text("Cancel") } })
    }
}

/**
 * Home — the Stonic page, stacked vertically for a phone (Stonic's desktop
 * window is 3 fixed columns; RANI scrolls the same panels so every join
 * stays visible): top bar (RANI AI GF, Floating, Awareness, avatar) →
 * greeting → full-figure character → status bar → Four Core Circuits
 * (connector lines) → Visual Hub → Your World → VOICE | NOTES → command
 * bar → quick actions. No tabs, no bottom bar.
 */
@Composable
fun HomeScreen(
    session: SessionViewModel,
    vm: HomeViewModel,
    world: YourWorldViewModel,
    memory: MemoryViewModel,
    visualhub: VisualHubViewModel,
    onNav: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val state by vm.charState.collectAsState(); val level by vm.micLevel.collectAsState()
    val status by vm.status.collectAsState(); val partial by vm.partial.collectAsState()
    val emotion by vm.emotion.collectAsState()
    val maintenance by session.maintenance.collectAsState(); val announcements by session.announcements.collectAsState()
    val awarenessOn by vm.awarenessOn.collectAsState(); val floatingOn by vm.floatingOn.collectAsState()
    val liveVoiceOn by vm.liveVoiceOn.collectAsState(); val liveOn by vm.liveOn.collectAsState()
    val autoCard by vm.autoCallCard.collectAsState()
    val liveState by LiveState.state.collectAsState()
    val liveActive by LiveState.active.collectAsState()
    val liveCaption by LiveState.caption.collectAsState()
    val liveCaptionWho by LiveState.captionWho.collectAsState()
    val liveMuted by LiveState.muted.collectAsState()
    // v1.8.0 §20.1.4: the hero key (null = no hero call).
    val heroKey by vm.heroLiveKey.collectAsState()
    // §19.8.4: the hero glow follows the auto-detected emotion's colour,
    // cross-fading over ~600 ms when the feeling changes.
    val glowColor by animateColorAsState(Emotions.glowColor(emotion), animationSpec = tween(600), label = "heroGlow")
    // Hero character state mirrors the Live call (§19.1) so the hero is never
    // a dead picture while a call is live.
    val heroState = if (liveActive) when (liveState) {
        "connecting", "thinking" -> CharState.Thinking
        "listening" -> CharState.Listening
        "speaking" -> CharState.Speaking
        "error" -> CharState.Error
        else -> CharState.Idle
    } else state
    val voiceCheck by vm.voiceCheck.collectAsState()
    val briefing by world.items.collectAsState(); val briefingBusy by world.busy.collectAsState()
    val days by memory.days.collectAsState(); val favourites by memory.favourites.collectAsState()
    var text by remember { mutableStateOf("") }
    var voiceNotesTab by remember { mutableStateOf(0) } // 0 = VOICE, 1 = NOTES (Stonic)

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.requestTalk()
    }
    // v1.9.0 §21.3: the Awareness toggle asks for the camera once; on grant
    // the front-camera stream starts, on deny the toggle stays OFF.
    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.onCameraPermissionResult(ok)
    }
    val needCameraPerm by vm.needCameraPermission.collectAsState()
    LaunchedEffect(needCameraPerm) {
        if (needCameraPerm) cameraPerm.launch(Manifest.permission.CAMERA)
    }
    val awarenessState by vm.awarenessState.collectAsState()
    val awarenessError by vm.awarenessError.collectAsState()
    // v1.9.0 §21.4: Back on Home mid-call minimises to the floating call
    // card — it does NOT end the call. Only End ends it.
    BackHandler(enabled = liveActive) { vm.minimizeCall() }
    // v1.7.0 §19.1: the mic ask for the auto-call — requested once; on grant
    // the auto-call retries, on deny the inline hero card stays.
    var micAutoRequested by remember { mutableStateOf(false) }
    val micAutoPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.maybeAutoCall(ok)
    }
    // Auto-call on cold start — once per process (HomeViewModel guard).
    LaunchedEffect(Unit) {
        vm.maybeAutoCall(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    LaunchedEffect(autoCard) {
        if (autoCard == "mic" && !micAutoRequested) {
            micAutoRequested = true
            micAutoPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val overlayPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Whichever flow asked for the permission, finish that one.
        if (vm.needCallOverlayPermission.value) vm.onCallOverlayGranted() else vm.onOverlayGranted()
    }
    val needOverlay by vm.needOverlayPermission.collectAsState()
    LaunchedEffect(needOverlay) {
        if (needOverlay) {
            runCatching {
                overlayPerm.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                )
            }
        }
    }
    // v1.5.0: minimizing a live call without the overlay permission asks for
    // it once, so the floating call window can appear over other apps.
    val needCallOverlay by vm.needCallOverlayPermission.collectAsState()
    LaunchedEffect(needCallOverlay) {
        if (needCallOverlay) {
            runCatching {
                overlayPerm.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                )
            }
        }
    }
    fun tapToTalk() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.requestTalk()
        } else {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Panel, Ink)))) {
        AmbientLights(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Top bar (Stonic §11 + §19.2): name, Floating, Live Voice, Awareness, avatar ----
            // S-5 (360 dp): the fixed content is ~400 dp, so compress
            // defensively — the title Column (weight 1f) collapses FIRST and
            // the three controls + avatar never overflow. "Floating" is
            // shortened to "Float", gaps shrink to 2 dp, label/switch gaps
            // to 4 dp, and both switch labels use labelSmall. A real 360 dp
            // render is still unverifiable in this environment and must be
            // checked on the phone (QA checklist item 10).
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = true)) {
                    Text("RANI AI GF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("YOUR VIRTUAL AI GIRLFRIEND", style = MaterialTheme.typography.labelSmall, color = Mute, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ vm.setFloating(!floatingOn) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("Float", maxLines = 1, color = if (floatingOn) CircuitMemory else Mute, fontWeight = if (floatingOn) FontWeight.Bold else FontWeight.Normal)
                }
                Spacer(Modifier.width(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Voice", style = MaterialTheme.typography.labelSmall, color = if (liveVoiceOn && liveOn) Color(0xFF4ADE80) else Mute, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = liveVoiceOn && liveOn,
                        onCheckedChange = vm::setLiveVoice,
                        enabled = liveOn,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF22C55E), uncheckedTrackColor = Panel2),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Awareness", style = MaterialTheme.typography.labelSmall, color = if (awarenessOn) Color(0xFF4ADE80) else Mute, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = awarenessOn,
                        onCheckedChange = vm::setAwareness,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF22C55E), uncheckedTrackColor = Panel2),
                    )
                }
                Spacer(Modifier.width(2.dp))
                RaniAvatar(38.dp, modifier = Modifier.clickable { onNav("about") })
            }

            maintenance?.let { Banner(it, Bad) }
            announcements.firstOrNull()?.let { Banner("${it.first}: ${it.second}", Blue) }
            if (voiceCheck == "checking") Banner("Verifying your voice…", Blue)
            // v1.9.0 §21.3: a failed camera shows the reason in plain words,
            // never a stuck ON.
            if (awarenessState == "failed") Banner(awarenessError ?: "Camera could not start.", Bad)
            if (awarenessState == "starting") Banner("Starting camera…", Blue)

            Text(vm.greeting, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = VioletSoft, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // ---- Centre: v1.8.0 §20.2 correction (2026-11-27) — the hero
            // character stays the holographic girl-form (RaniCharacter,
            // full figure); the dot field is NOT in the hero, it lives
            // only in the bottom-circle floating overlay view.
            // The transparent hero WebView (§20.1) is layered on top of
            // the girl-form in the same 320 dp box so the call runs inside
            // the hero. The §19.8 emotion glow stays behind her as the halo.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(300.dp)
                        .background(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.30f), Color.Transparent)))
                )
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    RaniCharacter(heroState, level, vm.lowPower, Modifier.fillMaxWidth().height(320.dp).clickable { tapToTalk() }, fullFigure = true, liveState = liveState, glowColor = glowColor, energy = Emotions.energy(emotion))
                    // v1.9.0 §21.4: the hero is a SLOT — the engine's
                    // WebView attaches here while Home is visible and
                    // detaches (never destroyed) on navigation.
                    if (heroKey != null) {
                        HeroLiveSlot(modifier = Modifier.fillMaxSize())
                    }
                }
            }
            // ---- §19.8.3 status pill (Stonic-style) + §19.1 caption + Mute/End ----
            // While a call is live the pill replaces the generic status bar so
            // pill and motion can never disagree; the emotion chip stays.
            if (liveActive) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LiveStatusPill(liveState)
                }
                // v1.9.0 §21.3 — "Seeing" indicator: green pulsing dot +
                // "Seeing · front camera". Tapping it opens Camera View,
                // which shows the live feed with her description.
                if (awarenessState == "seeing") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF14532D).copy(alpha = 0.85f))
                                .border(1.5.dp, Color(0xFF4ADE80), RoundedCornerShape(18.dp))
                                .clickable { onNav("camera") }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF4ADE80)))
                            Text("Seeing · front camera", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                Text(
                    when {
                        liveCaption.isNotBlank() -> (if (liveCaptionWho.isNotBlank()) "$liveCaptionWho: " else "") + liveCaption
                        liveState == "connecting" -> "Connecting to RANI…"
                        else -> "I'm listening…"
                    },
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    textAlign = TextAlign.Center, color = Color.White, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        { vm.setLiveMuted(!liveMuted) },
                        Modifier.size(96.dp, 44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = Color.White),
                    ) {
                        Icon(if (liveMuted) Icons.Default.MicOff else Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (liveMuted) "Unmute" else "Mute")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        { vm.stopLiveSession() },
                        Modifier.size(96.dp, 44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD92D20), contentColor = Color.White),
                    ) { Text("End") }
                }
            } else {
                StonicStatusBar(stateLabel(state, status, partial), emotion.composeColor)
            }
            // ---- §19.1.4 inline hero card — never a blocking screen ----
            autoCard?.let { kind ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (kind) {
                                    // S-2: the Live-engine-OFF card has its own
                                    // copy — it must never blame a missing key.
                                    "liveoff" -> "Live voice is off"
                                    "nokey" -> "Add your Gemini key to talk"
                                    "mic" -> "Microphone needed to talk — tap to allow."
                                    else -> "The call did not connect."
                                },
                                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            )
                            IconButton({ vm.dismissAutoCallCard() }) { Icon(Icons.Default.Close, "Dismiss") }
                        }
                        if (kind == "liveoff") {
                            Text("Live voice is off — turn it on in Controls.", style = MaterialTheme.typography.bodySmall, color = Mute)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ onNav("controls") }) { Text("Open Controls") }
                            }
                        }
                        if (kind == "nokey") {
                            Text("RANI talks with your free Gemini key — or sign in and use the shared key.", style = MaterialTheme.typography.bodySmall, color = Mute)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ onNav("settings") }) { Text("Add Gemini Key") }
                                // N-5 (§19.1.4): the second button the spec
                                // requires. There is NO nav route to the auth
                                // screen from Home — AppNav shows AuthScreen
                                // only when the Firebase user is null — so
                                // this signs the current session out, which
                                // drops straight into AuthScreen (email /
                                // Google sign-in). Signing in there restores
                                // the pooled-key path, so the call can start
                                // without an own key. Same mechanism as the
                                // "Sign out" button in Settings; no new auth
                                // screen invented.
                                OutlinedButton({ session.signOut() }) { Text("Sign In") }
                            }
                        }
                        if (kind == "mic") {
                            Button({
                                micAutoRequested = false
                                micAutoPerm.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Text("Allow microphone") }
                        }
                        if (kind == "failed") {
                            Text("The call did not connect. Check your internet and try again.", style = MaterialTheme.typography.bodySmall, color = Mute)
                            Button({
                                vm.retryAutoCall(
                                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                )
                            }) { Text("Try again") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
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
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VoiceOrb(heroState, level, vm.lowPower, Modifier.size(64.dp).clip(CircleShape).clickable { onNav("voice") })
            }
            // During a live call the caption above replaces this hint (§19.1).
            if (!liveActive) Text("Tap the figure or the orb to talk", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.labelMedium)

            // ---- Four Core Circuits (connector lines converge on the figure) ----
            CircuitsPanel(onCircuit = { onNav(it) })

            // ---- Visual Hub ----
            StonicSection("Visual Hub", Icons.Default.AutoAwesome, action = {
                TextButton({ onNav("visualhub") }) { Text("Open", color = VioletSoft) }
            }) {
                Text("What do you want to see?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Ask out loud. RANI draws it here as she explains.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "Starship blueprint" to "Draw a Starship blueprint and explain it",
                    "How black holes bend light" to "Draw how a black hole bends light and explain it",
                    "Fusion reactor" to "Draw a fusion reactor and explain how it works",
                    "Crewed Mars mission" to "Draw a crewed Mars mission and explain it",
                ).forEachIndexed { i, (label, prompt) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            visualhub.prompt.value = prompt
                            onNav("visualhub")
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}.", color = VioletSoft, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ---- Your World ----
            StonicSection("Your World", Icons.Default.Public, action = {
                IconButton({ world.refresh() }) { Icon(Icons.Default.Refresh, "Refresh briefing", tint = VioletSoft) }
            }) {
                Text("Three things for you today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                when {
                    briefingBusy -> Text("Building your briefing…", color = Mute, style = MaterialTheme.typography.bodySmall)
                    briefing.isEmpty() -> Text("Briefing is not ready yet — tap refresh.", color = Mute, style = MaterialTheme.typography.bodySmall)
                    else -> briefing.take(3).forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                vm.send("Tell me about: ${item.title}")
                                onNav("chat")
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Circle, null, tint = CircuitMemory, modifier = Modifier.size(8.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton({ onNav("world") }) { Text("Open Your World", color = VioletSoft) }
            }

            // ---- VOICE | NOTES (Stonic) ----
            StonicSection(if (voiceNotesTab == 0) "Voice" else "Notes", Icons.Default.Mic) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("VOICE" to 0, "NOTES" to 1).forEach { (label, idx) ->
                        Text(
                            label,
                            color = if (voiceNotesTab == idx) Color.White else Mute,
                            fontWeight = if (voiceNotesTab == idx) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (voiceNotesTab == idx) Panel2 else Color.Transparent)
                                .clickable { voiceNotesTab = idx }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (voiceNotesTab == 0) {
                    Text("SYSTEM READY", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            { tapToTalk() },
                            Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet, contentColor = Color.White),
                        ) { Icon(Icons.Default.Mic, "Talk", modifier = Modifier.size(30.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Text("Push-to-talk or hands-free.\nTap the mic and speak.", style = MaterialTheme.typography.bodyMedium, color = Mute)
                    }
                } else {
                    Text("${days.size} special days · ${favourites.size} favourites saved", style = MaterialTheme.typography.bodyMedium)
                    Text("Long-term memory lives on this phone. Add, edit or delete it anytime.", style = MaterialTheme.typography.bodySmall, color = Mute)
                    Spacer(Modifier.height(6.dp))
                    TextButton({ onNav("memory") }) { Text("Open My Memories", color = VioletSoft) }
                }
            }

            // ---- Command bar ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Ask me anything…") }, maxLines = 3, shape = RoundedCornerShape(28.dp))
                FilledIconButton({ if (text.isNotBlank()) { vm.send(text); text = ""; onNav("chat") } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet, contentColor = Color.White)) {
                    Icon(Icons.Default.Send, "Send")
                }
                FilledIconButton({ tapToTalk() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Panel2, contentColor = VioletSoft)) {
                    Icon(Icons.Default.Mic, "Talk")
                }
            }

            // ---- Quick actions (Stonic panels, English) ----
            StonicSection("Quick Actions", Icons.Default.Bolt) {
                val cards = listOf(
                    Triple(Icons.Default.Chat, "Chat", "Get instant answers") to "chat",
                    Triple(Icons.Default.Call, "Voice Call", "Talk to RANI") to "voice",
                    Triple(Icons.Default.Tune, "Controls", "Every switch, one by one") to "controls",
                    Triple(Icons.Default.Videocam, "Camera View", "See what RANI sees") to "camera",
                    Triple(Icons.Default.AutoAwesome, "Visual Hub", "Draw & explain") to "visualhub",
                    Triple(Icons.Default.Public, "Your World", "Daily briefing") to "world",
                    Triple(Icons.Default.Movie, "Recordings", "Screen recordings") to "recordings",
                    Triple(Icons.Default.Build, "AI Tools", "Summarise, translate…") to "tools",
                    Triple(Icons.Default.Language, "Website Builder", "Build a website") to "webforge",
                    Triple(Icons.Default.Code, "Coder", "Write code with AI") to "coder",
                    Triple(Icons.Default.Contacts, "Contacts", "Call / message by name") to "contacts",
                    Triple(Icons.Default.Folder, "My Files", "Images, sites, ZIPs") to "files",
                    Triple(Icons.Default.PhotoCamera, "Media", "Camera vision") to "media",
                    Triple(Icons.Default.Search, "Web Search", "Google, YouTube") to "search",
                    Triple(Icons.Default.MonitorHeart, "System Monitor", "CPU, RAM, battery") to "monitor",
                    Triple(Icons.Default.Bolt, "Quick Actions", "Phone control") to "quick",
                    Triple(Icons.Default.Lock, "Secret Chat", "Private heavy work") to "secret",
                )
                cards.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (card, route) ->
                            QuickCard(Modifier.weight(1f), card.first, card.second, card.third) { onNav(route) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    ActionDialogs(vm)
}

/**
 * v1.7.0 §19.8.3 — the Stonic-style status pill directly under the hero
 * character, driven by the same LiveState as the character animation so pill
 * and motion can never disagree. Dark panel #E6171130, 18 dp corners,
 * #8B3DFF 1.5 dp stroke, white 13 sp text. Speaking = animated waveform,
 * listening = mic icon, thinking = dots, connecting = spinner.
 */
@Composable
private fun LiveStatusPill(liveState: String) {
    val inf = rememberInfiniteTransition(label = "pillWave")
    val bar1 by inf.animateFloat(6f, 18f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pb1")
    val bar2 by inf.animateFloat(18f, 6f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pb2")
    val bar3 by inf.animateFloat(8f, 16f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "pb3")
    Row(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6171130))
            .border(1.5.dp, Color(0xFF8B3DFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (liveState) {
            "speaking" -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(18.dp)) {
                listOf(bar1, bar2, bar3).forEach { h ->
                    Box(Modifier.width(3.dp).height(h.dp).background(Color.White, RoundedCornerShape(2.dp)))
                }
            }
            "listening" -> Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(16.dp))
            "thinking" -> Icon(Icons.Default.MoreHoriz, null, tint = Color.White, modifier = Modifier.size(16.dp))
            "connecting" -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            else -> Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(
            when (liveState) {
                "speaking" -> "RANI is speaking"
                "listening" -> "RANI is listening"
                "thinking" -> "RANI is thinking"
                "connecting" -> "Connecting…"
                "error" -> "Call ended"
                else -> "RANI is ready"
            },
            color = Color.White, fontSize = 13.sp,
        )
    }
}

@Composable
private fun QuickCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Card(modifier.clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = Panel2.copy(alpha = 0.9f)), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Violet.copy(alpha = 0.28f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, title, tint = VioletSoft)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Mute)
        }
    }
}

@Composable
fun Banner(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 2.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
}
