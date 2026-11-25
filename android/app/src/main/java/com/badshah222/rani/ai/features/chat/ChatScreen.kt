package com.badshah222.rani.ai.features.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.features.home.ActionDialogs
import com.badshah222.rani.ai.features.home.HomeViewModel
import com.badshah222.rani.ai.ui.character.CharState
import com.badshah222.rani.ai.ui.orb.VoiceOrb
import com.badshah222.rani.ai.ui.theme.*

/** The text conversation, LIA-style: orb avatar header, violet user bubbles, panel assistant bubbles. */
@Composable
fun ChatScreen(vm: HomeViewModel, onBack: () -> Unit, onVoice: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by vm.charState.collectAsState(); val level by vm.micLevel.collectAsState()
    val messages by vm.messages.collectAsState(); val error by vm.error.collectAsState()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startListening() else vm.status.value = "Microphone permission denied. Open Permissions to enable it." }
    fun mic() { if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startListening() else micPerm.launch(Manifest.permission.RECORD_AUDIO) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Image(painterResource(R.drawable.rani_face), "RANI", Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("RANI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Here to help", style = MaterialTheme.typography.bodySmall, color = Mute)
            }
            IconButton({ vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
        }
        HorizontalDivider(color = Panel2)

        error?.let { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Bad.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e, Modifier.weight(1f), color = Bad, style = MaterialTheme.typography.bodySmall)
                TextButton(vm::dismissError) { Text("Dismiss") }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceOrb(CharState.Idle, 0f, vm.lowPower, Modifier.size(90.dp))
                        Text("Ask me anything, or tap the mic and speak.", color = Mute, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(messages.takeLast(60)) { m -> MessageBubble(m, vm, clipboard) }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.readBytes() }.getOrNull()
                    if (bytes != null) vm.askPhoto(bytes)
                }
            }
            IconButton({ photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.PhotoLibrary, "Photo bhejo — AI dekh kar batayega", tint = VioletSoft)
            }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message RANI…") }, maxLines = 4, shape = RoundedCornerShape(26.dp))
            IconButton({ if (state == CharState.Listening) vm.stopListening() else mic() }) {
                Icon(if (state == CharState.Listening) Icons.Default.Stop else Icons.Default.Mic, "Voice input", tint = if (state == CharState.Listening) Ok else VioletSoft)
            }
            FilledIconButton({ if (text.isNotBlank()) { vm.send(text); text = "" } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet, contentColor = Color.White)) {
                Icon(Icons.Default.Send, "Send")
            }
        }
    }
    ActionDialogs(vm)
}

@Composable
private fun MessageBubble(m: ChatMessage, vm: HomeViewModel, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val mine = m.role == "user"
    val ctx = LocalContext.current
    if (!mine && m.content.startsWith("image_url:")) {
        val url = m.content.removePrefix("image_url:")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
            VoiceOrb(CharState.Idle, 0f, vm.lowPower, Modifier.padding(end = 6.dp, top = 2.dp).size(26.dp))
            Column {
                UrlImage(url, Modifier.widthIn(max = 300.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)))
                Text("Generated image · link ~1 ghante valid hai", color = Mute, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        return
    }
    if (!mine && m.content.startsWith("image_file:")) {
        val path = m.content.removePrefix("image_file:")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
            VoiceOrb(CharState.Idle, 0f, vm.lowPower, Modifier.padding(end = 6.dp, top = 2.dp).size(26.dp))
            Column {
                FileImage(path, Modifier.widthIn(max = 300.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)))
                Text("Generated image · Hugging Face", color = Mute, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        return
    }
    val codeFiles = remember(m.content) { if (mine) emptyList() else extractCodeFiles(m.content) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) VoiceOrb(CharState.Idle, 0f, vm.lowPower, Modifier.padding(end = 6.dp, top = 2.dp).size(26.dp))
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .background(
                        if (mine) Brush.horizontalGradient(listOf(VioletDeep, Violet)) else Brush.horizontalGradient(listOf(Panel, Panel)),
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) { Text(m.content, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            if (!mine) {
                Row {
                    IconButton({ clipboard.setText(AnnotatedString(m.content)) }, Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp), tint = Mute) }
                    IconButton({ vm.replay(m.content) }, Modifier.size(32.dp)) { Icon(Icons.Default.VolumeUp, "Listen", Modifier.size(16.dp), tint = Mute) }
                }
                if (codeFiles.isNotEmpty()) {
                    OutlinedButton({
                        vm.status.value = saveCodeZip(ctx, codeFiles)
                    }) { Icon(Icons.Default.FolderZip, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Download ZIP (${codeFiles.size} files)") }
                }
            }
        }
    }
}

/** Loads a remote image with the shared HTTP client (no extra image library needed). */
@Composable
private fun UrlImage(url: String, modifier: Modifier = Modifier) {
    val bmp by produceState<android.graphics.Bitmap?>(null, url) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(url).build()
                com.badshah222.rani.ai.data.ai.http.newCall(req).execute().use { r -> r.body?.byteStream()?.let { android.graphics.BitmapFactory.decodeStream(it) } }
            }.getOrNull()
        }
    }
    if (bmp != null) Image(bmp!!.asImageBitmap(), "Generated image", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Panel2), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VioletSoft) }
}

@Composable
private fun FileImage(path: String, modifier: Modifier = Modifier) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    if (bmp != null) Image(bmp!!.asImageBitmap(), "Generated image", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Panel2), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VioletSoft) }
}

/** Pulls fenced code blocks out of an assistant reply and names each file (from the fence label when given). */
fun extractCodeFiles(content: String): List<Pair<String, String>> {
    val rx = Regex("""```([A-Za-z0-9]*)[ ]*([A-Za-z0-9._-]*)\s*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    val used = mutableMapOf<String, Int>()
    fun unique(name: String): String {
        val n = (used[name] ?: 0) + 1; used[name] = n
        if (n == 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + n + name.substring(dot) else name + n
    }
    return rx.findAll(content).map { m ->
        val lang = m.groupValues[1].lowercase()
        val named = m.groupValues[2]
        val base = when {
            named.contains('.') -> named
            lang == "html" || lang == "htm" -> "index.html"
            lang == "css" -> "style.css"
            lang == "javascript" || lang == "js" -> "script.js"
            lang == "kotlin" || lang == "kt" -> "Main.kt"
            lang == "python" || lang == "py" -> "main.py"
            lang == "json" -> "data.json"
            lang.isNotBlank() -> "file.$lang"
            else -> "file.txt"
        }
        unique(base) to m.groupValues[3].trim()
    }.toList()
}

/** Packs the files into a ZIP in the phone's Downloads folder and returns a status line. */
fun saveCodeZip(ctx: android.content.Context, files: List<Pair<String, String>>): String {
    val name = "badshah-code-${System.currentTimeMillis() / 1000}.zip"
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "ZIP nahi ban saka."
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                java.util.zip.ZipOutputStream(out).use { z ->
                    files.forEach { (n, c) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry() }
                }
            } ?: return "ZIP nahi ban saka."
            "ZIP Downloads mein save ho gaya: $name"
        } else {
            val f = java.io.File(ctx.cacheDir, name)
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(f)).use { z ->
                files.forEach { (n, c) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            }
            "ZIP ban gaya: ${f.absolutePath}"
        }
    } catch (e: Exception) { "ZIP banane mein masla: ${e.message?.take(60) ?: "unknown"}" }
}
