package com.badshah222.rani.ai.features.settings

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.data.ai.*
import com.badshah222.rani.ai.data.voice.*
import com.badshah222.rani.ai.domain.*
import com.badshah222.rani.ai.features.auth.SessionViewModel
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Settings — ported from MYRA Ultra's SettingsPage (myra_pages.py).
 * Cards in MYRA order: Profile, Language, Gemini API, Voice & model,
 * Session (live), Window, Orb / startup / Screen Guardian, About.
 * RANI is always the girlfriend — no mode switch, no male voices.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    private val uid get() = c.auth.current?.uid

    // ---- Profile (MYRA: Profile name) ----
    val assistantName = MutableStateFlow(c.store.assistantName)
    val petName = MutableStateFlow(c.store.petName)
    fun setAssistantName(n: String) { c.store.assistantName = n.trim().ifBlank { "RANI" }; assistantName.value = c.store.assistantName }
    fun setPetName(n: String) { c.store.petName = n.trim().ifBlank { "Babu Jaan" }; petName.value = c.store.petName }

    // ---- Language (MYRA: 148 languages + search) ----
    val language = MutableStateFlow(c.store.language)
    fun setLanguage(tag: String) { c.store.language = tag; language.value = tag; pushPrefs() }

    // ---- Gemini API (MYRA: Gemini API key; BYOK — user enters their own free key) ----
    val provider = MutableStateFlow(if (c.store.provider == Provider.GEMINI) Provider.GEMINI else c.store.provider)
    val models = MutableStateFlow<List<String>>(emptyList())
    val selectedModel = MutableStateFlow(c.store.model(c.store.provider))
    val keySaved = MutableStateFlow(c.store.apiKey(c.store.provider) != null)
    val aiMsg = MutableStateFlow<Pair<Boolean, String>?>(null)
    val testing = MutableStateFlow(false)
    fun selectProvider(p: Provider) { c.store.provider = p; provider.value = p; models.value = emptyList(); selectedModel.value = c.store.model(p); keySaved.value = c.store.apiKey(p) != null; aiMsg.value = null; pushPrefs() }
    fun testAndSave(key: String) {
        val p = provider.value
        if (key.isBlank() && !keySaved.value) { aiMsg.value = false to "Pehle apni ${p.label} API key paste karo, Babu Jaan."; return }
        viewModelScope.launch {
            testing.value = true; aiMsg.value = null
            try {
                val k = key.ifBlank { c.store.apiKey(p)!! }
                val list = c.ai.testConnection(p, k)
                c.store.setApiKey(p, k); keySaved.value = true; models.value = list
                val keep = c.store.model(p)?.takeIf { it in list } ?: list.first(); c.store.setModel(p, keep); selectedModel.value = keep
                aiMsg.value = true to "Connected. ${list.size} models available."
                if (p == Provider.GEMINI) {
                    val lm = runCatching { c.ai.detectLiveModel() }.getOrNull()
                    liveModel.value = lm; liveModelKey.value = c.store.liveModelKey; liveCandidates.value = c.store.liveCandidates; geminiSaved.value = true
                    if (lm != null) aiMsg.value = true to "Key is valid. Live voice available — ${LiveModels.fromKey(c.store.liveModelKey)?.label ?: "Gemini Live"}."
                    else aiMsg.value = true to "Key valid hai — chat chalega. Lekin is key par Live voice nahi mila, is liye Live call classic mode mein chalegi."
                }
                uid?.let { runCatching { c.cloud.saveProviderStatus(it, p.id, true, "ok") } }; pushPrefs()
            } catch (e: AiException) { aiMsg.value = false to e.userMessage; uid?.let { runCatching { c.cloud.saveProviderStatus(it, p.id, c.store.apiKey(p) != null, "failed") } } }
            catch (e: Exception) { aiMsg.value = false to "Could not test the key." } finally { testing.value = false }
        }
    }
    fun selectModel(m: String) { c.store.setModel(provider.value, m); selectedModel.value = m; pushPrefs() }
    fun removeKey() { val p = provider.value; c.store.setApiKey(p, null); keySaved.value = false; models.value = emptyList(); aiMsg.value = true to "Key removed from this phone."; uid?.let { u -> viewModelScope.launch { runCatching { c.cloud.saveProviderStatus(u, p.id, false, "removed") } } } }

    // ---- Voice & model (MYRA VoicePage: female voices only, live model RadioList) ----
    val liveVoiceName = MutableStateFlow(c.store.liveVoiceName)
    val liveModel = MutableStateFlow(c.store.liveModel)
    val liveModelKey = MutableStateFlow(c.store.liveModelKey)
    val geminiSaved = MutableStateFlow(c.store.apiKey(Provider.GEMINI) != null)
    val liveCandidates = MutableStateFlow(c.store.liveCandidates)
    val lastLiveError = MutableStateFlow(c.store.lastLiveError)
    val liveEnabled = MutableStateFlow(c.store.liveEnabled)
    val liveMsg = MutableStateFlow<Pair<Boolean, String>?>(null)
    val liveTesting = MutableStateFlow(false)
    fun setLiveVoice(v: LiveVoice) { c.store.liveVoiceName = v.name; liveVoiceName.value = v.name }
    fun setLiveModelKey(k: String) { c.store.liveModelKey = k; liveModelKey.value = k }
    fun setLiveEnabled(on: Boolean) { c.store.liveEnabled = on; liveEnabled.value = on }
    fun testLive() {
        if (c.store.apiKey(Provider.GEMINI) == null) { liveMsg.value = false to "Pehle Connections mein apni Gemini API key save karo."; return }
        viewModelScope.launch {
            liveTesting.value = true; liveMsg.value = null
            try {
                val m = c.ai.detectLiveModel()
                liveModel.value = m; liveModelKey.value = c.store.liveModelKey; liveCandidates.value = c.store.liveCandidates; lastLiveError.value = c.store.lastLiveError
                liveMsg.value = if (m != null) true to "Live ready — ${LiveModels.fromKey(c.store.liveModelKey)?.label ?: "Gemini Live"} available hai." else false to "Is key par Live voice nahi hai. Voice Call classic mode mein chalegi."
            } catch (e: Exception) { liveMsg.value = false to "Live test failed. Internet check karke dobara try karo." }
            finally { liveTesting.value = false }
        }
    }

    // ---- Voice basics (MYRA VoicePage: listen modes, mic sensitivity + gain) ----
    val listenMode = MutableStateFlow(c.store.listenMode)
    val micSensitivity = MutableStateFlow(c.store.micSensitivity)
    val micGain = MutableStateFlow(c.store.micGain)
    val speak = MutableStateFlow(c.store.speakReplies)
    val lowPower = MutableStateFlow(c.store.lowPower)
    val googleVoice = MutableStateFlow(c.store.googleTtsVoice)
    fun setListenMode(m: String) { c.store.listenMode = m; listenMode.value = m }
    fun setMicSensitivity(v: Int) { c.store.micSensitivity = v; micSensitivity.value = v }
    fun setMicGain(v: Float) { c.store.micGain = v; micGain.value = v }
    fun setSpeak(v: Boolean) { c.store.speakReplies = v; speak.value = v }
    fun setLowPower(v: Boolean) { c.store.lowPower = v; lowPower.value = v }
    fun pickGoogleVoice(v: String) { c.store.googleTtsVoice = v; googleVoice.value = v }

    // ---- Orb & Guardian (MYRA SettingsPage: orb float size 56–200 default 96,
    //      UI scale 60–140 default 100, guardian interval 30/45/90 default 45) ----
    val orbFloatSize = MutableStateFlow(c.store.orbFloatSize)
    val orbUiScale = MutableStateFlow(c.store.orbUiScale)
    val guardianEnabled = MutableStateFlow(c.store.guardianEnabled)
    val guardianInterval = MutableStateFlow(c.store.guardianInterval)
    fun setOrbFloatSize(v: Int) { c.store.orbFloatSize = v; orbFloatSize.value = v }
    fun setOrbUiScale(v: Int) { c.store.orbUiScale = v; orbUiScale.value = v }
    fun setGuardianEnabled(v: Boolean) {
        c.store.guardianEnabled = v; guardianEnabled.value = v
        // The service only watches while Screen Share is ON; otherwise it waits quietly.
        if (v) com.badshah222.rani.ai.features.guardian.ScreenGuardianService.start(getApplication())
        else com.badshah222.rani.ai.features.guardian.ScreenGuardianService.stop(getApplication())
    }
    fun setGuardianInterval(v: Int) { c.store.guardianInterval = v; guardianInterval.value = v }

    // ---- Memory (MYRA: ConversationMemory — on-device only) ----
    val memoryOn = MutableStateFlow(c.store.memoryOn)
    val memoryAskFirst = MutableStateFlow(c.store.memoryAskFirst)
    fun setMemoryOn(v: Boolean) { c.store.memoryOn = v; memoryOn.value = v }
    fun setMemoryAskFirst(v: Boolean) { c.store.memoryAskFirst = v; memoryAskFirst.value = v }

    // ---- Identity / look (RANI fixed: girlfriend, girl avatar or crown logo) ----
    val avatarChoice = MutableStateFlow(c.store.avatarChoice)
    val personalityId = MutableStateFlow(c.store.personalityId)
    val emotionId = MutableStateFlow(c.store.emotionId)
    val responseStyle = MutableStateFlow(c.store.responseStyle)
    fun setAvatar(a: String) { c.store.avatarChoice = a; avatarChoice.value = a }
    fun setEmotion(e: Emotion?) { c.store.emotionId = e?.id; emotionId.value = e?.id }
    fun setResponseStyle(s: String) { c.store.responseStyle = s; responseStyle.value = s }

    // ---- Image Studio (MYRA: Gemini image first, free Pollinations fallback) ----
    val imageModel = MutableStateFlow(c.store.imageModel)
    fun setImageModel(m: String) { c.store.imageModel = m; imageModel.value = m }

    // ---- Google Voice (optional, female voices only) ----
    val googleSaved = MutableStateFlow(c.store.googleTtsKey() != null)
    val googleMsg = MutableStateFlow<Pair<Boolean, String>?>(null)
    val engine = MutableStateFlow(c.store.voiceEngine)
    fun testGoogle(key: String) {
        val k = key.ifBlank { c.store.googleTtsKey() }; if (k == null) { googleMsg.value = false to "Pehle Google Cloud API key paste karo."; return }
        viewModelScope.launch {
            try { GoogleTtsClient().synthesize(k, googleVoice.value, "Hello, I am RANI AI."); c.store.setGoogleTtsKey(k); googleSaved.value = true; googleMsg.value = true to "Google Voice connected." }
            catch (e: Exception) { googleMsg.value = false to (e.message?.take(130) ?: "Google Voice connect nahi hua.") }
        }
    }
    fun removeGoogle() { c.store.setGoogleTtsKey(null); googleSaved.value = false; if (c.store.voiceEngine == "google") setEngine("android"); googleMsg.value = true to "Google Voice key removed." }
    fun setEngine(e: String) { c.store.voiceEngine = e; engine.value = e; pushPrefs() }

    private fun pushPrefs() { val u = uid ?: return; viewModelScope.launch { runCatching { c.cloud.savePreferences(u, provider.value.id, selectedModel.value, null, engine.value, 1.0f, 1.0f, language.value) } } }
}

@Composable private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = VioletSoft); Text(title, style = MaterialTheme.typography.titleMedium, color = VioletSoft) }; content() } }
}
@Composable private fun Msg(m: Pair<Boolean, String>?) { m?.let { Text(it.second, color = if (it.first) Ok else Bad, style = MaterialTheme.typography.bodySmall) } }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(session: SessionViewModel, vm: SettingsViewModel, onBack: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val provider by vm.provider.collectAsState(); val models by vm.models.collectAsState(); val selModel by vm.selectedModel.collectAsState()
    val keySaved by vm.keySaved.collectAsState(); val aiMsg by vm.aiMsg.collectAsState(); val testing by vm.testing.collectAsState()
    val liveVoiceName by vm.liveVoiceName.collectAsState(); val liveModel by vm.liveModel.collectAsState(); val liveModelKey by vm.liveModelKey.collectAsState()
    val liveEnabled by vm.liveEnabled.collectAsState(); val geminiSaved by vm.geminiSaved.collectAsState()
    val liveCandidates by vm.liveCandidates.collectAsState(); val lastLiveError by vm.lastLiveError.collectAsState()
    val liveMsg by vm.liveMsg.collectAsState(); val liveTesting by vm.liveTesting.collectAsState()
    val listenMode by vm.listenMode.collectAsState(); val micSensitivity by vm.micSensitivity.collectAsState(); val micGain by vm.micGain.collectAsState()
    val speak by vm.speak.collectAsState(); val low by vm.lowPower.collectAsState(); val lang by vm.language.collectAsState()
    val orbFloatSize by vm.orbFloatSize.collectAsState(); val orbUiScale by vm.orbUiScale.collectAsState()
    val guardianEnabled by vm.guardianEnabled.collectAsState(); val guardianInterval by vm.guardianInterval.collectAsState()
    val memoryOn by vm.memoryOn.collectAsState(); val memoryAskFirst by vm.memoryAskFirst.collectAsState()
    val avatarChoice by vm.avatarChoice.collectAsState(); val emotionId by vm.emotionId.collectAsState()
    val responseStyle by vm.responseStyle.collectAsState(); val imageModel by vm.imageModel.collectAsState()
    val googleSaved by vm.googleSaved.collectAsState(); val googleVoice by vm.googleVoice.collectAsState(); val googleMsg by vm.googleMsg.collectAsState()
    val engine by vm.engine.collectAsState()
    val assistantName by vm.assistantName.collectAsState(); val petName by vm.petName.collectAsState()
    val user by session.user.collectAsState()
    var emotionFamily by remember { mutableStateOf(Emotions.families.first()) }
    var nameDraft by remember(assistantName) { mutableStateOf(assistantName) }
    var petDraft by remember(petName) { mutableStateOf(petName) }
    var langSearch by remember { mutableStateOf("") }
    var orbOn by remember { mutableStateOf(com.badshah222.rani.ai.core.FloatingOrbService.running) }
    var key by remember(provider) { mutableStateOf("") }; var showKey by remember { mutableStateOf(false) }
    var gKey by remember { mutableStateOf("") }; var gShow by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Settings", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.rani_face), "RANI", Modifier.size(54.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(assistantName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(user?.email ?: "Signed in", color = Mute, style = MaterialTheme.typography.bodySmall)
                            Text("Your girlfriend · RANI AI GF", color = VioletSoft, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("AI SETTINGS", color = Mute, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp)) }

            // ---- Profile (MYRA SettingsPage) ----
            item { Section("Profile", Icons.Default.Person) {
                Text("RANI tumhe kis naam se bulaye? Pet name hamesha wahi rahega — jaise \"Babu Jaan\".", style = MaterialTheme.typography.bodySmall, color = Mute)
                OutlinedTextField(nameDraft, { nameDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Assistant name") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(petDraft, { petDraft = it }, Modifier.weight(1f), singleLine = true, label = { Text("Pet name (tumhara naam)") })
                    Button({ vm.setAssistantName(nameDraft); vm.setPetName(petDraft) }) { Text("Save") }
                }
            } }

            // ---- Language (MYRA: 148 languages + search) ----
            item { Section("Language", Icons.Default.Language) {
                Text("RANI isi language mein sunegi aur bolegi — ${RaniLanguages.all.size} languages. Auto = khud pehchan legi.", style = MaterialTheme.typography.bodySmall, color = Mute)
                OutlinedTextField(langSearch, { langSearch = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Language dhoondo…") })
                val q = langSearch.trim().lowercase()
                val shown = RaniLanguages.all.filter { q.isEmpty() || it.second.lowercase().contains(q) || it.third.lowercase().contains(q) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(lang == RaniLanguages.AUTO, { vm.setLanguage(RaniLanguages.AUTO) }, { Text("Auto-detect") })
                    shown.take(40).forEach { (code, en, native) ->
                        FilterChip(lang == code, { vm.setLanguage(code) }, { Text(if (native == en) en else "$en · $native") })
                    }
                }
                if (shown.size > 40) Text("…aur ${shown.size - 40} — search karke dhoondo.", style = MaterialTheme.typography.bodySmall, color = Mute)
            } }

            // ---- Connections / Gemini API (MYRA: Gemini API — BYOK) ----
            item { Section("Connections — Gemini API", Icons.Default.Key) {
                Text("RANI tumhari apni free Gemini key se chalti hai — koi built-in key nahi. Key sirf is phone par encrypted rehti hai. Google AI Studio se free key lo, yahan paste karo, Test & save dabao.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Provider.entries.forEach { p -> FilterChip(provider == p, { vm.selectProvider(p) }, { Text(p.label) }) } }
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (keySaved) "API key saved (paste a new one to replace)" else "${provider.label} API key") }, singleLine = true,
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton({ showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({ vm.testAndSave(key); key = "" }, enabled = !testing) { if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test & save") }
                    OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(provider.keyUrl))) }) { Text("Get a key") }
                    if (keySaved) TextButton(vm::removeKey) { Text("Remove") }
                }
                Msg(aiMsg)
                if (keySaved) Box { OutlinedButton({ modelMenu = true }, enabled = models.isNotEmpty() || selModel != null) { Text("Model: ${selModel ?: "-"}") }
                    DropdownMenu(modelMenu, { modelMenu = false }) { models.forEach { m -> DropdownMenuItem({ Text(m) }, { vm.selectModel(m); modelMenu = false }) } } }
                if (keySaved && models.isEmpty()) Text("Press Test & save to load the list of models for your key.", style = MaterialTheme.typography.bodySmall, color = Mute)
            } }

            // ---- Voice & model (MYRA VoicePage: female voices + live model RadioList) ----
            item { Section("Voice & model — Live voice", Icons.Default.RecordVoiceOver) {
                Text("Voice Call Gemini Live par chalti hai — Google ki asli insani ladkiyon ki awaazein. Saari voices female hain, default Aoede (MYRA wali).", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Use Live voice on Voice Call", Modifier.weight(1f)); Switch(liveEnabled, vm::setLiveEnabled) }
                Text("Live model chuno:", style = MaterialTheme.typography.labelLarge, color = Gold)
                LiveModels.all.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(liveModelKey == m.key, { vm.setLiveModelKey(m.key) })
                        Column { Text(m.label, style = MaterialTheme.typography.bodyLarge); Text("${m.modelId} · ${m.version}", style = MaterialTheme.typography.bodySmall, color = Mute) }
                    }
                }
                Text("RANI ki voices — saari female:", style = MaterialTheme.typography.labelLarge, color = Gold)
                LiveVoices.female.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(liveVoiceName == v.name, { vm.setLiveVoice(v) })
                        Column { Text(v.name, style = MaterialTheme.typography.bodyLarge); Text(v.style, style = MaterialTheme.typography.bodySmall, color = Mute) }
                    }
                }
                Text("Gemini key: ${if (geminiSaved) "saved hai ✓" else "NAHI daali — Live sirf Gemini key se chalta hai. Connections mein Google Gemini select karke key daalo."}", style = MaterialTheme.typography.bodySmall, color = if (geminiSaved) Ok else Bad)
                lastLiveError?.let { Text("Aakhri Live koshish ki wajah: $it", style = MaterialTheme.typography.bodySmall, color = Bad) }
                Button(vm::testLive, enabled = !liveTesting) { if (liveTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test Live voice") }
                Msg(liveMsg)
                Text("Voice sunne ke liye Voice Call kholo — wahan live baat hogi isi voice mein.", style = MaterialTheme.typography.bodySmall, color = Mute)
            } }

            // ---- Session (live) (MYRA SettingsPage: Session panel + resumption ON) ----
            item { Section("Session (live)", Icons.Default.Psychology) {
                Text("Live model: ${liveModel ?: "abhi confirm nahi"}", style = MaterialTheme.typography.bodySmall, color = if (liveModel != null) Ok else Mute)
                Text("Session resumption ON hai — call kat jaye to RANI wahi se baat dobara shuru karti hai, \"Hello\" dobara nahi kehti.", style = MaterialTheme.typography.bodySmall, color = Mute)
                if (liveCandidates.isNotEmpty()) Text("Tried models: ${liveCandidates.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Listen mode", Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaniTools.listenModes.forEach { m -> FilterChip(listenMode == m, { vm.setListenMode(m) }, { Text(RaniTools.listenLabel(m)) }) }
                }
                Text(RaniTools.listenHint(listenMode), style = MaterialTheme.typography.bodySmall, color = Mute)
                Text("Mic sensitivity: $micSensitivity", style = MaterialTheme.typography.bodySmall)
                Slider(micSensitivity.toFloat(), { vm.setMicSensitivity(it.toInt()) }, valueRange = 5f..120f)
                Text("Mic gain: ${"%.1f".format(micGain)}x", style = MaterialTheme.typography.bodySmall)
                Slider(micGain, { vm.setMicGain(it) }, valueRange = 1f..5f)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Speak chat replies aloud", Modifier.weight(1f)); Switch(speak, vm::setSpeak) }
            } }

            // ---- Orb & Guardian (MYRA SettingsPage) ----
            item { Section("Orb & Screen Guardian", Icons.Default.Security) {
                Text("Floating orb — doosri apps ke upar chhota gola (MYRA style). Tap karo to voice call, long-press karo to gayab. Pehli baar Android overlay permission mangega.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Floating orb"); Text("Doosri apps ke upar", style = MaterialTheme.typography.bodySmall, color = Mute) }
                    Switch(orbOn, { on ->
                        orbOn = on
                        if (on) {
                            if (android.provider.Settings.canDrawOverlays(ctx)) com.badshah222.rani.ai.core.FloatingOrbService.start(ctx)
                            else runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        } else com.badshah222.rani.ai.core.FloatingOrbService.stop(ctx)
                    })
                }
                Text("Orb size: $orbFloatSize px", style = MaterialTheme.typography.bodySmall)
                Slider(orbFloatSize.toFloat(), { vm.setOrbFloatSize(it.toInt()) }, valueRange = 56f..200f)
                Text("UI scale: $orbUiScale%", style = MaterialTheme.typography.bodySmall)
                Slider(orbUiScale.toFloat(), { vm.setOrbUiScale(it.toInt()) }, valueRange = 60f..140f)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Low-power mode", Modifier.weight(1f)); Switch(low, vm::setLowPower) }
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Screen Guardian"); Text("Screen share ON ho to RANI screen par nazar rakhti hai aur sensitive cheez (password / bank / OTP) par warning deti hai.", style = MaterialTheme.typography.bodySmall, color = Mute) }; Switch(guardianEnabled, vm::setGuardianEnabled) }
                if (guardianEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 45, 90).forEach { s -> FilterChip(guardianInterval == s, { vm.setGuardianInterval(s) }, { Text("$s sec") }) }
                }
            } }

            // ---- Memory (MYRA ConversationMemory) ----
            item { Section("Memory", Icons.Default.Memory) {
                Text("RANI tumhari baatein yaad rakhti hai — sirf is phone par. Kabhi upload nahi hoti.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Yaad rakhna ON hai", Modifier.weight(1f)); Switch(memoryOn, vm::setMemoryOn) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Save karne se pehle poochna", Modifier.weight(1f)); Switch(memoryAskFirst, vm::setMemoryAskFirst) }
                Text("Special days aur favourites Memory screen par manage karo.", style = MaterialTheme.typography.bodySmall, color = Mute)
            } }

            // ---- Image Studio (MYRA: Gemini image + free fallback) ----
            item { Section("Image Studio", Icons.Default.Image) {
                Text("Chat mein bolo \"image banao\" — RANI khud image bana degi. Pehle Gemini image model (tumhari key), warna free image.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(imageModel == "gemini", { vm.setImageModel("gemini") }, { Text("Gemini image") })
                    FilterChip(imageModel == "pollinations", { vm.setImageModel("pollinations") }, { Text("Pollinations (free)") })
                }
            } }

            // ---- Emotions (BADSHAH base: voice tone follows the emotion) ----
            item { Section("Emotions", Icons.Default.Favorite) {
                Text("Auto par RANI tumhare alfaaz se tumhara mood pehchanti hai aur usi emotion mein jawab deti hai. Ya khud koi emotion fix kar lo.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(emotionId == null, { vm.setEmotion(null) })
                    Column { Text("Auto — reads your mood", style = MaterialTheme.typography.bodyLarge); Text("Recommended", style = MaterialTheme.typography.bodySmall, color = Mute) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Emotions.families.forEach { fam -> FilterChip(emotionFamily == fam, { emotionFamily = fam }, { Text(fam) }) }
                }
                Emotions.byFamily(emotionFamily).forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(emotionId == e.id, { vm.setEmotion(e) })
                        Box(Modifier.size(10.dp).clip(CircleShape).background(e.composeColor))
                        Text(e.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } }

            // ---- Look & feel ----
            item { Section("Look & feel", Icons.Default.AutoAwesome) {
                Text("Face & logo — Home aur Voice Call ke orb mein yahi nazar ayega.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(avatarChoice == "girl", { vm.setAvatar("girl") }, { Text("RANI face") })
                    FilterChip(avatarChoice == "logo", { vm.setAvatar("logo") }, { Text("Crown logo") })
                }
                Text("Response style", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(responseStyle == "balanced", { vm.setResponseStyle("balanced") }, { Text("Balanced") })
                    FilterChip(responseStyle == "short", { vm.setResponseStyle("short") }, { Text("Short") })
                    FilterChip(responseStyle == "detailed", { vm.setResponseStyle("detailed") }, { Text("Detailed") })
                }
            } }

            // ---- Google Voice (optional, female voices only) ----
            item { Section("Google Voice (optional)", Icons.Default.RecordVoiceOver) {
                Text("Google ki premium female awaazein chat replies ke liye — phone ki robot awaaz se zyada natural. Apni Google Cloud API key lagao (Text-to-Speech API enable karo). Key sirf is phone par rehti hai.", style = MaterialTheme.typography.bodySmall, color = Mute)
                OutlinedTextField(gKey, { gKey = it }, Modifier.fillMaxWidth(), label = { Text(if (googleSaved) "Google Cloud key saved" else "Google Cloud API key") }, singleLine = true,
                    visualTransformation = if (gShow) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton({ gShow = !gShow }) { Text(if (gShow) "Hide" else "Show") } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({ vm.testGoogle(gKey); gKey = "" }) { Text("Test & save") }
                    if (googleSaved) TextButton(vm::removeGoogle) { Text("Remove") }
                }
                Msg(googleMsg)
                if (googleSaved) {
                    Text("Voice chuno:", style = MaterialTheme.typography.bodySmall, color = Mute)
                    GoogleVoices.all.forEach { (id, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(googleVoice == id, { vm.pickGoogleVoice(id) }); Column { Text(label, style = MaterialTheme.typography.bodyMedium); Text(id, style = MaterialTheme.typography.bodySmall, color = Mute) } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(engine == "google", { vm.setEngine("google") }, { Text("Google voice for replies") }); FilterChip(engine == "android", { vm.setEngine("android") }, { Text("Phone voice") }) }
                }
            } }

            item { Section("App settings", Icons.Default.Tune) {
                OutlinedButton(onPermissions) { Text("Permissions") }
                OutlinedButton({ session.signOut() }) { Text("Sign out") }
                Text("RANI AI GF v1.0.0 — BADSHAH222", style = MaterialTheme.typography.bodySmall, color = Mute)
            } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TopBar(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
}
