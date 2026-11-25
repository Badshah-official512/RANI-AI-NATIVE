package com.badshah222.rani.ai.features.tools

import android.app.Application
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.data.ai.Pollinations
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.domain.RaniTools
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * MYRA Apps & Tools (myra_pages.py AppsToolsPage.AI_TOOLS) — the same 7 tools,
 * the same ask-prompts and the same Gemini templates, on Android.
 * Generate Image uses the Gemini image model (Pollinations free URL as fallback);
 * Create PDF writes a real PDF to Downloads; Analyze File reads one of RANI's
 * own generated files; the rest run through RANI's Gemini chat.
 */
class AiToolsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val result = MutableStateFlow<String?>(null)
    val imageBytes = MutableStateFlow<ByteArray?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun runTool(tool: RaniTools.AiTool, input: String) {
        val x = input.trim()
        if (tool.name == "Analyze File") { /* handled by runAnalyzeFile */ return }
        if (x.isEmpty()) { error.value = "Write something first."; return }
        busy.value = true; error.value = null; result.value = null; imageBytes.value = null
        viewModelScope.launch {
            try {
                when (tool.name) {
                    "Generate Image" -> {
                        val bytes = runCatching { c.ai.generateImage(x) }.getOrElse {
                            // Free fallback: the Pollinations URL renders the picture.
                            result.value = "Gemini could not create the image — showing a free image instead:\n${Pollinations.imageUrl(x)}"
                            return@launch
                        }
                        val dir = File(getApplication<Application>().cacheDir, "images").apply { mkdirs() }
                        val f = File(dir, "tool_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
                        imageBytes.value = bytes
                        result.value = "Image is ready. Saved: ${f.name}"
                    }
                    "Create PDF" -> {
                        val reply = c.ai.chat(listOf(ChatMessage("user", tool.template.replace("{x}", x))))
                        val path = writePdf(tool.name, reply.text)
                        result.value = "PDF is ready.\n${reply.text.take(600)}\n\nSaved: $path"
                    }
                    else -> {
                        val reply = c.ai.chat(listOf(ChatMessage("user", tool.template.replace("{x}", x))))
                        result.value = reply.text
                    }
                }
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "command", mapOf("tool" to "ai_tools")) }
            } catch (e: AiException) { error.value = e.userMessage }
            catch (e: Exception) { error.value = "Something went wrong: ${e.message?.take(120)}" }
            finally { busy.value = false }
        }
    }

    fun runAnalyzeFile(file: File) {
        busy.value = true; error.value = null; result.value = null
        viewModelScope.launch {
            try {
                val text = runCatching { file.readText().take(6000) }.getOrElse { "[binary file: ${file.name}]" }
                val reply = c.ai.chat(listOf(ChatMessage("user", RaniTools.aiTools[5].template.replace("{x}", "${file.name}:\n$text"))))
                result.value = reply.text
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "command", mapOf("tool" to "ai_tools_file")) }
            } catch (e: AiException) { error.value = e.userMessage }
            catch (e: Exception) { error.value = "Could not read the file: ${e.message?.take(120)}" }
            finally { busy.value = false }
        }
    }

    private fun writePdf(title: String, text: String): String {
        val ctx = getApplication<Application>()
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val paint = android.graphics.Paint().apply { textSize = 13f; color = android.graphics.Color.BLACK }
        val titlePaint = android.graphics.Paint().apply { textSize = 20f; isFakeBoldText = true; color = android.graphics.Color.BLACK }
        page.canvas.drawText(title, 40f, 60f, titlePaint)
        var y = 100f
        text.split("\n").forEach { line ->
            var rest = line
            while (rest.isNotEmpty()) {
                val take = minOf(rest.length, 72)
                page.canvas.drawText(rest.take(take), 40f, y, paint)
                y += 20f; rest = rest.drop(take)
                if (y > 800f) break
            }
            if (y > 800f) return@forEach
        }
        doc.finishPage(page)
        val name = "RANI_${System.currentTimeMillis()}.pdf"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Downloads could not be opened")
            ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close()
            "Downloads/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(dir, name)
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            f.absolutePath
        }
    }

    fun generatedFiles(): List<File> {
        val ctx = getApplication<Application>()
        val dirs = listOf(File(ctx.cacheDir, "images"), File(ctx.filesDir, "webforge"), File(ctx.cacheDir, "images"))
        return dirs.filter { it.exists() }.flatMap { it.listFiles()?.toList().orEmpty() }.sortedByDescending { it.lastModified() }.take(20)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiToolsScreen(vm: AiToolsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<RaniTools.AiTool?>(null) }
    var input by remember { mutableStateOf("") }
    val result by vm.result.collectAsState()
    val imageBytes by vm.imageBytes.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val files = remember { vm.generatedFiles() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("AI Tools", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("AI Tools — with RANI", style = MaterialTheme.typography.bodyMedium, color = Mute)
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RaniTools.aiTools) { t ->
                        Card({ selected = t; input = ""; vm.result.value = null; vm.imageBytes.value = null }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            selected?.let { tool ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tool.name, style = MaterialTheme.typography.titleMedium, color = VioletSoft)
                            if (tool.name == "Analyze File") {
                                Text("Apni banayi hui files mein se chuno:", style = MaterialTheme.typography.bodySmall, color = Mute)
                                if (files.isEmpty()) Text("No files yet — create something with WebForge or Image Studio first.", style = MaterialTheme.typography.bodySmall, color = Mute)
                                files.forEach { f ->
                                    OutlinedButton({ vm.runAnalyzeFile(f) }, Modifier.fillMaxWidth()) { Text(f.name, maxLines = 1) }
                                }
                            } else {
                                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text(tool.ask ?: "Likho…") }, minLines = 3, maxLines = 6)
                                Button({ vm.runTool(tool, input) }, enabled = !busy) {
                                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Chalao")
                                }
                            }
                            error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
                            imageBytes?.let { bytes ->
                                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                bmp?.let { Image(it.asImageBitmap(), "Generated image", Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Crop) }
                            }
                            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}
