package com.badshah222.rani.ai.features.webforge

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.features.chat.extractCodeFiles
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Prompt handed over from Web Search ("Build this as a website"). */
object WebForgeShared { var prompt: String = "" }

private val WEBFORGE_SYSTEM = """
You are RANI's WebForge — a website builder. The user describes a website; you return ONE complete, ready-to-use website.
Rules: return the ENTIRE website as a single HTML file inside ONE fenced code block labeled with its filename, like ```html index.html.
The HTML must be complete and self-contained (inline CSS + JS, no external local files). Make it beautiful, modern and mobile-friendly:
dark purple-neon RANI style unless the user asked for another theme. Never cut the file short. No explanation outside the code block
except one short line before it. Reply in the user's language.
""".trimIndent()

class WebForgeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val prompt = MutableStateFlow(WebForgeShared.prompt.also { WebForgeShared.prompt = "" })
    val plan = MutableStateFlow("")
    val html = MutableStateFlow<String?>(null)
    val fileName = MutableStateFlow("index.html")
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val repo = MutableStateFlow("")
    val ghTokenSaved = MutableStateFlow(c.store.miscSecret("github_token") != null)

    /** MYRA planner_agent behaviour: detect the site type + theme from the words, name the project. */
    fun planSite(): String {
        val p = prompt.value.lowercase()
        val type = when {
            p.contains("store") || p.contains("shop") || p.contains("dukan") || p.contains("sell") -> "online store"
            p.contains("portfolio") || p.contains("personal") || p.contains("cv") || p.contains("resume") -> "portfolio"
            p.contains("blog") -> "blog"
            p.contains("game") -> "game"
            p.contains("restaurant") || p.contains("food") || p.contains("menu") -> "restaurant"
            else -> "landing page"
        }
        val theme = when {
            p.contains("light") || p.contains("white") || p.contains("halka") -> "light"
            p.contains("neon") -> "neon purple"
            p.contains("minimal") || p.contains("saada") -> "minimal"
            else -> "dark purple-neon (RANI style)"
        }
        val name = prompt.value.split(Regex("[^A-Za-z0-9]+")).filter { it.length > 2 }.take(3).joinToString("-").lowercase().ifBlank { "rani-site" }
        return "Type: $type · Theme: $theme · Project: $name"
    }

    fun generate() {
        val p = prompt.value.trim()
        if (p.isEmpty()) { status.value = "Pehle batao kaisi website chahiye, Babu Jaan."; return }
        plan.value = planSite()
        busy.value = true; status.value = "Website ban rahi hai…"
        viewModelScope.launch {
            try {
                val reply = c.ai.chat(listOf(ChatMessage("user", "Build this website (${plan.value}): $p")), systemOverride = WEBFORGE_SYSTEM)
                val files = extractCodeFiles(reply.text)
                val main = files.firstOrNull { it.first.endsWith(".html", ignoreCase = true) } ?: files.firstOrNull()
                if (main == null) { status.value = "Website ka code nahi mila — prompt badal kar dobara try karo, Babu Jaan."; return@launch }
                fileName.value = main.first; html.value = main.second
                status.value = "Website tayyar hai, Babu Jaan — neeche preview dekho."
            } catch (e: AiException) { status.value = e.userMessage }
            catch (e: Exception) { status.value = "Website nahi bani — internet ya key check karo." }
            finally { busy.value = false }
        }
    }

    fun download(ctx: android.content.Context) {
        val h = html.value ?: return
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName.value)
                            put(MediaStore.Downloads.MIME_TYPE, "text/html")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/RANI")
                        }
                        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@runCatching "Download nahi ho saka."
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(h.toByteArray()) }
                        "Downloads/RANI/${fileName.value} mein save ho gaya, Babu Jaan."
                    } else {
                        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "RANI").apply { mkdirs() }
                        val f = java.io.File(dir, fileName.value)
                        f.writeText(h)
                        "Downloads/RANI/${fileName.value} mein save ho gaya, Babu Jaan."
                    }
                }.getOrElse { "Download nahi ho saka: ${it.message}" }
            }
            status.value = msg
        }
    }

    fun saveGhToken(t: String) { c.store.setMiscSecret("github_token", t.ifBlank { null }); ghTokenSaved.value = c.store.miscSecret("github_token") != null }

    /** Deploy the generated file to a GitHub repo (user's own token, stored encrypted on-device). */
    fun deployToGitHub() {
        val h = html.value ?: return
        val token = c.store.miscSecret("github_token")
        if (token.isNullOrBlank()) { status.value = "Pehle apna GitHub token save karo (neeche) — wo sirf is phone par encrypted rehta hai."; return }
        val r = repo.value.trim()
        if (!r.contains("/")) { status.value = "Repo aise likho: owner/repo — jaise Badshah-official512/RANI-AI-ASSISTANT"; return }
        busy.value = true; status.value = "GitHub par deploy ho raha hai…"
        viewModelScope.launch {
            try {
                val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val path = fileName.value
                val api = "https://api.github.com/repos/$r/contents/$path"
                fun req(b: okhttp3.Request.Builder) = b.header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
                val get = client.newCall(req(Request.Builder().url(api)).get().build()).execute()
                val sha = if (get.isSuccessful) JSONObject(get.body?.string().orEmpty()).optString("sha").takeIf { it.isNotBlank() } else null
                val body = JSONObject().put("message", "RANI WebForge: ${fileName.value}").put("content", Base64.encodeToString(h.toByteArray(), Base64.NO_WRAP)).put("branch", "main")
                if (sha != null) body.put("sha", sha)
                val put = client.newCall(req(Request.Builder().url(api)).put(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
                status.value = if (put.isSuccessful) "Deploy ho gaya, Babu Jaan — https://github.com/$r/blob/main/$path" else "Deploy nahi hua (HTTP ${put.code}) — token ya repo check karo."
            } catch (e: Exception) { status.value = "Deploy nahi hua: ${e.message?.take(120)}" }
            finally { busy.value = false }
        }
    }
}

@Composable
fun WebForgeScreen(vm: WebForgeViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prompt by vm.prompt.collectAsState(); val plan by vm.plan.collectAsState()
    val html by vm.html.collectAsState(); val fileName by vm.fileName.collectAsState()
    val busy by vm.busy.collectAsState(); val status by vm.status.collectAsState()
    val repo by vm.repo.collectAsState(); val ghSaved by vm.ghTokenSaved.collectAsState()
    var tokenDraft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Website Builder — WebForge", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Batao kaisi website chahiye — RANI poori website bana degi (MYRA WebForge jaisi).", color = Mute, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(prompt, { vm.prompt.value = it }, Modifier.fillMaxWidth(), label = { Text("Website ka idea…") }, minLines = 3, shape = RoundedCornerShape(16.dp))
            Button(vm::generate, Modifier.fillMaxWidth(), enabled = !busy) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Website banao") }
            if (plan.isNotBlank()) Text(plan, color = Gold, style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, color = if (it.contains("nahi") || it.contains("nakaam")) Bad else Ok, style = MaterialTheme.typography.bodySmall) }
            html?.let { h ->
                Text("Preview — $fileName", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth().height(420.dp), shape = RoundedCornerShape(16.dp)) {
                    AndroidView({ android.webkit.WebView(it).apply { settings.javaScriptEnabled = true } }, Modifier.fillMaxSize()) { wv -> wv.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.download(ctx) }) { Text("Download .html") }
                    OutlinedButton({ vm.prompt.value = prompt; vm.generate() }) { Text("Dobara banao") }
                }
                Text("GitHub par deploy (optional)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(repo, { vm.repo.value = it }, Modifier.fillMaxWidth(), label = { Text("owner/repo") }, singleLine = true)
                OutlinedTextField(tokenDraft, { tokenDraft = it }, Modifier.fillMaxWidth(), label = { Text(if (ghSaved) "GitHub token saved ✓ (naya lagana hai to likho)" else "GitHub token (repo scope)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.saveGhToken(tokenDraft); tokenDraft = "" }) { Text("Save token") }
                    Button(vm::deployToGitHub, enabled = !busy) { Text("Deploy to GitHub") }
                }
                Text("Token sirf is phone par encrypted rehta hai — kabhi upload nahi hota.", color = Mute, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
