package com.badshah222.rani.ai.features.browser

import android.app.Application
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.features.home.HomeViewModel
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val summary = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    /** Reads a web page's text and explains it in the user's own mix, out loud as well. */
    fun summarize(pageText: String) {
        if (pageText.isBlank()) { summary.value = "Is page par parhne layak text nahi mila, Babu Jaan."; return }
        viewModelScope.launch {
            busy.value = true
            try {
                val r = c.ai.chat(
                    listOf(ChatMessage("user", "Is web page ko short aur asaan alfaaz mein samjhao, main points ke saath. Page ka text ye hai:\n\n" + pageText.take(6000)))
                )
                summary.value = r.text
                c.tts.speak(r.text)
            } catch (e: Exception) {
                summary.value = "Page parh liya, lekin samjhane mein masla aya: ${e.message?.take(90)}"
            } finally { busy.value = false }
        }
    }

    fun stopSpeaking() { c.tts.stop() }
    fun clearSummary() { summary.value = null; c.tts.stop() }
}

/** Turns typed text into a URL: full links pass through, domains get https://, anything else becomes a Google search. */
private fun toUrl(input: String): String {
    val t = input.trim()
    if (t.startsWith("http://") || t.startsWith("https://")) return t
    if (!t.contains(" ") && t.contains(".")) return "https://$t"
    return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
}

/**
 * The assistant's OWN browser, inside the app: the user sees every page on screen and
 * keeps full control (address bar, back/forward, reload), and the assistant can read
 * the open page and explain it out loud. Opened by voice ("browser kholo …"), from
 * Quick actions, or with a pending search from chat.
 */
@Composable
fun BrowserScreen(home: HomeViewModel, vm: BrowserViewModel, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf("") }
    val summary by vm.summary.collectAsState()
    val busy by vm.busy.collectAsState()

    fun load(input: String) {
        if (input.isBlank()) return
        address = input
        webView?.loadUrl(toUrl(input))
    }

    // A voice/chat request like "browser mein cricket search karo" lands here.
    LaunchedEffect(Unit) {
        home.pendingBrowser.value?.takeIf { it.isNotBlank() }?.let { address = it }
        home.pendingBrowser.value = null
    }
    LaunchedEffect(webView, address) {
        val wv = webView ?: return@LaunchedEffect
        if (address.isNotBlank() && wv.url == null) wv.loadUrl(toUrl(address))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Assistant browser", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                address, { address = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Search karo ya website likho…") },
                shape = RoundedCornerShape(14.dp),
            )
            Button({ load(address) }, shape = RoundedCornerShape(14.dp)) { Text("Go") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton({ webView?.goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            IconButton({ webView?.goForward() }) { Icon(Icons.Default.ArrowForward, "Forward") }
            IconButton({ webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
            IconButton({
                webView?.evaluateJavascript("(function(){return document.body ? document.body.innerText.slice(0,6000) : ''})()") { result ->
                    val text = runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
                    vm.summarize(text)
                }
            }) { Icon(Icons.Default.MenuBook, "Page parh kar samjhao") }
            if (summary != null) IconButton({ vm.stopSpeaking() }) { Icon(Icons.Default.Stop, "Bolna band karo") }
            Text(
                "📖 button = page parh kar samjhao + sunao",
                Modifier.padding(start = 6.dp, top = 12.dp), color = Mute, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet)
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl("https://www.google.com")
                }
            },
            update = { webView = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (summary != null) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(14.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary!!, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ vm.clearSummary() }) { Text("Band karo") }
                }
            }
        }
    }
}
