package com.badshah222.rani.ai.features.media

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * MYRA MediaPage — camera vision, screen share, media keys, screenshots.
 * Camera vision: photo lo, RANI Gemini vision se batayegi us mein kya hai.
 * Screenshots: sirf tumhare phone ke Screenshots folder ki aakhri 8 tasveerein.
 */
class MediaViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val visionResult = MutableStateFlow<String?>(null)
    val visionBusy = MutableStateFlow(false)
    val shots = MutableStateFlow<List<Bitmap>>(emptyList())

    fun loadShots(ctx: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 29) return // loadThumbnail needs API 29+
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val out = mutableListOf<Bitmap>()
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(MediaStore.Images.Media._ID)
                val sel = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?"
                runCatching {
                    ctx.contentResolver.query(uri, proj, sel, arrayOf("Screenshots"), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
                        val idCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        var n = 0
                        while (cur.moveToNext() && n < 8) {
                            val id = cur.getLong(idCol)
                            val u = android.content.ContentUris.withAppendedId(uri, id)
                            runCatching { ctx.contentResolver.loadThumbnail(u, android.util.Size(360, 360), null) }.getOrNull()?.let { out += it; n++ }
                        }
                    }
                }
                out
            }
            shots.value = list
        }
    }

    fun describePhoto(bmp: Bitmap) {
        visionBusy.value = true; visionResult.value = null
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                }
                val text = c.ai.describeImage(bytes, "Is photo mein kya dikh raha hai? Roman Urdu mein tafseel se batao — cheezein, log, text, rang.")
                visionResult.value = text
            } catch (e: AiException) { visionResult.value = "Vision nahi chala: ${e.userMessage}" }
            catch (e: Exception) { visionResult.value = "Vision nahi chala — dobara try karo." }
            finally { visionBusy.value = false }
        }
    }

    fun mediaKey(code: Int) {
        val am = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}

@Composable
fun MediaScreen(vm: MediaViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    val ctx = LocalContext.current
    val vision by vm.visionResult.collectAsState(); val visionBusy by vm.visionBusy.collectAsState(); val shots by vm.shots.collectAsState()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> if (bmp != null) vm.describePhoto(bmp) }
    LaunchedEffect(Unit) { vm.loadShots(ctx) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Media", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera vision", style = MaterialTheme.typography.titleMedium, color = Gold)
                        Text("Photo lo — RANI batayegi us mein kya hai (Gemini vision, tumhari key).", color = Mute, style = MaterialTheme.typography.bodySmall)
                        Button({ camera.launch(null) }, enabled = !visionBusy) { if (visionBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Photo lo aur poocho") }
                        vision?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Screen share", style = MaterialTheme.typography.titleMedium, color = Gold)
                        Text("Apni screen RANI ko dikhao — wo dekhegi aur madad karegi. Screen Guardian bhi yahin se chalta hai.", color = Mute, style = MaterialTheme.typography.bodySmall)
                        Button({ onNav("screen") }) { Text("Screen share kholo") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Media keys", style = MaterialTheme.typography.titleMedium, color = Gold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton({ vm.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                            IconButton({ vm.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }) { Icon(Icons.Default.PlayArrow, "Play/Pause") }
                            IconButton({ vm.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }) { Icon(Icons.Default.SkipNext, "Next") }
                            IconButton({ vm.mediaKey(KeyEvent.KEYCODE_VOLUME_MUTE) }) { Icon(Icons.Default.VolumeOff, "Mute") }
                        }
                    }
                }
            }
            item { Text("Screenshots — aakhri 8", style = MaterialTheme.typography.titleMedium, color = Gold) }
            item {
                if (shots.isEmpty()) Text("Koi screenshot nahi mila.", color = Mute, style = MaterialTheme.typography.bodySmall)
                else LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shots) { bmp -> Image(bmp.asImageBitmap(), "Screenshot", Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Crop) }
                }
            }
        }
    }
}
