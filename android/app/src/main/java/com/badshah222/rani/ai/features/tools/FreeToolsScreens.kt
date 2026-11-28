package com.badshah222.rani.ai.features.tools

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.HisaabStore
import com.badshah222.rani.ai.core.ReminderScheduler
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.domain.RaniModes
import com.badshah222.rani.ai.features.home.HomeViewModel
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * v3.0.0 §23.1 P2 — the free-tools suite. Every screen calls a LIVE
 * endpoint (Worker /api/tools endpoints, /api/news, or the public Aladhan API)
 * and shows COMING_SOON honestly when the endpoint is down — a dead
 * endpoint never produces a fake answer. Verified live 2026-11-28:
 * weather, joke, fact, meme, ask, wiki, shorten, lyrics-search,
 * lyrics-get (?artist=&title=), news, dost (auth-gated), tiktok (429
 * rate-limited — tikwm.com fallback), namaz (Aladhan direct).
 */
class FreeToolsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val store get() = c.store
    val hisaab = HisaabStore(app)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true; error.value = null
            runCatching { block() }.onFailure { error.value = it.message?.take(140) ?: "Something went wrong." }
            busy.value = false
        }
    }

    // ---- tool results (one state per screen, kept simple) ----
    val weatherText = MutableStateFlow<String?>(null)
    val tiktokText = MutableStateFlow<String?>(null)
    val tiktokUrl = MutableStateFlow<String?>(null)
    val lyricsResults = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())
    val lyricsText = MutableStateFlow<String?>(null)
    val maloomatText = MutableStateFlow<String?>(null)
    val funText = MutableStateFlow<String?>(null)
    val funImage = MutableStateFlow<ByteArray?>(null)
    val shortUrl = MutableStateFlow<String?>(null)
    val newsItems = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())
    val namazText = MutableStateFlow<String?>(null)
    val dostMessages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val briefingText = MutableStateFlow<String?>(null)
    val callLogs = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val callLogSummary = MutableStateFlow<String?>(null)
    val reminders = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())

    fun loadWeather(city: String) = io {
        val j = c.worker.tool("weather?city=${java.net.URLEncoder.encode(city, "UTF-8")}")
        weatherText.value = if (j == null) null else {
            val cur = j.optJSONObject("current") ?: j
            val temp = cur.optString("tempC").ifBlank { cur.optString("temp_c") }
            val desc = cur.optString("desc").ifBlank { cur.optString("description") }
            val feels = cur.optString("feelsC").ifBlank { cur.optString("feels_like") }
            val hum = cur.optString("humidity")
            val wind = cur.optString("windKmph").ifBlank { cur.optString("wind_kph") }
            buildString {
                append("Weather in $city\n")
                if (temp.isNotBlank()) append("Temperature: $temp°C")
                if (feels.isNotBlank()) append(" (feels like $feels°C)")
                append("\n")
                if (desc.isNotBlank()) append("Conditions: $desc\n")
                if (hum.isNotBlank()) append("Humidity: $hum%\n")
                if (wind.isNotBlank()) append("Wind: $wind km/h\n")
            }.trim()
        }
        if (weatherText.value == null) error.value = "Weather is not available right now — please try again in a bit."
    }

    fun loadTikTok(url: String) = io {
        var j = c.worker.tool("tiktok?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
        var via = "Worker"
        if (j == null || j.optString("error") == "limit") { j = c.worker.tikwm(url); via = "tikwm" }
        val data = j?.optJSONObject("data") ?: j
        val play = data?.optString("play").orEmpty()
        val title = data?.optString("title").orEmpty()
        val author = data?.optJSONObject("author")?.optString("nickname").orEmpty()
        if (play.isBlank()) {
            tiktokText.value = null
            error.value = "Could not fetch that TikTok link right now (the service is rate-limited). Please try again in a bit."
        } else {
            tiktokUrl.value = play
            tiktokText.value = "Download ready ($via).\n${if (author.isNotBlank()) "By: $author\n" else ""}${if (title.isNotBlank()) title else "TikTok video"}"
        }
    }

    fun searchLyrics(q: String) = io {
        val j = c.worker.tool("lyrics-search?q=${java.net.URLEncoder.encode(q, "UTF-8")}")
        // dev-finish fix: the live endpoint answers {"rows": [...]} — the
        // "results"/"data" shapes never arrive, so without "rows" every
        // search came back empty. Verified live 2026-11-28.
        val arr = j?.optJSONArray("results") ?: j?.optJSONArray("rows") ?: j?.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Triple(o.optString("title"), o.optString("artist"), o.optString("id")))
        }
        lyricsResults.value = out
        if (out.isEmpty()) error.value = "No lyrics found for that search."
    }

    fun loadLyrics(artist: String, title: String) = io {
        val j = c.worker.tool("lyrics-get?artist=${java.net.URLEncoder.encode(artist, "UTF-8")}&title=${java.net.URLEncoder.encode(title, "UTF-8")}")
        lyricsText.value = j?.optString("lyrics")?.takeIf { it.isNotBlank() }
            ?: j?.optString("text")?.takeIf { it.isNotBlank() }
        if (lyricsText.value == null) error.value = "Lyrics are not available for that song right now."
    }

    fun askMaloomat(q: String, wiki: Boolean) = io {
        val j = if (wiki) c.worker.tool("wiki?q=${java.net.URLEncoder.encode(q, "UTF-8")}")
            else c.worker.tool("ask?q=${java.net.URLEncoder.encode(q, "UTF-8")}")
        maloomatText.value = j?.optString("answer")?.takeIf { it.isNotBlank() }
            ?: j?.optString("extract")?.takeIf { it.isNotBlank() }
            ?: j?.optString("text")?.takeIf { it.isNotBlank() }
        if (maloomatText.value == null) error.value = "No answer right now — please try again in a bit."
    }

    fun loadFun(kind: String) = io {
        val j = c.worker.tool(kind)
        when (kind) {
            "meme" -> {
                val url = j?.optString("url").orEmpty()
                if (url.isBlank()) { error.value = "No meme right now — please try again in a bit."; return@io }
                funText.value = j.optString("title").ifBlank { "Meme" }
                funImage.value = withContext(Dispatchers.IO) {
                    runCatching { URL(url).openStream().readBytes() }.getOrNull()
                }
            }
            "joke" -> {
                // N4 fix: the Worker joke source (jokeapi.dev) returns a
                // category — Dark/Spooky jokes are NOT clean. Retry (up to
                // 3 fetches) for a clean one; if the source keeps returning
                // dark jokes, say so instead of showing one. Two-part jokes
                // (setup + delivery) are joined into one text.
                var attempt = j
                var shown: String? = null
                for (i in 0 until 3) {
                    val cat = attempt?.optString("category").orEmpty()
                    val dark = cat.equals("Dark", ignoreCase = true) || cat.equals("Spooky", ignoreCase = true)
                    if (!dark) {
                        shown = attempt?.optString("joke")?.takeIf { it.isNotBlank() }
                            ?: run { val s = attempt?.optString("setup").orEmpty(); val d = attempt?.optString("delivery").orEmpty(); if (s.isNotBlank() && d.isNotBlank()) "$s\n$d" else null }
                            ?: attempt?.optString("text")?.takeIf { it.isNotBlank() }
                        if (shown != null) break
                    }
                    if (i < 2) attempt = c.worker.tool("joke")
                }
                funText.value = shown
                if (shown == null) error.value = "No clean joke right now — please try again in a bit."
            }
            "fact" -> {
                funText.value = j?.optString("fact")?.takeIf { it.isNotBlank() }
                    ?: j?.optString("text")?.takeIf { it.isNotBlank() }
                if (funText.value == null) error.value = "No fact right now — please try again in a bit."
            }
        }
    }

    fun shorten(url: String) = io {
        val j = c.worker.tool("shorten?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
        shortUrl.value = j?.optString("short")?.takeIf { it.isNotBlank() }
            ?: j?.optString("url")?.takeIf { it.isNotBlank() && it != url }
        if (shortUrl.value == null) error.value = "Could not shorten that link right now."
    }

    fun loadNews() = io {
        val j = c.worker.news()
        val arr = j?.optJSONArray("items") ?: j?.optJSONArray("articles") ?: JSONArray()
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Triple(o.optString("title"), o.optString("source").ifBlank { o.optString("site") }, o.optString("link").ifBlank { o.optString("url") }))
        }
        newsItems.value = out
        if (out.isEmpty()) error.value = "News is not available right now — please try again in a bit."
    }

    fun loadNamaz(city: String) = io {
        val j = c.worker.namaz(city)
        val data = j?.optJSONObject("data")
        val t = data?.optJSONObject("timings")
        namazText.value = if (t == null) null else {
            val date = data.optJSONObject("date")?.optString("readable").orEmpty()
            "Namaz times — $city${if (date.isNotBlank()) " ($date)" else ""}\n" +
                listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha", "Imsak")
                    .joinToString("\n") { k -> "$k: ${t.optString(k, "—")}" }
        }
        if (namazText.value == null) error.value = "Namaz times are not available right now."
    }

    fun loadDost(room: String) = io {
        val j = c.worker.dostGet(room)
        val arr = j?.optJSONArray("messages") ?: j?.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optString("name") to o.optString("text"))
        }
        dostMessages.value = out
        if (j == null) error.value = "Dost Room needs you to be signed in — please sign in and try again."
    }

    fun postDost(room: String, text: String) = io {
        val ok = c.worker.dostPost(room, c.store.userName, text)
        if (!ok) error.value = "Could not send that message — please sign in and try again."
        else loadDost(room)
    }

    fun loadBriefing() = io {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
        val fact = c.worker.tool("fact")?.optString("fact").orEmpty()
        val w = c.worker.tool("weather?city=Swabi")
        val wText = w?.let {
            val cur = it.optJSONObject("current") ?: it
            "${cur.optString("tempC")}°C, ${cur.optString("desc")}"
        }.orEmpty()
        val memCount = c.memory.facts().size
        val chatCount = c.memory.recentConversations().size
        briefingText.value = "Good morning, ${c.store.userName}.\n$today\n\n" +
            "Weather in Swabi: ${if (wText.isNotBlank()) wText else "not available right now"}\n\n" +
            "Things RANI remembers about you: $memCount\nConversations saved: $chatCount\n\n" +
            if (fact.isNotBlank()) "Did you know: $fact" else ""
    }

    fun loadCallLogs() {
        callLogs.value = c.memory.recentConversations()
    }

    fun summariseCallLog(text: String) = io {
        val reply = c.ai.chat(listOf(ChatMessage("user", "Summarise this conversation briefly in English, 2-3 lines, as a call log summary:\n$text")))
        callLogSummary.value = reply.text
    }

    fun loadReminders() {
        val arr = runCatching { JSONArray(c.store.tasksJson) }.getOrNull() ?: JSONArray()
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("status") == "done") continue
            out.add(Triple(o.optString("id"), o.optString("title"), o.optString("due")))
        }
        reminders.value = out
    }

    fun markReminderDone(id: String) {
        val arr = runCatching { JSONArray(c.store.tasksJson) }.getOrNull() ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) o.put("status", "done")
        }
        c.store.tasksJson = arr.toString()
        ReminderScheduler.cancel(getApplication(), id)
        loadReminders()
    }

    fun deleteReminder(id: String) {
        val arr = runCatching { JSONArray(c.store.tasksJson) }.getOrNull() ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        c.store.tasksJson = arr.toString()
        ReminderScheduler.cancel(getApplication(), id)
        loadReminders()
    }
}

/* ---------- shared bits ---------- */

@Composable
private fun ToolScaffold(title: String, onBack: () -> Unit, vm: FreeToolsViewModel, content: @Composable ColumnScope.() -> Unit) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar(title, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet)
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, title, tint = VioletSoft, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Mute, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, "Open", tint = Mute)
    }
}

/* ---------- hub ---------- */

@Composable
fun FreeToolsHubScreen(vm: FreeToolsViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Free Tools", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Free tools — no account, no payment. Each one uses a live source; if a source is down, the tool says so instead of guessing.", color = Mute, style = MaterialTheme.typography.bodySmall)
            ToolRow(Icons.Default.WbSunny, "Weather", "Today's weather, live", { onNav("weather") })
            ToolRow(Icons.Default.Newspaper, "News", "Latest headlines", { onNav("news") })
            ToolRow(Icons.Default.Mosque, "Namaz Times", "Prayer times for your city", { onNav("namaz") })
            ToolRow(Icons.Default.Timer, "Focus Timer", "Pomodoro — 25 minutes focus, 5 rest", { onNav("focus") })
            ToolRow(Icons.Default.GridView, "AI Modes", "26 modes — study, work, fun and more", { onNav("modes") })
            ToolRow(Icons.Default.WbSunny, "Morning Briefing", "Date, weather, memory, one fact", { onNav("briefing") })
            ToolRow(Icons.Default.Call, "Call Logs", "Past conversations with AI summary", { onNav("calllogs") })
            ToolRow(Icons.Default.Notifications, "Reminders", "Your saved reminders — they fire", { onNav("reminders") })
            ToolRow(Icons.Default.AccountBalanceWallet, "Hisaab", "Money in and out, encrypted on this phone", { onNav("hisaab") })
            ToolRow(Icons.Default.MusicVideo, "TikTok Saver", "Download a TikTok video by link", { onNav("tiktok") })
            ToolRow(Icons.Default.MusicNote, "Lyrics", "Search and read song lyrics", { onNav("lyrics") })
            ToolRow(Icons.Default.School, "Maloomat", "Ask anything, or read from Wikipedia", { onNav("maloomat") })
            ToolRow(Icons.Default.Celebration, "Fun Box", "Memes, jokes and facts", { onNav("funbox") })
            ToolRow(Icons.Default.Link, "Link Shortener", "Shorten any link", { onNav("shortener") })
            ToolRow(Icons.Default.Description, "CV Maker", "Build a CV and save it to Downloads", { onNav("cv") })
            ToolRow(Icons.Default.Mic, "Voice Studio", "Test RANI's voice and your recordings", { onNav("voicestudio") })
            ToolRow(Icons.Default.Group, "Dost Room", "Chat room with RANI and friends", { onNav("dost") })
        }
    }
}

/* ---------- Weather / News / Namaz / Briefing ---------- */

@Composable
fun WeatherScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var city by remember { mutableStateOf(vm.store.namazCity) }
    val text by vm.weatherText.collectAsState()
    LaunchedEffect(Unit) { vm.loadWeather(city) }
    ToolScaffold("Weather", onBack, vm) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(city, { city = it }, Modifier.weight(1f), singleLine = true, label = { Text("City") })
            Button({ vm.loadWeather(city.ifBlank { "Swabi" }) }) { Text("Check") }
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun NewsScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by vm.newsItems.collectAsState()
    LaunchedEffect(Unit) { vm.loadNews() }
    ToolScaffold("News", onBack, vm) {
        Button({ vm.loadNews() }) { Text("Refresh") }
        items.forEach { (title, source, link) ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).clickable(enabled = link.isNotBlank()) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            }.padding(12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (source.isNotBlank()) Text(source, color = Mute, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (items.isEmpty()) Text("No news loaded yet.", color = Mute)
    }
}

@Composable
fun NamazScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var city by remember { mutableStateOf(vm.store.namazCity) }
    val text by vm.namazText.collectAsState()
    LaunchedEffect(Unit) { vm.loadNamaz(city) }
    ToolScaffold("Namaz Times", onBack, vm) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(city, { city = it }, Modifier.weight(1f), singleLine = true, label = { Text("City") })
            Button({ vm.store.namazCity = city.ifBlank { "Swabi" }; vm.loadNamaz(city.ifBlank { "Swabi" }) }) { Text("Check") }
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("Times come from the public Aladhan API. The city is saved for Weather too.", color = Mute, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun BriefingScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val text by vm.briefingText.collectAsState()
    LaunchedEffect(Unit) { vm.loadBriefing() }
    ToolScaffold("Morning Briefing", onBack, vm) {
        Button({ vm.loadBriefing() }) { Text("Refresh") }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/* ---------- TikTok / Lyrics / Maloomat / Fun Box / Shortener ---------- */

@Composable
fun TikTokScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    val text by vm.tiktokText.collectAsState()
    val videoUrl by vm.tiktokUrl.collectAsState()
    ToolScaffold("TikTok Saver", onBack, vm) {
        Text("Paste a TikTok video link. The Worker tries first; if it is rate-limited, the public tikwm service is used instead.", color = Mute, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("TikTok link") })
        Button({ vm.loadTikTok(url.trim()) }, enabled = url.isNotBlank()) { Text("Get video") }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        videoUrl?.let { v ->
            Button({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v))) } }) { Text("Open video") }
            Text("Long-press the video in your browser to save it.", color = Mute, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LyricsScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by vm.lyricsResults.collectAsState()
    val text by vm.lyricsText.collectAsState()
    ToolScaffold("Lyrics", onBack, vm) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Song or artist") })
        Button({ vm.searchLyrics(q.trim()) }, enabled = q.isNotBlank()) { Text("Search") }
        results.forEach { (title, artist, _) ->
            ToolRow(Icons.Default.MusicNote, title.ifBlank { "Unknown song" }, artist, { vm.loadLyrics(artist, title) })
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun MaloomatScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var wiki by remember { mutableStateOf(false) }
    val text by vm.maloomatText.collectAsState()
    ToolScaffold("Maloomat", onBack, vm) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What do you want to know?") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!wiki, { wiki = false }, { Text("Ask") })
            FilterChip(wiki, { wiki = true }, { Text("Wikipedia") })
            Button({ vm.askMaloomat(q.trim(), wiki) }, enabled = q.isNotBlank()) { Text("Search") }
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun FunBoxScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val text by vm.funText.collectAsState()
    val img by vm.funImage.collectAsState()
    ToolScaffold("Fun Box", onBack, vm) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.loadFun("meme") }) { Text("Meme") }
            Button({ vm.loadFun("joke") }) { Text("Joke") }
            Button({ vm.loadFun("fact") }) { Text("Fact") }
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        img?.let { bytes ->
            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            bmp?.let { Image(it.asImageBitmap(), "Meme", Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
fun ShortenerScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val short by vm.shortUrl.collectAsState()
    ToolScaffold("Link Shortener", onBack, vm) {
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Long link") })
        Button({ vm.shorten(url.trim()) }, enabled = url.isNotBlank()) { Text("Shorten") }
        short?.let { Text("Short link:\n$it", style = MaterialTheme.typography.bodyMedium) }
    }
}

/* ---------- Hisaab / Reminders / Focus / CV / Voice Studio ---------- */

@Composable
fun HisaabScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("expense") }
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(refresh) { vm.hisaab.entries() }
    val income = remember(refresh) { vm.hisaab.totalIncome() }
    val expense = remember(refresh) { vm.hisaab.totalExpense() }
    ToolScaffold("Hisaab", onBack, vm) {
        Text("Income: $income PKR   •   Expense: $expense PKR   •   Balance: ${income - expense} PKR", fontWeight = FontWeight.SemiBold)
        Text("Saved encrypted on this phone only — never uploaded, never synced.", color = Mute, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Amount (PKR)") })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Note") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == "expense", { kind = "expense" }, { Text("Expense") })
            FilterChip(kind == "income", { kind = "income" }, { Text("Income") })
            Button({
                val a = amount.toLongOrNull()
                if (a != null && a > 0) { vm.hisaab.add(kind, a, note.ifBlank { kind }); amount = ""; note = ""; refresh++ }
            }, enabled = amount.isNotBlank()) { Text("Add") }
        }
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (e.kind == "income") "+" else "-"}${e.amount} PKR — ${e.note}")
                    Text(DateFormat.format("d MMM yyyy, h:mm a", e.at).toString(), color = Mute, style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ vm.hisaab.delete(e.id); refresh++ }) { Text("Delete", color = Bad) }
            }
        }
        if (entries.isEmpty()) Text("No entries yet.", color = Mute)
    }
}

@Composable
fun RemindersScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val items by vm.reminders.collectAsState()
    LaunchedEffect(Unit) { vm.loadReminders() }
    ToolScaffold("Reminders", onBack, vm) {
        Text("Reminders with a understood time FIRE — an alarm and a notification at the right moment. Saved ones without a time stay here as a list.", color = Mute, style = MaterialTheme.typography.bodySmall)
        Button({ vm.loadReminders() }) { Text("Refresh") }
        items.forEach { (id, title, due) ->
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    if (due.isNotBlank()) Text("When: $due", color = Mute, style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ vm.markReminderDone(id) }) { Text("Done") }
                TextButton({ vm.deleteReminder(id) }) { Text("Delete", color = Bad) }
            }
        }
        if (items.isEmpty()) Text("No pending reminders.", color = Mute)
    }
}

@Composable
fun FocusScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    var secondsLeft by remember { mutableStateOf(25 * 60) }
    var running by remember { mutableStateOf(false) }
    var isBreak by remember { mutableStateOf(false) }
    var cycles by remember { mutableStateOf(0) }
    LaunchedEffect(running) {
        while (running && secondsLeft > 0) { delay(1000); secondsLeft-- }
        if (running && secondsLeft == 0) {
            running = false
            if (!isBreak) { cycles++; isBreak = true; secondsLeft = 5 * 60 } else { isBreak = false; secondsLeft = 25 * 60 }
        }
    }
    ToolScaffold("Focus Timer", onBack, vm) {
        Text(if (isBreak) "Break — rest for 5 minutes" else "Focus — 25 minutes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60), style = MaterialTheme.typography.displayMedium, color = VioletSoft)
        Text("Completed focus sessions: $cycles", color = Mute)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ running = !running }) { Text(if (running) "Pause" else "Start") }
            Button({ running = false; isBreak = false; secondsLeft = 25 * 60 }) { Text("Reset") }
            Button({ running = false; isBreak = true; secondsLeft = 5 * 60 }) { Text("Break now") }
        }
    }
}

@Composable
fun CvScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(vm.store.userName) }
    var title by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf("") }
    var experience by remember { mutableStateOf("") }
    var education by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    ToolScaffold("CV Maker", onBack, vm) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Full name") })
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Job title") })
        OutlinedTextField(contact, { contact = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Phone / email") })
        OutlinedTextField(skills, { skills = it }, Modifier.fillMaxWidth(), label = { Text("Skills (one per line)") })
        OutlinedTextField(experience, { experience = it }, Modifier.fillMaxWidth(), label = { Text("Experience") })
        OutlinedTextField(education, { education = it }, Modifier.fillMaxWidth(), label = { Text("Education") })
        Button({
            val html = buildString {
                append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>CV — $name</title>")
                append("<style>body{font-family:sans-serif;max-width:700px;margin:40px auto;padding:0 20px;color:#111}h1{margin-bottom:0}h2{border-bottom:2px solid #6d28d9;padding-bottom:4px}li{margin:4px 0}</style></head><body>")
                append("<h1>${name.escapeHtml()}</h1><p>${title.escapeHtml()} • ${contact.escapeHtml()}</p>")
                append("<h2>Skills</h2><ul>${skills.lines().filter { it.isNotBlank() }.joinToString("") { "<li>${it.escapeHtml()}</li>" }}</ul>")
                append("<h2>Experience</h2><p>${experience.escapeHtml().replace("\n", "<br>")}</p>")
                append("<h2>Education</h2><p>${education.escapeHtml().replace("\n", "<br>")}</p>")
                append("<p><small>Made with RANI AI</small></p></body></html>")
            }
            val fileName = "RANI-CV-${name.ifBlank { "me" }.replace(Regex("[^A-Za-z0-9]+"), "-")}.html"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/html")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray(Charsets.UTF_8)) }
                saved = "CV saved to Downloads as $fileName"
            } else saved = "Could not save the CV — please try again."
        }, enabled = name.isNotBlank()) { Text("Save CV to Downloads") }
        saved?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun String.escapeHtml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

@Composable
fun VoiceStudioScreen(vm: FreeToolsViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf("Hello, I am RANI — your AI companion. How are you today?") }
    ToolScaffold("Voice Studio", onBack, vm) {
        Text("Hear RANI's voice, and find your voice notes.", color = Mute, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(sample, { sample = it }, Modifier.fillMaxWidth(), label = { Text("Text to speak") })
        Button({
            val c = (ctx.applicationContext as RaniApp).container
            scope.launch { c.tts.speak(sample) }
        }, enabled = sample.isNotBlank()) { Text("Speak it") }
        Button({ (ctx.applicationContext as RaniApp).container.tts.stop() }) { Text("Stop speaking") }
        ToolRow(Icons.Default.Mic, "Voice Notes & Recordings", "Your saved recordings", { onNav("recordings") })
        Text("Voice notes are recorded on this phone and stay on this phone.", color = Mute, style = MaterialTheme.typography.bodySmall)
    }
}

/* ---------- Call Logs / Dost Room / Modes ---------- */

@Composable
fun CallLogsScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val logs by vm.callLogs.collectAsState()
    val summary by vm.callLogSummary.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadCallLogs() }
    ToolScaffold("Call Logs", onBack, vm) {
        Text("Your last ${logs.size} conversations (max 20). Tap one for an AI summary.", color = Mute, style = MaterialTheme.typography.bodySmall)
        logs.forEach { (title, text) ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).clickable { selected = text; vm.summariseCallLog(text) }.padding(12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text.lines().take(2).joinToString(" "), color = Mute, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (logs.isEmpty()) Text("No conversations saved yet.", color = Mute)
        if (selected != null) {
            Text("AI Summary:", fontWeight = FontWeight.Bold, color = Gold)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun DostScreen(vm: FreeToolsViewModel, onBack: () -> Unit) {
    val room = "rani"
    val messages by vm.dostMessages.collectAsState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadDost(room) }
    ToolScaffold("Dost Room — rani", onBack, vm) {
        Text("A shared room for RANI and friends. You must be signed in — the room needs your login.", color = Mute, style = MaterialTheme.typography.bodySmall)
        Button({ vm.loadDost(room) }) { Text("Refresh") }
        messages.forEach { (name, msg) ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = VioletSoft)
                Text(msg)
            }
        }
        if (messages.isEmpty()) Text("No messages yet — say hello.", color = Mute)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Write a message") })
            Button({ if (text.isNotBlank()) { vm.postDost(room, text.trim()); text = "" } }) { Text("Send") }
        }
    }
}

@Composable
fun ModesScreen(vm: FreeToolsViewModel, home: HomeViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar("AI Modes", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pick a mode — RANI answers in that style until you clear it. The active mode shows as a bar on top of Chat.", color = Mute, style = MaterialTheme.typography.bodySmall)
            RaniModes.all.forEach { mode ->
                ToolRow(Icons.Default.Psychology, mode.title, mode.desc, {
                    // Modes with their own screen (website, CV, camera
                    // translator) open that screen; the rest set the mode
                    // and open Chat with the mode bar on top.
                    if (mode.route != null) onNav(mode.route) else { home.setMode(mode.id); onNav("chat") }
                })
            }
            // N3 fix: the 4 Rooms are prompt-preset cards INSIDE this grid
            // (§23.1 item 28) — they were defined but never rendered, so
            // the grid showed 22 cards while the file holds 26 modes.
            Text("Rooms", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp))
            RaniModes.rooms.forEach { mode ->
                ToolRow(Icons.Default.Group, mode.title, mode.desc, { home.setMode(mode.id); onNav("chat") })
            }
        }
    }
}

/* ---------- App Lock PIN gate ---------- */

@Composable
fun PinScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { (ctx.applicationContext as RaniApp).container.store }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, "Locked", tint = VioletSoft, modifier = Modifier.size(48.dp))
        Text("RANI is locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text("Enter your PIN to open RANI.", color = Mute, modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(pin, { pin = it.filter { ch -> ch.isDigit() }.take(8) }, Modifier.fillMaxWidth().padding(top = 20.dp), singleLine = true,
            label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation())
        error?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 8.dp)) }
        Button({
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if (hash == store.appLockHash) onUnlocked() else error = "Wrong PIN — please try again."
        }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = pin.length >= 4) { Text("Unlock") }
    }
}
