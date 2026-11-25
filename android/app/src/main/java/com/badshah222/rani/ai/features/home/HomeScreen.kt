package com.badshah222.rani.ai.features.home

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.core.RaniAccessibilityService
import com.badshah222.rani.ai.data.phone.MessageSender
import com.badshah222.rani.ai.data.phone.PhoneResult
import com.badshah222.rani.ai.data.phone.SendOutcome
import com.badshah222.rani.ai.data.ai.*
import com.badshah222.rani.ai.domain.*
import com.badshah222.rani.ai.features.auth.SessionViewModel
import com.badshah222.rani.ai.ui.character.CharState
import com.badshah222.rani.ai.ui.orb.VoiceOrb
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
    val status = MutableStateFlow("Tap the orb and speak, or type below.")
    val pending = MutableStateFlow<PhoneAction?>(null)        // waiting for user confirmation (calls / messages)
    val needPermission = MutableStateFlow<Pair<String, String>?>(null)
    val pickContact = MutableStateFlow(false)
    val working = MutableStateFlow(false)
    val emotion = MutableStateFlow(Emotions.fromId("warm"))
    val lowPower get() = c.store.lowPower
    /** The girl assistant (RANI) is the face when the user picked the girl avatar, or the Girlfriend personality. */
    val girlMode: Boolean get() = c.store.avatarChoice == "girl" || c.store.personalityId == "girlfriend"
    /** Time-aware greeting with the pet name — "Subah bakhair, Babu Jaan".
     *  On a special day (birthday / anniversary) she wishes you first. */
    val greeting: String get() {
        val pet = c.store.petName
        val day = specialDayToday()
        if (day != null) return "\uD83C\uDF89 $day, $pet!"
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val base = when (hour) {
            in 5..11 -> "Subah bakhair"
            in 12..16 -> "Dopahar bakhair"
            in 17..20 -> "Shaam bakhair"
            else -> "Raat bakhair"
        }
        return "$base, $pet"
    }
    private fun specialDayToday(): String? {
        return try {
            val now = java.util.Calendar.getInstance()
            val m = now.get(java.util.Calendar.MONTH) + 1
            val d = now.get(java.util.Calendar.DAY_OF_MONTH)
            val arr = org.json.JSONArray(c.store.specialDaysJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optInt("month") == m && o.optInt("day") == d) return "Happy ${o.optString("name")}"
            }
            null
        } catch (e: Exception) { null }
    }
    val assistantName: String get() = c.store.assistantName
    val avatarChoice: String get() = c.store.avatarChoice
    val listening = c.speech.listening; val micLevel = c.speech.level; val speaking = c.tts.speaking

    // ---- Gemini Live (real human voice) ----
    val live = c.live
    val liveFallback = MutableStateFlow<String?>(null)
    fun liveAvailable(): Boolean = c.store.liveEnabled && c.store.apiKey(Provider.GEMINI) != null
    fun startLiveSession() {
        liveFallback.value = null
        viewModelScope.launch {
            if (c.store.liveModel == null) c.ai.detectLiveModel()
            val emo = c.store.emotionId?.let { Emotions.fromId(it) } ?: emotion.value
            c.live.start(c.ai.systemPrompt(emo.style))
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
                val emo = c.store.emotionId?.let { Emotions.fromId(it) } ?: Emotions.detect(t)
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
        status.value = "Message likh kar bhej rahi hoon…"
        viewModelScope.launch {
            when (val r = MessageSender.send(ctx, a.channel, a.target, a.message)) {
                is SendOutcome.Sent -> reply("Bhej diya, Babu Jaan — ${r.contactName} ko ${if (r.channel == "whatsapp") "WhatsApp" else "SMS"} par: \"${a.message}\"")
                SendOutcome.NeedsAccessibility -> reply("Accessibility ON nahi hai, Babu Jaan — Settings > Accessibility mein RANI AI ON karo, phir dobara bolo.")
                SendOutcome.NeedsContactsPermission -> { needPermission.value = android.Manifest.permission.READ_CONTACTS to "Naam se dhoondhne ke liye contacts ki permission chahiye, Babu Jaan."; reply("Contacts ki permission chahiye, Babu Jaan.") }
                is SendOutcome.Failed -> { error.value = r.reason; reply(r.reason) }
            }
            working.value = false
        }
    }
    fun retryLastAction() { lastAction?.let { runAction(it) } }
    fun contactPicked(name: String) = reply("Selected contact: $name. Say \"call $name\" or \"send WhatsApp to $name saying ...\".")

    private fun reply(text: String) { add(ChatMessage("assistant", text)); status.value = text; viewModelScope.launch { speak(text) } }
    // ---- image generation (MYRA Image Studio: Gemini image model first, free Pollinations fallback) ----
    private fun isImageRequest(t: String): Boolean {
        val l = t.lowercase()
        return listOf("image banao", "image bana", "tasveer banao", "tasveer bana", "photo banao", "pic banao",
            "generate image", "create image", "make an image", "make image", "draw me", "logo banao",
            "poster banao", "wallpaper banao", "image generate", "tasveer bana do", "image bana do").any { l.contains(it) }
    }

    private fun deliverImage(url: String) {
        add(ChatMessage("assistant", "image_url:$url"))
        status.value = "Image tayyar hai, Babu Jaan."
        viewModelScope.launch { speak("Image tayyar hai, Babu Jaan.") }
    }

    private fun deliverImageFile(path: String) {
        add(ChatMessage("assistant", "image_file:$path"))
        status.value = "Image tayyar hai, Babu Jaan."
        viewModelScope.launch { speak("Image tayyar hai, Babu Jaan.") }
    }

    private suspend fun generateImage(prompt: String) {
        status.value = "Image ban raha hai…"
        val chosen = c.store.imageModel
        if (chosen == "pollinations") { deliverImage(Pollinations.imageUrl(prompt)); return }
        // Gemini image model (user's own key) — the picture comes back as bytes.
        try {
            val bytes = c.ai.generateImage(prompt)
            val dir = java.io.File(getApplication<android.app.Application>().cacheDir, "images").apply { mkdirs() }
            val f = java.io.File(dir, "gemini_${System.currentTimeMillis()}.jpg")
            f.writeBytes(bytes)
            deliverImageFile(f.absolutePath)
            return
        } catch (e: AiException) {
            // No key / model failed — the free Pollinations image always answers.
            status.value = "Gemini image nahi bana saka — free image dikha rahi hoon."
        }
        deliverImage(Pollinations.imageUrl(prompt))
    }

    // ---- classic voice-call loop: listen → reply → listen again, until Babu Jaan ends the call ----
    val inCall = MutableStateFlow(false)
    val callMuted = MutableStateFlow(false)
    fun beginCall() { inCall.value = true; callMuted.value = false; c.tts.setCallVolume(true) }
    fun endCall() { inCall.value = false; c.tts.setCallVolume(false); stopListening(); stopSpeaking() }
    fun setCallMuted(m: Boolean) { callMuted.value = m; if (m) stopListening() else if (inCall.value) startListening() }

    private suspend fun speak(text: String) {
        if (!c.store.speakReplies) return
        c.tts.speak(text, onDone = {
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

    /** Chat attachment (MYRA-style): the user sends a photo, Gemini Vision describes it in chat. */
    fun askPhoto(jpeg: ByteArray) {
        add(ChatMessage("user", "📷 Maine ek photo bheji hai — is mein kya hai?"))
        viewModelScope.launch {
            busy.value = true
            try {
                val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                val r = c.ai.vision("User ne ye photo bheji hai. Is mein jo kuch hai tafseel se batao — cheezein, log, mahaul — aur agar koi text hai to wo bhi parh kar batao.", b64)
                reply(r.text)
            } catch (e: AiException) { reply(e.userMessage) }
            catch (e: Exception) { reply("Photo parhne mein masla aya, Babu Jaan.") }
            finally { busy.value = false }
        }
    }

    private suspend fun syncToCloud(userText: String, r: AiReply) {
        if (uid.isEmpty()) return
        runCatching {
            val id = convoId ?: c.cloud.createConversation(uid, userText, r.provider.id, r.model).also { convoId = it }
            c.cloud.addMessage(uid, id, "user", userText, null, null)
            c.cloud.addMessage(uid, id, "assistant", r.text, r.provider.id, r.model)
        } // best effort: local history is always kept even when cloud sync is unavailable
    }
    fun newChat() { convoId = null; messages.value = emptyList(); if (uid.isNotEmpty()) c.chats.clear(uid); c.tts.stop() }
    fun stopSpeaking() = c.tts.stop()
    override fun onCleared() { c.speech.stop() }
}

/** Short human label for the assistant's current state, LIA-style. */
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

@Composable
fun HomeScreen(session: SessionViewModel, vm: HomeViewModel, onNav: (String) -> Unit) {
    val state by vm.charState.collectAsState(); val level by vm.micLevel.collectAsState()
    val status by vm.status.collectAsState(); val partial by vm.partial.collectAsState()
    val emotion by vm.emotion.collectAsState()
    val maintenance by session.maintenance.collectAsState(); val announcements by session.announcements.collectAsState(); val cloud by session.cloudReady.collectAsState()
    var text by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Panel, Ink)))) {
        AmbientLights(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onNav("about") }) { Icon(Icons.Default.Menu, "About") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("RANI AI GF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("YOUR VIRTUAL AI GIRLFRIEND", style = MaterialTheme.typography.labelSmall, color = Mute, letterSpacing = 3.sp)
            }
            IconButton({ onNav("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        }
        maintenance?.let { Banner(it, Bad) }
        announcements.firstOrNull()?.let { Banner("${it.first}: ${it.second}", Blue) }

        Text(vm.greeting, Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, color = VioletSoft, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        HeroPortrait(vm.girlMode, vm.avatarChoice, vm.assistantName, emotion.composeColor, vm.lowPower)

        Text(stateLabel(state, status, partial), Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = if (state == CharState.Error) Bad else Mute, maxLines = 2)

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Panel.copy(alpha = 0.85f)).border(1.dp, emotion.composeColor.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(emotion.composeColor))
                Text("Emotion · ${emotion.label}", style = MaterialTheme.typography.labelMedium, color = emotion.composeColor)
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            VoiceOrb(state, level, vm.lowPower, Modifier.size(118.dp).clip(CircleShape).clickable { onNav("voice") })
        }
        Text("Tap to Talk", Modifier.fillMaxWidth().padding(bottom = 2.dp), textAlign = TextAlign.Center, color = VioletSoft, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Ask me anything…") }, maxLines = 3, shape = RoundedCornerShape(28.dp))
            FilledIconButton({ if (text.isNotBlank()) { vm.send(text); text = ""; onNav("chat") } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet, contentColor = Color.White)) {
                Icon(Icons.Default.Send, "Send")
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickCard(Modifier.weight(1f), Icons.Default.Chat, "Chat", "Get instant answers") { onNav("chat") }
            QuickCard(Modifier.weight(1f), Icons.Default.Call, "Voice Call", "Talk to RANI") { onNav("voice") }
            QuickCard(Modifier.weight(1f), Icons.Default.Bolt, "Quick Actions", "Phone control") { onNav("quick") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickCard(Modifier.weight(1f), Icons.Default.AutoAwesome, "AI Tools", "Summarise, translate, PDF…") { onNav("tools") }
            QuickCard(Modifier.weight(1f), Icons.Default.Language, "Website Builder", "WebForge — site banao") { onNav("webforge") }
            QuickCard(Modifier.weight(1f), Icons.Default.Code, "Coder", "AI se code likhwao") { onNav("coder") }
            QuickCard(Modifier.weight(1f), Icons.Default.Contacts, "Contacts", "Naam se call / message") { onNav("contacts") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickCard(Modifier.weight(1f), Icons.Default.Folder, "My Files", "Images, sites, ZIPs") { onNav("files") }
            QuickCard(Modifier.weight(1f), Icons.Default.PhotoCamera, "Media", "Camera vision, screenshots") { onNav("media") }
            QuickCard(Modifier.weight(1f), Icons.Default.Search, "Web Search", "Google, YouTube, RANI se poocho") { onNav("search") }
            QuickCard(Modifier.weight(1f), Icons.Default.MonitorHeart, "System Monitor", "CPU, RAM, battery") { onNav("monitor") }
        }
        Spacer(Modifier.height(12.dp))
        }
    }
    ActionDialogs(vm)
}

@Composable
private fun HeroPortrait(girl: Boolean, avatarChoice: String, assistantName: String, accent: Color, lowPower: Boolean) {
    val transition = rememberInfiniteTransition(label = "hero")
    val floatY by transition.animateFloat(-6f, 6f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val rgb = rememberRgbColor()
    var burst by remember { mutableStateOf(0) }
    val heroRes = when {
        girl -> R.drawable.rani_face
        avatarChoice == "logo" -> R.drawable.ic_launcher_fg
        else -> R.drawable.rani_face
    }
    Box(Modifier.fillMaxWidth().height(248.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(240.dp).clip(CircleShape).background(Brush.radialGradient(listOf(rgb.copy(alpha = 0.35f), Color.Transparent))))
        Box(Modifier.size(230.dp).border(1.5.dp, rgb.copy(alpha = 0.65f), CircleShape))
        Box(Modifier.size(254.dp).border(1.dp, accent.copy(alpha = 0.45f), CircleShape))
        Image(painterResource(heroRes), assistantName, Modifier.size(214.dp).offset(y = if (lowPower) 0.dp else floatY.dp).clip(CircleShape).clickable { burst++ }, contentScale = ContentScale.Crop)
        TapLightBurst(burst, rgb, Modifier.size(254.dp))
        Text(if (girl) "Hi, I'm\nRANI" else "Hi, I'm\n$assistantName", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = VioletSoft, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Always\nhere for you", Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), color = VioletSoft, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun QuickCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Card(modifier.clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.88f)), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Violet.copy(alpha = 0.28f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, title, tint = VioletSoft)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Mute)
        }
    }
}

@Composable
fun Banner(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
}
