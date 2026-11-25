package com.badshah222.rani.ai.features.recordings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.badshah222.rani.ai.core.ScreenRecordings
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recordings library — what RANI saw, saved as on-device MP4s.
 * Files live in the app's own Movies/RANI folder and are never uploaded.
 * Play opens the phone's video player; Delete removes the file for good.
 */
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    fun reload() {
        files = ScreenRecordings.list(ctx)
    }
    LaunchedEffect(Unit) { reload() }

    fun play(f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    fun sizeText(f: File): String {
        val kb = f.length() / 1024
        return if (kb >= 1024) "%.1f MB".format(kb / 1024f) else "$kb KB"
    }

    fun dateText(f: File): String =
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(f.lastModified()))

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Screen recordings", onBack)
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Videocam, null, tint = VioletSoft, modifier = Modifier.size(56.dp))
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Start a recording from the Camera View screen. Videos are saved on this phone only — never uploaded.",
                    color = Mute, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(files, key = { it.absolutePath }) { f ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Videocam, null, tint = VioletSoft)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("${dateText(f)} · ${sizeText(f)}", style = MaterialTheme.typography.bodySmall, color = Mute)
                                Text("On this phone only", style = MaterialTheme.typography.labelSmall, color = Ok)
                            }
                            IconButton({ play(f) }) { Icon(Icons.Default.PlayArrow, "Play") }
                            IconButton({ confirmDelete = f }) { Icon(Icons.Default.Delete, "Delete", tint = Bad) }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("“${f.name}” will be deleted from this phone for good.") },
            confirmButton = {
                TextButton({
                    ScreenRecordings.delete(f)
                    confirmDelete = null
                    reload()
                }) { Text("Delete", color = Bad) }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep") } }
        )
    }
}
