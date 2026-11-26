package com.badshah222.rani.ai.features.home

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.AwarenessMonitor
import com.badshah222.rani.ai.core.FloatingOrbService
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
    val floatingOn = MutableStateFlow(c.store.floatingOrbEnabled)
    /** Set when the overlay permission must be granted before the floating orb can start. */
    val needOverlayPermission = MutableStateFlow(false)
    /** "checking" while a voice-print sample is being verified. */
    val voiceCheck = MutableStateFlow<String?>(null)
    private var callStartedAt = 0L

    fun setAwareness(v: Boolean) {
        c.store.awarenessEnabled = v
        AwarenessMonitor.enabled = v
        awarenessOn.value = v
        viewModelScope.launch { runCatching { c.cloud.recordActivity(uid, "awareness", mapOf("enabled" to v)) } }
    }

    fun setFloating(v: Boolean) {
        val ctx = getApplication<Application>()
        if (v) {
            if (Settings.canDrawOverlays(ctx)) {
                FloatingOrbService.start(ctx)
                c.store.floatingOrbEnabled = true
                floatingOn.value = true
            } else {
                needOverlayPermission.value = true
            }
        } else {
            FloatingOrbService.stop(ctx)
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

    // ---- Controls screen toggles (every switch, one by one) ----
    val liveOn = MutableStateFlow(c.store.liveEnabled)
    fun setLive(v: Boolean) { c.store.liveEnabled = v; liveOn.value = v }
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
    fun requestLive() {
        viewModelScope.launch { if (voiceGateOk()) startLiveSession() }
    }

    // ---- Gemini Live (real human voice) ----
    val live = c.live
    val liveFallback = MutableStateFlow<String?>(null)
    fun liveAvailable(): Boolean = c.store.liveEnabled && c.store.apiKey(Provider.GEMINI) != null
    fun startLiveSession() {
        liveFallback.value = null
        viewModelScope.launch {
            // Fail fast: if this key offers no Live voice model at all, do not burn
            // the whole model ladder on "Connecting…" — say so at once.
            if (c.store.liveModel == null) {
                val found = c.ai.detectLiveModel()
                if (found == null) {
                    liveFallback.value = "Live voice could not be confirmed for this Gemini key. Check the internet, then open Settings → Live Voice → Test Live voice and pick a working model."
                    return@launch
                }
            }
            val emo = c.store.emotionId?.let { Emotions.fromId(it) } ?: emotion.value
            // The panel's call greeting opens the Live call (voice-only, never on screen).
            val greeting = c.remoteConfig.callGreeting()
            c.live.start(c.ai.systemPrompt(emo.style) + "\nStart the call by greeting the user with: \"$greeting\"")
        }
    }
    fun stopLiveSession() = c.live.stop()
    private var convoId: String? = null
    private var lastAction: PhoneAction? = null

    val charState: StateFlow<CharState> = combine(listening, speaking, busy, working, error) { l, s, b, w, e ->
        when { e != null -> CharState.Error; l -> CharState.Listening; b -> CharState.Thinking; w -> CharState.Working; s -> CharState.Speaking; else -> CharState.Idle }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CharState.Idle)

    init {
        // RANI chats on the user's own keys (Gemini default). No built-in engine.
        if (uid.isNotEmpty()) messages.value = c.chats.load(uid)
        // Best-effort remote config load (fail-soft: defaults apply when the panel table is not there yet).
        c.remoteConfig.refreshAsync(viewModelScope)
        c.live.onTurn = { u, a ->
            if (c.store.emotionId == null && u.isNotEmpty()) emotion.value = Emotions.detect(u)
            if (u.isNotEmpty()) add(ChatMessage("user", u))
            if (a.isNotEmpty()) {
                add(ChatMessage("assistant", a))
                if (uid.isNotEmpty()) viewModelScope.launch {
                    runCatching {
                        val id = convoId ?: c.cloud.createConversation(uid, u, "gemini", c.store.liveModel ?: "live").also { convoId = it }
                        c.cloud.addMessage(uid, id, "user", u, null, null)
                        c.cloud.addMessage(uid, id, "assistant", a, "gemini", c.store.liveModel ?: "live")
                        c.cloud.recordActivity(uid, "chat", mapOf("provider" to "gemini", "mode" to "live"))
                    }
                }
            }
        }
        c.live.onUnavailable = { reason -> liveFallback.value = reason; c.store.lastLiveError = reason }
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
        val action = CommandParser.parse(t)
        if (action != null) { handleAction(action); return }
        viewModelScope.launch {
            busy.value = true; status.value = "Thinking..."
            try {
                if (isImageRequest(t)) { generateImage(t); return@launch }
                // Emotion: the user's own choice wins, then the panel tone, then detection.
                val emo = c.store.emotionId?.let { Emotions.fromId(it) }
                    ?: c.remoteConfig.tone()?.let { Emotions.fromId(it) }
                    ?: Emotions.detect(t)
                emotion.value = emo
                val reply = c.ai.chat(messages.value, emo.style)
                add(ChatMessage("assistant", reply.text)); status.value = "Speaking..."
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
    fun newChat() { convoId = null; messages.value = emptyList(); if (uid.isNotEmpty()) c.chats.clear(uid); c.tts.stop() }
    fun stopSpeaking() = c.tts.stop()
    override fun onCleared() { c.speech.stop() }
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
    val voiceCheck by vm.voiceCheck.collectAsState()
    val briefing by world.items.collectAsState(); val briefingBusy by world.busy.collectAsState()
    val days by memory.days.collectAsState(); val favourites by memory.favourites.collectAsState()
    var text by remember { mutableStateOf("") }
    var voiceNotesTab by remember { mutableStateOf(0) } // 0 = VOICE, 1 = NOTES (Stonic)

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.requestTalk()
    }
    val overlayPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onOverlayGranted()
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
            // ---- Top bar (Stonic §11): name, Floating, Awareness, avatar ----
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RANI AI GF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("YOUR VIRTUAL AI GIRLFRIEND", style = MaterialTheme.typography.labelSmall, color = Mute, letterSpacing = 2.sp)
                }
                TextButton({ vm.setFloating(!floatingOn) }) {
                    Text("Floating", color = if (floatingOn) CircuitMemory else Mute, fontWeight = if (floatingOn) FontWeight.Bold else FontWeight.Normal)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Awareness", style = MaterialTheme.typography.labelMedium, color = if (awarenessOn) Color(0xFF4ADE80) else Mute)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = awarenessOn,
                        onCheckedChange = vm::setAwareness,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF22C55E), uncheckedTrackColor = Panel2),
                    )
                }
                Spacer(Modifier.width(8.dp))
                RaniAvatar(38.dp, modifier = Modifier.clickable { onNav("about") })
            }

            maintenance?.let { Banner(it, Bad) }
            announcements.firstOrNull()?.let { Banner("${it.first}: ${it.second}", Blue) }
            if (voiceCheck == "checking") Banner("Verifying your voice…", Blue)

            Text(vm.greeting, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = VioletSoft, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // ---- Centre: the character, full figure (never circular) ----
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(300.dp)
                        .background(Brush.radialGradient(listOf(emotion.composeColor.copy(alpha = 0.30f), Color.Transparent)))
                )
                RaniCharacter(
                    state, level, vm.lowPower,
                    Modifier.fillMaxWidth().height(320.dp).clickable { tapToTalk() },
                    fullFigure = true,
                )
            }
            StonicStatusBar(stateLabel(state, status, partial), emotion.composeColor)
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
                VoiceOrb(state, level, vm.lowPower, Modifier.size(64.dp).clip(CircleShape).clickable { onNav("voice") })
            }
            Text("Tap the figure or the orb to talk", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.labelMedium)

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
