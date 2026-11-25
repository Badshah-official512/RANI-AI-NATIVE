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
            answer.value = "Pehle neeche se screen share START karo, Babu Jaan — phir poochho, main dekh kar bataunga."
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
                val full = "User apni phone ki screen share kar raha hai aur ye usi screen ki tasveer hai. " +
                    "Screen ko ghaur se dekho — kya khula hai, koi error ya masla hai to wo bhi — aur phir is sawal ka jawab do, " +
                    "asaan alfaaz mein, step-by-step rehnumai ke saath agar zaroorat ho:\n" + question
                val r = c.ai.vision(full, b64)
                answer.value = r.text
                lastAskedAt = System.currentTimeMillis()
                c.tts.speak(r.text)
            } catch (e: AiException) {
                answer.value = e.userMessage
            } catch (e: Exception) {
                answer.value = "Screen parhne mein masla aya: ${e.message?.take(90)}"
            } finally { busy.value = false }
        }
    }

    /** Auto mode: while sharing, re-analyze the screen at most every ~10 seconds. */
    fun maybeAutoAsk() {
        if (auto.value && !busy.value && ScreenShareService.running.value &&
            System.currentTimeMillis() - lastAskedAt > 10000
        ) {
            ask("Is screen par kya ho raha hai? Koi error ya masla hai to samjhao, aur agla step batao.")
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
                    "🔒 Privacy: screen sirf tab dikhti hai jab TUM Start dabao aur Android ki permission do. " +
                        "Jab tak sharing chalti hai, upar notification rehta hai. Stop dabate hi sab band — koi cheez chhup kar record nahi hoti.",
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
                    Text(if (sharing) "Screen aa rahi hai…" else "Screen preview yahan ayega", color = Mute)
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
                Text("AI se screen ke baare mein poochho:", color = Mute, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { vm.ask("Is screen mein kya hai? Sab kuch short mein batao.") }, { Text("Kya hai?") })
                    FilterChip(false, { vm.ask("Is screen par koi error ya masla hai? Agar hai to wajah aur hal batao.") }, { Text("Error samjhao") })
                    FilterChip(false, { vm.ask("Mujhe agla step kya karna chahiye? Step-by-step batao.") }, { Text("Agla step?") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(question, { question = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Apna sawal likho…") }, shape = RoundedCornerShape(14.dp))
                    Button({ if (question.isNotBlank()) { vm.ask(question); question = "" } }, shape = RoundedCornerShape(14.dp)) { Text("Poochho") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet)
            if (answer != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(answer!!, style = MaterialTheme.typography.bodyMedium)
                        TextButton({ vm.stopSpeaking() }) { Text("Bolna band karo") }
                    }
                }
            }
        }
    }
}
