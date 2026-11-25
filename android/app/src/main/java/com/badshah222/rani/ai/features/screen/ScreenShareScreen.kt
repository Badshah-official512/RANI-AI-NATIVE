package com.badshah222.rani.ai.features.screen

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.ScreenShareService
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class ScreenViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val answer = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val auto = MutableStateFlow(false)
    private var lastAskedAt = 0L

    fun ask(question: String) {
        val bmp = ScreenShareService.frame.value ?: run {
            answer.value = "First start screen share below — then ask, and I will look and tell you."
            return
        }
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val scaled = scaleDown(bmp, 900)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 68, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                val full = "The user is sharing their phone screen, and this is a picture of that screen. " +
                    "Look at the screen carefully — what is open, and whether there is any error or problem — then answer this question, " +
                    "in simple words, with step-by-step guidance if needed:\n" + question
                val r = c.ai.vision(full, b64)
                answer.value = r.text
                lastAskedAt = System.currentTimeMillis()
                c.tts.speak(r.text)
            } catch (e: AiException) {
                answer.value = e.userMessage
            } catch (e: Exception) {
                answer.value = "Could not read the screen: ${e.message?.take(90)}"
            } finally { busy.value = false }
        }
    }

    /** Auto mode: while sharing, re-analyze the screen at most every ~10 seconds. */
    fun maybeAutoAsk() {
        if (auto.value && !busy.value && ScreenShareService.running.value &&
            System.currentTimeMillis() - lastAskedAt > 10000
        ) {
            ask("What is happening on this screen? If there is an error or problem, explain it and tell me the next step.")
        }
    }

    fun stopSpeaking() { c.tts.stop() }

    private fun scaleDown(bmp: Bitmap, maxW: Int): Bitmap {
        if (bmp.width <= maxW) return bmp
        val h = (bmp.height.toLong() * maxW / bmp.width).toInt()
        return Bitmap.createScaledBitmap(bmp, maxW, h, true)
    }
}

/**
 * Screen Share — the user shares their screen ONLY through Android's own consent
 * dialog; RANI sees frames, answers questions about them out loud, and a
 * persistent notification + this screen make the sharing visible at all times.
 */
@Composable
fun ScreenShareScreen(vm: ScreenViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val frame by ScreenShareService.frame.collectAsState()
    val sharing by ScreenShareService.running.collectAsState()
    val answer by vm.answer.collectAsState()
    val busy by vm.busy.collectAsState()
    val auto by vm.auto.collectAsState()
    var question by remember { mutableStateOf("") }

    val mpm = remember { ctx.getSystemService(MediaProjectionManager::class.java) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            ScreenShareService.start(ctx, res.resultCode, res.data!!)
        }
    }

    LaunchedEffect(auto, sharing) {
        while (true) { delay(3000); vm.maybeAutoAsk() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Screen Share", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                Text(
                    "🔒 Privacy: the screen is only visible when YOU press Start and give Android permission. " +
                        "While sharing is on, a notification stays at the top. The moment you press Stop, everything stops — nothing is secretly recorded.",
                    Modifier.padding(12.dp), color = Mute, style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(16.dp)).background(Panel2),
                contentAlignment = Alignment.Center,
            ) {
                val f = frame
                if (f != null) {
                    Image(f.asImageBitmap(), "Shared screen", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text(if (sharing) "Screen is coming through…" else "Screen preview will appear here", color = Mute)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sharing) {
                    Button({ launcher.launch(mpm.createScreenCaptureIntent()) }, shape = RoundedCornerShape(14.dp)) { Text("▶ Share My Screen") }
                } else {
                    Button({ ScreenShareService.stop(ctx); vm.stopSpeaking() }, shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Bad)) { Text("■ Stop sharing") }
                    FilterChip(auto, { vm.auto.value = !auto }, { Text("Auto samjhao") })
                }
            }
            if (sharing) {
                Text("Ask the AI about the screen:", color = Mute, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { vm.ask("What is on this screen? Tell me everything, briefly.") }, { Text("What is this?") })
                    FilterChip(false, { vm.ask("Is there an error or problem on this screen? If so, explain the cause and the fix.") }, { Text("Explain the error") })
                    FilterChip(false, { vm.ask("What should I do next? Tell me step by step.") }, { Text("Next step?") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(question, { question = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Write your question…") }, shape = RoundedCornerShape(14.dp))
                    Button({ if (question.isNotBlank()) { vm.ask(question); question = "" } }, shape = RoundedCornerShape(14.dp)) { Text("Poochho") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet)
            if (answer != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(answer!!, style = MaterialTheme.typography.bodyMedium)
                        TextButton({ vm.stopSpeaking() }) { Text("Stop speaking") }
                    }
                }
            }
        }
    }
}
