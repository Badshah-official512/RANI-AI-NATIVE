package com.badshah222.rani.ai.features.world

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Your World (Stonic-style): a globe and the daily briefing — "Three things
 * for you today", picked from the user's own memories (special days,
 * favourites) plus the web via Gemini. Items from memories are labelled
 * "From your memories"; AI items are labelled "From Gemini". Tap an item and
 * RANI chats about it. The briefing is cached for the day.
 */
data class BriefingItem(val title: String, val body: String, val source: String)

class YourWorldViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container

    val items = MutableStateFlow<List<BriefingItem>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val dateText: String = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date())

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    init {
        loadCachedOrGenerate()
    }

    private fun loadCachedOrGenerate() {
        val cached = runCatching {
            if (c.store.briefingDate != todayKey()) return@runCatching null
            val arr = JSONArray(c.store.briefingJson)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                BriefingItem(o.optString("title"), o.optString("body"), o.optString("source"))
            }
        }.getOrNull()
        if (!cached.isNullOrEmpty()) {
            items.value = cached
        } else {
            refresh()
        }
    }

    fun refresh() {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val out = mutableListOf<BriefingItem>()
                // 1. From memories: a special day today, or a favourite to remember.
                val now = java.util.Calendar.getInstance()
                val m = now.get(java.util.Calendar.MONTH) + 1
                val d = now.get(java.util.Calendar.DAY_OF_MONTH)
                runCatching {
                    val arr = JSONArray(c.store.specialDaysJson)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optInt("month") == m && o.optInt("day") == d) {
                            out += BriefingItem(
                                "Today: ${o.optString("name")}",
                                "Today is ${o.optString("name")} — RANI will wish you first.",
                                "From your memories"
                            )
                        }
                    }
                }
                runCatching {
                    val fav = JSONObject(c.store.favouritesJson)
                    val keys = fav.keys()
                    if (keys.hasNext()) {
                        val k = keys.next()
                        out += BriefingItem(
                            "You like: $k",
                            "RANI remembers — your $k is ${fav.optString(k)}.",
                            "From your memories"
                        )
                    }
                }
                // 2. From Gemini: three short, real, useful things for today.
                val need = (3 - out.size).coerceAtLeast(0)
                if (need > 0) {
                    val reply = c.ai.chat(
                        listOf(
                            ChatMessage(
                                "user",
                                "Give me $need short, real and useful things for today " +
                                    "($dateText) for someone in Pakistan who loves AI, websites and tech. " +
                                    "One line each: a title, then a colon, then one short sentence. " +
                                    "Only real, current things — no invented dates or prices."
                            )
                        )
                    )
                    reply.text.lines().filter { it.contains(":") }.take(need).forEach { line ->
                        val title = line.substringBefore(":").trim().trimStart('-', '•', '1', '2', '3', '.', ' ')
                        val body = line.substringAfter(":").trim()
                        if (title.isNotBlank() && body.isNotBlank()) {
                            out += BriefingItem(title, body, "From Gemini")
                        }
                    }
                }
                if (out.isEmpty()) {
                    out += BriefingItem(
                        "Good day",
                        "Add a special day or a favourite in Memory, and RANI will brief you here every morning.",
                        "From your memories"
                    )
                }
                val final = out.take(3)
                items.value = final
                c.store.briefingJson = JSONArray().apply {
                    final.forEach { put(JSONObject().put("title", it.title).put("body", it.body).put("source", it.source)) }
                }.toString()
                c.store.briefingDate = todayKey()
            } catch (e: AiException) {
                error.value = e.userMessage
                if (items.value.isEmpty()) {
                    items.value = listOf(
                        BriefingItem(
                            "Good day",
                            "Add your Gemini key in Settings to get your daily briefing from the web.",
                            "From your memories"
                        )
                    )
                }
            } catch (e: Exception) {
                error.value = "Briefing could not be created — try again."
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun YourWorldScreen(vm: YourWorldViewModel, onBack: () -> Unit, onAskTopic: (String) -> Unit) {
    val items by vm.items.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Your World", onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            // Globe
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(170.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.minDimension / 2f
                    drawCircle(Color(0xFF2563EB).copy(alpha = 0.22f), r, Offset(cx, cy))
                    drawCircle(CyanAcc.copy(alpha = 0.7f), r, Offset(cx, cy), style = Stroke(2f))
                    // Meridians + equator
                    for (k in -2..2) {
                        val rx = r * (0.35f + 0.325f * kotlin.math.abs(k))
                        drawOval(
                            CyanAcc.copy(alpha = 0.35f),
                            topLeft = Offset(cx - rx, cy - r),
                            size = androidx.compose.ui.geometry.Size(rx * 2, r * 2),
                            style = Stroke(1.5f)
                        )
                    }
                    drawLine(CyanAcc.copy(alpha = 0.35f), Offset(cx - r, cy), Offset(cx + r, cy), 1.5f)
                    // Dots (cities)
                    val dots = listOf(0.3f to 0.6f, 0.62f to 0.35f, 0.45f to 0.75f, 0.75f to 0.6f, 0.55f to 0.5f)
                    dots.forEach { (fx, fy) ->
                        drawCircle(Ok, 4f, Offset(cx + (fx - 0.5f) * 2 * r * 0.8f, cy + (fy - 0.5f) * 2 * r * 0.8f))
                    }
                    // Orbiting dot
                    val t = (System.currentTimeMillis() % 6000) / 6000f * 2 * Math.PI.toFloat()
                    drawCircle(
                        Color.White, 5f,
                        Offset(cx + cos(t) * r * 1.08f, cy + sin(t) * r * 1.08f)
                    )
                }
                Icon(Icons.Default.Public, null, tint = Color.Transparent, modifier = Modifier.size(1.dp))
            }
            Text(vm.dateText, style = MaterialTheme.typography.labelLarge, color = Mute, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                "Three things for you today",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                "Tap an item and RANI will talk about it.",
                style = MaterialTheme.typography.bodySmall, color = Mute,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (busy) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(" Building your briefing…", color = Mute, style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            items.forEachIndexed { i, item ->
                Card(
                    onClick = { onAskTopic(item.title) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.padding(14.dp)) {
                        Text("${i + 1}", color = VioletSoft, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 2.dp))
                            Text(item.source, style = MaterialTheme.typography.labelSmall, color = Mute, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            OutlinedButton({ vm.refresh() }, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp), enabled = !busy) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Text(" Refresh briefing")
            }
        }
    }
}
