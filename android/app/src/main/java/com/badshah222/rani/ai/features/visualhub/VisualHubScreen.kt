package com.badshah222.rani.ai.features.visualhub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Visual Hub (Stonic-style): "What do you want to see? Ask out loud. RANI
 * draws it here as it explains." The user asks for anything — a blueprint, a
 * black hole, a reactor — RANI generates the picture with the user's own
 * Gemini key and explains it out loud in words. The Gesture tab recognises
 * three hand-drawn gestures and jumps straight to the matching screen.
 *
 * Gesture map: circle -> Camera View · checkmark -> Web Search · line -> Your World.
 */
class VisualHubViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container

    val prompt = MutableStateFlow("")
    val image = MutableStateFlow<Bitmap?>(null)
    val explanation = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val tab = MutableStateFlow(0) // 0 = Visual Hub, 1 = Gesture

    fun drawAndExplain(q: String) {
        val query = q.trim()
        if (query.isEmpty() || busy.value) return
        prompt.value = query
        busy.value = true
        error.value = null
        explanation.value = null
        image.value = null
        viewModelScope.launch {
            try {
                val bytes = c.ai.generateImage(
                    "A clear, beautiful, educational illustration of: $query. " +
                        "Clean diagram style, labelled, easy to understand."
                )
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                image.value = bmp
                explanation.value = c.ai.describeImage(
                    bytes,
                    "Explain this picture simply in 5 short points, as if teaching Babu Jaan. " +
                        "Say what it is, how it works, and one interesting fact. Keep it warm and easy."
                )
            } catch (e: AiException) {
                error.value = e.userMessage
            } catch (e: Exception) {
                error.value = "Could not draw that — try again."
            } finally {
                busy.value = false
            }
        }
    }
}

private val SUGGESTIONS = listOf(
    "Starship blueprint",
    "How a black hole bends light",
    "How a fusion reactor works",
    "Crewed mission to Mars",
)

/** Classifies one drawn stroke: circle / checkmark / horizontal line / unknown. */
fun classifyGesture(points: List<Offset>): String {
    if (points.size < 8) return "unknown"
    var minX = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var minY = Float.MAX_VALUE
    var maxY = Float.MIN_VALUE
    var length = 0f
    for (i in points.indices) {
        val p = points[i]
        minX = min(minX, p.x); maxX = max(maxX, p.x)
        minY = min(minY, p.y); maxY = max(maxY, p.y)
        if (i > 0) length += (p - points[i - 1]).getDistance()
    }
    val w = maxX - minX
    val h = maxY - minY
    if (w <= 0f || h <= 0f) return "unknown"
    val startEnd = (points.last() - points.first()).getDistance()
    // Circle: closed loop, roughly square box, long path.
    if (startEnd < length * 0.25f && w / h in 0.55f..1.8f && length > 2.5f * max(w, h)) return "circle"
    // Horizontal line: wide, flat, start and end at similar height.
    if (w > h * 3.5f && abs(points.first().y - points.last().y) < h * 0.5f) return "line"
    // Checkmark: goes down-right to a low point, then up-right.
    val lowIdx = points.indices.maxByOrNull { points[it].y } ?: return "unknown"
    if (lowIdx in 2 until points.size - 2) {
        val firstHalfDown = points[lowIdx].y - points.first().y > h * 0.5f
        val secondHalfUp = points[lowIdx].y - points.last().y > h * 0.4f
        val movesRight = points.last().x - points.first().x > w * 0.5f
        if (firstHalfDown && secondHalfUp && movesRight) return "checkmark"
    }
    return "unknown"
}

@Composable
fun VisualHubScreen(vm: VisualHubViewModel, onBack: () -> Unit, onNav: (String) -> Unit) {
    val prompt by vm.prompt.collectAsState()
    val image by vm.image.collectAsState()
    val explanation by vm.explanation.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val tab by vm.tab.collectAsState()
    var draft by remember { mutableStateOf("") }
    var gesturePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var gestureResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Visual Hub", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { vm.tab.value = 0 }, { Text("Visual Hub") }, modifier = Modifier.weight(1f))
            FilterChip(tab == 1, { vm.tab.value = 1 }, { Text("Gesture") }, modifier = Modifier.weight(1f))
        }
        if (tab == 0) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "What do you want to see? Ask out loud. RANI draws it here as she explains.",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp)
                )
                OutlinedTextField(
                    draft, { draft = it }, Modifier.fillMaxWidth().padding(top = 10.dp),
                    placeholder = { Text("e.g. How a black hole bends light…") }, maxLines = 3,
                    shape = RoundedCornerShape(16.dp)
                )
                Button(
                    { vm.drawAndExplain(draft) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !busy && draft.isNotBlank()
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Text(" Draw & explain")
                }
                Text("Try one:", style = MaterialTheme.typography.labelLarge, color = Mute, modifier = Modifier.padding(top = 12.dp))
                SUGGESTIONS.forEachIndexed { i, s ->
                    Card(
                        onClick = { draft = s; vm.drawAndExplain(s) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Text("${i + 1}.", color = VioletSoft, fontWeight = FontWeight.Bold)
                            Text(" $s", Modifier.padding(start = 6.dp))
                        }
                    }
                }
                if (busy) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(" RANI is drawing…", color = Mute, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                image?.let { bmp ->
                    androidx.compose.foundation.Image(
                        bmp.asImageBitmap(), "Visual Hub drawing",
                        Modifier.fillMaxWidth().padding(top = 12.dp).background(Panel, RoundedCornerShape(16.dp))
                    )
                }
                explanation?.let {
                    Card(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Draw a gesture with your finger:",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text("Circle = Camera View · Checkmark = Web Search · Line = Your World",
                    color = Mute, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(300.dp).padding(top = 12.dp)
                        .background(Panel, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { gesturePoints = listOf(it); gestureResult = null },
                                onDragEnd = {
                                    val g = classifyGesture(gesturePoints)
                                    gestureResult = g
                                    when (g) {
                                        "circle" -> onNav("camera")
                                        "checkmark" -> onNav("search")
                                        "line" -> onNav("world")
                                    }
                                }
                            ) { change, _ -> gesturePoints = gesturePoints + change.position }
                        }
                ) {
                    if (gesturePoints.size > 1) {
                        for (i in 1 until gesturePoints.size) {
                            drawLine(Color(0xFF7C4DFF), gesturePoints[i - 1], gesturePoints[i], strokeWidth = 8f)
                        }
                    }
                    drawCircle(Color(0xFF7C4DFF).copy(alpha = 0.25f), style = Stroke(2f))
                }
                gestureResult?.let {
                    Text(
                        when (it) {
                            "circle" -> "Circle — opening Camera View…"
                            "checkmark" -> "Checkmark — opening Web Search…"
                            "line" -> "Line — opening Your World…"
                            else -> "Gesture not recognised — try a clear circle, checkmark or line."
                        },
                        color = if (it == "unknown") Bad else Ok,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ onNav("camera") }, Modifier.weight(1f)) { Text("Camera View") }
                    OutlinedButton({ onNav("search") }, Modifier.weight(1f)) { Text("Web Search") }
                    OutlinedButton({ onNav("world") }, Modifier.weight(1f)) { Text("Your World") }
                }
                Icon(Icons.Default.Gesture, null, tint = VioletSoft, modifier = Modifier.padding(top = 16.dp).size(40.dp))
            }
        }
    }
}
