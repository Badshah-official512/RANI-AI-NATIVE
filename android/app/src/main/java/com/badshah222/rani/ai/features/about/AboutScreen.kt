package com.badshah222.rani.ai.features.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.core.Config
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.stonic.RaniAvatar
import com.badshah222.rani.ai.ui.theme.*

private data class Social(val name: String, val handle: String, val url: String)
private val SOCIALS = listOf(
    Social("TikTok", "@zain_ff45", Config.TIKTOK), Social("YouTube", "@Badshahofficial-222", Config.YOUTUBE),
    Social("Facebook", "RANI official", Config.FACEBOOK), Social("Instagram", "@badshahofficial222", Config.INSTAGRAM),
    Social("WhatsApp", "+92 325 1787969", Config.WHATSAPP),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("About RANI AI", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                // The character, full figure (never circular, never the poster).
                Image(painterResource(R.drawable.rani_character), "RANI AI — your AI girlfriend", Modifier.fillMaxWidth().height(320.dp), contentScale = ContentScale.Fit)
            }
            item {
                Text("RANI AI GF", style = MaterialTheme.typography.headlineMedium, color = Gold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("YOUR VIRTUAL AI GIRLFRIEND", style = MaterialTheme.typography.labelLarge, color = Mute, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("RANI AI is a voice-first AI girlfriend created by BADSHAH222. Talk to her, ask anything, and let her open your apps, prepare calls and messages, search YouTube, build websites and code — all with your own Gemini key and the female voice you choose. Your keys stay on your phone, and your contacts never leave it.", Modifier.padding(top = 12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaniAvatar(54.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Created by BADSHAH222", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Zain Shah · Swabi, Pakistan", color = Mute, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("Follow RANI", style = MaterialTheme.typography.titleMedium, color = Gold) }
            items(SOCIALS) { s ->
                Card(Modifier.fillMaxWidth().clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(s.name, style = MaterialTheme.typography.titleMedium); Text(s.handle, color = Mute, style = MaterialTheme.typography.bodySmall) }
                        Text("Open", color = Gold)
                    }
                }
            }
            item { Text("RANI AI GF — BADSHAH222", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.bodySmall) }
            item { Text("Version 1.0.0", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Mute, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
