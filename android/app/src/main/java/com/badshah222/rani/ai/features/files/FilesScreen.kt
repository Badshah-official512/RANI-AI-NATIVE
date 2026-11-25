package com.badshah222.rani.ai.features.files

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class GenFile(val file: File) {
    val kind: String get() = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp" -> "image"
        "html", "htm" -> "html"
        "zip" -> "zip"
        "pdf" -> "pdf"
        else -> "file"
    }
}

/**
 * MYRA FilesPage — RANI ki banayi hui files: images, websites, ZIPs, PDFs.
 * Open, share, delete, ya Gemini se summarize karwao. Sirf RANI ki files
 * dikhti hain (Downloads/RANI + app cache/images).
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val files = MutableStateFlow<List<GenFile>>(emptyList())
    val summary = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<String?>(null)

    fun load() {
        val ctx = getApplication<Application>()
        val out = mutableListOf<File>()
        File(ctx.cacheDir, "images").listFiles()?.let { out += it }
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RANI").listFiles()?.let { out += it }
        files.value = out.filter { it.isFile }.sortedByDescending { it.lastModified() }.map { GenFile(it) }
    }

    fun delete(f: GenFile) { if (f.file.delete()) { load(); status.value = "${f.file.name} delete kar diya." } }
    fun summarize(f: GenFile) {
        if (f.kind == "image" || f.kind == "zip") { status.value = "This file cannot be summarised — choose a text/HTML/PDF file."; return }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { f.file.readText().take(12000) }
                val reply = c.ai.chat(listOf(ChatMessage("user", "Is file ka short summary do (key points):\n$text")))
                summary.value = reply.text
            } catch (e: AiException) { status.value = e.userMessage }
            catch (e: Exception) { status.value = "Could not summarise." }
        }
    }
}

fun fileUri(ctx: android.content.Context, f: File): Uri =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

@Composable
fun FilesScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files by vm.files.collectAsState(); val summary by vm.summary.collectAsState(); val status by vm.status.collectAsState()
    var preview by remember { mutableStateOf<GenFile?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("My Files", onBack)
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Mute, style = MaterialTheme.typography.bodySmall) }
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No files yet — ask RANI to make an image or a website and it will appear here.", Modifier.padding(24.dp), color = Mute)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files) { g ->
                Card(Modifier.fillMaxWidth().clickable { preview = g }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.file.name, style = MaterialTheme.typography.titleMedium)
                            Text("${g.kind} · ${(g.file.length() / 1024).coerceAtLeast(1)} KB", color = Mute, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ vm.summarize(g) }) { Text("✦", color = Gold) }
                        IconButton({
                            val uri = fileUri(ctx, g.file)
                            ctx.startActivity(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Icon(Icons.Default.Share, "Share") }
                        IconButton({ vm.delete(g) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    preview?.let { g ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(g.file.name) },
            text = {
                if (g.kind == "image") {
                    val bmp = remember(g) { BitmapFactory.decodeFile(g.file.absolutePath) }
                    if (bmp != null) Image(bmp.asImageBitmap(), g.file.name, Modifier.fillMaxWidth().height(320.dp), contentScale = ContentScale.Crop)
                    else Text("Image could not be opened.")
                } else Text("Press Share to open it, or open Downloads/RANI in the Files app.")
            },
            confirmButton = {
                TextButton({
                    preview = null
                    val uri = fileUri(ctx, g.file)
                    val mime = when (g.kind) { "html" -> "text/html"; "pdf" -> "application/pdf"; "zip" -> "application/zip"; else -> "*/*" }
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open") }
            },
            dismissButton = { TextButton({ preview = null }) { Text("Close") } },
        )
    }
    summary?.let { s ->
        AlertDialog(onDismissRequest = { vm.summary.value = null }, title = { Text("Summary") }, text = { Text(s) },
            confirmButton = { TextButton({ vm.summary.value = null }) { Text("OK") } })
    }
}
