package com.badshah222.rani.ai.features.tools

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.badshah222.rani.ai.core.AppResolver
import com.badshah222.rani.ai.core.RaniAccessibilityService
import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.data.ai.Pollinations
import com.badshah222.rani.ai.data.phone.ContactsHelper
import com.badshah222.rani.ai.domain.AiException
import com.badshah222.rani.ai.domain.ChatMessage
import com.badshah222.rani.ai.domain.RaniTools
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow // FIX (CI run #48): missing import — MutableStateFlow used at lines 74-77; its absence caused all 30 errors in AiToolsScreen.kt
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * v2.0.0 §22.4 — the Skills screen: 65 real skills across 12 categories.
 *
 * Same dark panels, violet stroke, Four Core Circuits language as the rest
 * of the app. Categories are SECTIONS with headers (name + count) in one
 * scrolling layout — not a tab per category. Each skill is a card: icon
 * name, English name, one-line English description, and a "Coming soon"
 * badge only on kind (c); native skills get no badge — they just work.
 * Tapping a skill opens the existing ask-flow: the English ask-prompt as
 * the field label, "Type here…" placeholder, "Run" button, result below.
 * Search box on top filters cards by name + category + description.
 * The total ("65 skills") is computed from the catalog at runtime, never
 * hardcoded. The original 8 tools keep their exact templates and
 * handlers — only their UI strings changed to English; Analyze File
 * stays as its own file-picker section below the catalog (it is not a
 * skill card, so the runtime total stays exactly 65).
 *
 * Generate Image uses the Gemini image model (Pollinations free URL as fallback);
 * Create PDF writes a real PDF to Downloads; Analyze File reads one of RANI's
 * own generated files; chat skills run through RANI's Gemini chat; native
 * skills run the real on-device action (same intents / resolver /
 * accessibility calls as the Live phone tools in RaniLiveBridge).
 */
class AiToolsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val result = MutableStateFlow<String?>(null)
    val imageBytes = MutableStateFlow<ByteArray?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun runSkill(skill: RaniTools.Skill, input: String) {
        val x = input.trim()
        if (skill.kind == RaniTools.SkillKind.COMING_SOON) {
            // Honest badge — a "Coming soon" skill never fakes a result.
            result.value = skill.ask
            return
        }
        if (skill.needsInput && x.isEmpty()) { error.value = "Write something first."; return }
        busy.value = true; error.value = null; result.value = null; imageBytes.value = null
        viewModelScope.launch {
            try {
                when (val a = skill.action) {
                    "generate_image" -> {
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
                    "generate_video" -> {
                        // Video takes a few minutes (the pooled video system renders it).
                        result.value = "Your video is being made — this can take a few minutes. Keep the screen on."
                        val bytes = c.ai.generateVideo(x)
                        val path = writeVideo(bytes)
                        result.value = "Video is ready.\nSaved: $path"
                    }
                    "create_pdf" -> {
                        val reply = c.ai.chat(listOf(ChatMessage("user", skill.template.replace("{x}", x))))
                        val path = writePdf(skill.name, reply.text)
                        result.value = "PDF is ready.\n${reply.text.take(600)}\n\nSaved: $path"
                    }
                    "save_todo_list" -> {
                        val reply = c.ai.chat(listOf(ChatMessage("user", skill.template.replace("{x}", x))))
                        val lines = reply.text.lines().map {
                            it.trim().removePrefix("- ").removePrefix("• ").replace(Regex("^\\d+[.)]\\s*"), "")
                        }.filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) saveTasks(lines)
                        result.value = reply.text + if (lines.isNotEmpty()) "\n\nSaved to your to-do list." else ""
                    }
                    "open_app" -> result.value = nativeOpenApp(x)
                    "call_contact" -> result.value = nativeCallContact(x)
                    "set_alarm" -> result.value = nativeSetAlarm(x)
                    "set_timer" -> result.value = nativeSetTimer(x)
                    "open_settings" -> result.value = nativeOpenSettings(x)
                    "take_photo" -> result.value = nativeTakePhoto()
                    "send_whatsapp" -> result.value = nativeSendWhatsApp(x)
                    "send_sms" -> result.value = nativeSendSms(x)
                    "read_screen" -> result.value = nativeReadScreen()
                    "type_text_send" -> result.value = nativeTypeAndSend(x)
                    "search_youtube" -> result.value = nativeSearchYouTube(x)
                    "search_web" -> result.value = nativeSearchWeb(x)
                    "set_reminder" -> result.value = nativeSetReminder(x)
                    "water_reminder" -> result.value = nativeWaterReminder(x)
                    "play_music" -> result.value = nativePlayMusic(x)
                    null -> {
                        // Plain chat skill: the template carries {x}, or is
                        // a fixed prompt the input is appended to.
                        val prompt = if (skill.template.contains("{x}")) skill.template.replace("{x}", x)
                        else skill.template + if (x.isNotEmpty()) "\n$x" else ""
                        val reply = c.ai.chat(listOf(ChatMessage("user", prompt)))
                        result.value = reply.text
                    }
                    else -> {
                        if (a.startsWith("routine:")) result.value = runRoutine(a.removePrefix("routine:"))
                        else error.value = "Something went wrong: unknown skill action."
                    }
                }
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "command", mapOf("tool" to "ai_tools")) }
            } catch (e: AiException) { error.value = e.userMessage }
            catch (e: Exception) { error.value = "Something went wrong: ${e.message?.take(120)}" }
            finally { busy.value = false }
        }
    }

    /** v2.0.0 §22.4: routine skills run the REAL routine mechanism — the
     *  same one AutomationScreen uses: volume/brightness + a spoken
     *  confirmation. */
    private suspend fun runRoutine(routineName: String): String {
        val ctx = appCtx()
        val r = RaniTools.routines.firstOrNull { it.name == routineName }
            ?: return "I could not find that routine."
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        r.volume?.let { v ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max / 100).coerceIn(0, max), 0)
        }
        r.brightness?.let { b ->
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (b * 255 / 100).coerceIn(10, 255))
            } else {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "To change brightness, first give RANI the 'Modify system settings' permission."
            }
        }
        runCatching { c.tts.speak("${r.name} on, Babu Jaan.") }
        return "${r.name} on — ${r.prompt}"
    }

    // ---- Native skill actions (same intents / resolver / accessibility
    // calls as the Live phone tools in RaniLiveBridge — the skill does
    // the real thing, it never only chats about doing it). ----

    private fun appCtx(): Context = getApplication<Application>()

    private fun launchIntent(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appCtx().startActivity(intent)
        true
    } catch (e: Exception) { false }

    private fun nativeOpenApp(query: String): String {
        return when (val r = AppResolver.launch(appCtx(), query)) {
            "ok" -> "Opening $query."
            "notfound" -> "I could not find an app called $query on your phone."
            "fail" -> "I could not open $query."
            else -> if (r.startsWith("multiple:")) {
                "Which one did you mean — ${r.removePrefix("multiple:").replace(Regex(", ([^,]+)$"), " or $1")}?"
            } else "I could not open $query."
        }
    }

    /** Resolves a contact name (or digits) to a number — mirrors
     *  RaniLiveBridge.resolveNumber. Returns "multiple:Name1, Name2" on
     *  a tie so RANI asks which one. Contacts stay on-device. */
    private fun resolveNumber(target: String): String {
        val t = target.trim()
        if (t.isEmpty()) return "fail"
        if (t.matches(Regex("^[+]?[0-9][0-9 \\-]{5,}$"))) return t.replace(" ", "").replace("-", "")
        if (!ContactsHelper.hasPermission(appCtx())) return "nocontacts"
        val all = runCatching { ContactsHelper.loadAll(appCtx()) }.getOrDefault(emptyList())
        val exact = all.filter { it.name.equals(t, ignoreCase = true) }
        if (exact.size > 1) {
            val names = exact.map { it.name }.distinct().take(3)
            if (names.size > 1) return "multiple:" + names.joinToString(", ")
        }
        val entry = runCatching { ContactsHelper.findBest(appCtx(), t) }.getOrNull() ?: return "notfound"
        return runCatching { ContactsHelper.bestNumber(entry) }.getOrNull() ?: "notfound"
    }

    private fun multipleOrStatus(num: String, contact: String): String? = when {
        num == "notfound" -> "I could not find a contact called $contact."
        num == "nocontacts" -> "I need permission to read contacts to find that person by name."
        num.startsWith("multiple:") -> "Which one did you mean — ${num.removePrefix("multiple:").replace(Regex(", ([^,]+)$"), " or $1")}?"
        num == "fail" -> "I could not understand that contact."
        else -> null
    }

    private fun nativeCallContact(name: String): String {
        val num = resolveNumber(name)
        multipleOrStatus(num, name)?.let { return it }
        return try {
            if (launchIntent(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))) "Calling $name now." else "I could not call $name."
        } catch (e: SecurityException) { "I need the call permission to call $name." }
        catch (e: Exception) { "I could not call $name." }
    }

    /** Parses "6", "6:30", "6 am", "6:30 pm" -> (hour24, minute) or null. */
    private fun parseAlarmTime(raw: String): Pair<Int, Int>? {
        val m = Regex("(?i)^\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*$").find(raw.trim()) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun nativeSetAlarm(input: String): String {
        val hm = parseAlarmTime(input) ?: return "I could not understand that time. Try something like 6 am or 6:30."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hm.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, hm.second)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "RANI AI")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return if (launchIntent(i)) "Alarm editor opened with ${input.trim()} prefilled — confirm it in the alarm app." else "I could not open the alarm app."
    }

    private fun parseDurationSeconds(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        Regex("(\\d+)\\s*(?:h|hour|hours)").find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 3600 }
        Regex("(\\d+)\\s*(?:m|min|mins|minute|minutes)").find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 60 }
        Regex("(\\d+)\\s*(?:s|sec|secs|second|seconds)").find(s)?.let { return it.groupValues[1].toIntOrNull() }
        return s.toIntOrNull()
    }

    private fun nativeSetTimer(input: String): String {
        val secs = parseDurationSeconds(input) ?: return "I could not understand that length. Try something like 10 minutes."
        if (secs <= 0) return "I could not understand that length. Try something like 10 minutes."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "RANI AI")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return if (launchIntent(i)) "Timer editor opened prefilled — confirm it in the timer app." else "I could not open the timer app."
    }

    private fun nativeOpenSettings(input: String): String {
        val s = input.trim().lowercase()
        val (action, label) = when {
            s.contains("wifi") || s.contains("wi-fi") || s.contains("wireless") -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            s.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            s.contains("display") || s.contains("brightness") || s.contains("screen") -> Settings.ACTION_DISPLAY_SETTINGS to "Display"
            s.contains("sound") || s.contains("volume") || s.contains("audio") -> Settings.ACTION_SOUND_SETTINGS to "Sound"
            s.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery"
            s.contains("location") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location"
            s.contains("notification") -> Settings.ACTION_APP_NOTIFICATION_SETTINGS to "Notifications"
            s.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility"
            s.contains("app") -> Settings.ACTION_APPLICATION_SETTINGS to "Apps"
            else -> Settings.ACTION_SETTINGS to "Settings"
        }
        val i = Intent(action)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, appCtx().packageName)
        return if (launchIntent(i)) "Opening $label settings." else "I could not open the settings."
    }

    /** Open the camera, then tap the shutter via accessibility when the
     *  service is ON (polls up to ~3 s — the shutter node does not exist
     *  the instant the camera intent returns). Service OFF -> the camera
     *  still opens and RANI says "tap the shutter"; never a silent
     *  nothing. */
    private suspend fun nativeTakePhoto(): String {
        if (!launchIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) return "I could not open the camera."
        val svc = RaniAccessibilityService.instance ?: return "Camera is open — tap the shutter to take the photo."
        val labels = listOf("Shutter", "Take photo", "Capture")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            for (label in labels) {
                if (svc.tapText(label) == "ok") return "Photo taken."
            }
            delay(300)
        }
        return "Camera is open — tap the shutter to take the photo."
    }

    /** "Ali — see you at 5" -> (contact, message). */
    private fun splitContactMessage(input: String): Pair<String, String>? {
        for (sep in listOf(" — ", " - ", ": ")) {
            val idx = input.indexOf(sep)
            if (idx > 0) return input.substring(0, idx).trim() to input.substring(idx + sep.length).trim()
        }
        return null
    }

    private fun nativeSendWhatsApp(input: String): String {
        val (contact, message) = splitContactMessage(input)
            ?: return "Write it like this: Ali — see you at 5."
        val num = resolveNumber(contact)
        multipleOrStatus(num, contact)?.let { return it }
        val e164 = runCatching { ContactsHelper.toE164(appCtx(), num) }.getOrNull() ?: num
        var url = "https://wa.me/" + e164.removePrefix("+")
        if (message.isNotBlank()) url += "?text=" + URLEncoder.encode(message, "UTF-8")
        return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "WhatsApp opened with your message drafted — press Send to send it." else "I could not open WhatsApp."
    }

    /** SMS draft only — the user presses Send himself. Never auto-sent. */
    private fun nativeSendSms(input: String): String {
        val (contact, message) = splitContactMessage(input)
            ?: return "Write it like this: Ali — see you at 5."
        val num = resolveNumber(contact)
        multipleOrStatus(num, contact)?.let { return it }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
        if (message.isNotBlank()) i.putExtra("sms_body", message)
        return if (launchIntent(i)) "SMS draft opened — press Send to send it." else "I could not open the messaging app."
    }

    private fun nativeReadScreen(): String {
        val svc = RaniAccessibilityService.instance
            ?: return "I need the accessibility permission to read the screen. Turn on RANI AI in Settings, Accessibility."
        val text = svc.readScreen()
        return if (text.isBlank()) "I could not read anything on the screen right now." else text
    }

    private fun nativeTypeAndSend(input: String): String {
        val svc = RaniAccessibilityService.instance
            ?: return "I need the accessibility permission for that. Turn on RANI AI in Settings, Accessibility."
        if (svc.typeText(input) != "ok") return "There is no text field on the screen right now to type into."
        return if (svc.tapText("Send") == "ok") "Typed and sent." else "I typed it, but I could not find the Send button — press it yourself."
    }

    private fun nativeSearchYouTube(query: String): String {
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage("com.google.android.youtube") }
        if (launchIntent(i)) return "Searching YouTube for $query."
        return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Searching YouTube for $query." else "I could not open YouTube."
    }

    private fun nativeSearchWeb(query: String): String {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage("com.android.chrome") }
        if (launchIntent(i)) return "Searching the web for $query."
        return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Searching the web for $query." else "I could not open the browser."
    }

    /** Reminder: saved on-device in the existing tasks store (local only)
     *  — the same shape RaniLiveBridge.setReminder writes. */
    private fun saveReminder(text: String, whenText: String): Boolean {
        return try {
            val store = SecureStore(appCtx())
            val arr = runCatching { JSONArray(store.tasksJson) }.getOrNull() ?: JSONArray()
            arr.put(
                JSONObject()
                    .put("id", "rem_${System.currentTimeMillis()}")
                    .put("title", text)
                    .put("due", whenText)
                    .put("status", "pending"),
            )
            store.tasksJson = arr.toString()
            true
        } catch (e: Exception) { false }
    }

    private fun saveTasks(titles: List<String>) {
        try {
            val store = SecureStore(appCtx())
            val arr = runCatching { JSONArray(store.tasksJson) }.getOrNull() ?: JSONArray()
            titles.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", "task_${System.currentTimeMillis()}_${t.hashCode()}")
                        .put("title", t)
                        .put("due", "")
                        .put("status", "pending"),
                )
            }
            store.tasksJson = arr.toString()
        } catch (e: Exception) { }
    }

    private fun nativeSetReminder(input: String): String {
        // "Call Ammi at 5" -> text "Call Ammi", when "5".
        val idx = input.lastIndexOf(" at ")
        val (text, whenText) = if (idx > 0) input.substring(0, idx).trim() to input.substring(idx + 4).trim() else input.trim() to ""
        if (text.isEmpty()) return "What should I remind you about?"
        return if (saveReminder(text, whenText)) "Reminder saved: $text" + if (whenText.isNotEmpty()) " ($whenText)." else "." else "I could not save that reminder."
    }

    private fun nativeWaterReminder(input: String): String {
        return if (saveReminder("Drink water", input.trim())) "I will remind you to drink water" + if (input.isNotBlank()) " ${input.trim()}." else "." else "I could not save that reminder."
    }

    private fun nativePlayMusic(query: String): String {
        val q = query.trim()
        if (q.lowercase().contains("spotify")) {
            val song = q.replace("spotify", "", ignoreCase = true).trim()
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + URLEncoder.encode(song, "UTF-8"))).setPackage("com.spotify.music")
            if (launchIntent(i)) return "Playing $q on Spotify."
            if (AppResolver.launch(appCtx(), "Spotify") == "ok") return "Opening Spotify."
            return "I could not find Spotify on your phone."
        }
        val url = if (q.isEmpty()) "https://music.youtube.com" else "https://music.youtube.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Playing $q on YouTube Music." else "I could not open the music app."
    }

    fun runAnalyzeFile(file: File) {
        busy.value = true; error.value = null; result.value = null
        viewModelScope.launch {
            try {
                val text = runCatching { file.readText().take(6000) }.getOrElse { "[binary file: ${file.name}]" }
                // v2.0.0 fix: this used aiTools[5] — the Create PDF template.
                // Analyze File has its own template (looked up by name).
                val template = RaniTools.aiTools.first { it.name == "Analyze File" }.template
                val reply = c.ai.chat(listOf(ChatMessage("user", template.replace("{x}", "${file.name}:\n$text"))))
                result.value = reply.text
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "command", mapOf("tool" to "ai_tools_file")) }
            } catch (e: AiException) { error.value = e.userMessage }
            catch (e: Exception) { error.value = "Could not read the file: ${e.message?.take(120)}" }
            finally { busy.value = false }
        }
    }

    private fun writeVideo(bytes: ByteArray): String {
        val ctx = getApplication<Application>()
        val name = "RANI_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Downloads could not be opened")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            "Downloads/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(dir, name)
            f.writeBytes(bytes)
            f.absolutePath
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
    var selected by remember { mutableStateOf<RaniTools.Skill?>(null) }
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val result by vm.result.collectAsState()
    val imageBytes by vm.imageBytes.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val files = remember { vm.generatedFiles() }

    fun openUrl(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // Search filters by name + category + description, locally, as the
    // user types. The total is computed from the catalog at runtime.
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) RaniTools.skills else RaniTools.skills.filter {
        it.name.lowercase().contains(q) || it.category.lowercase().contains(q) || it.description.lowercase().contains(q)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("AI Tools", onBack)
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Search skills…") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        Text(
            "${RaniTools.skills.size} skills",
            Modifier.padding(start = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = VioletSoft,
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            // The open skill's ask-flow sits at the top, so it is visible
            // the moment a card is tapped.
            selected?.let { skill ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(skill.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = VioletSoft)
                                if (skill.kind == RaniTools.SkillKind.COMING_SOON) {
                                    Text(
                                        "Coming soon",
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Gold,
                                    )
                                }
                            }
                            Text(skill.description, style = MaterialTheme.typography.bodySmall, color = Mute)
                            if (skill.kind == RaniTools.SkillKind.COMING_SOON) {
                                // Honest badge — no input, no Run, no fake result.
                                Text(skill.ask, style = MaterialTheme.typography.bodyMedium)
                            } else if (!skill.needsInput) {
                                Text(skill.ask, style = MaterialTheme.typography.bodySmall, color = Mute)
                                Button({ vm.runSkill(skill, "") }, enabled = !busy) {
                                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Run")
                                }
                            } else {
                                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text(skill.ask) }, placeholder = { Text("Type here…") }, minLines = 3, maxLines = 6)
                                Button({ vm.runSkill(skill, input) }, enabled = !busy) {
                                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Run")
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
            if (filtered.isEmpty()) {
                item { Text("No skills match that search.", style = MaterialTheme.typography.bodyMedium, color = Mute) }
            } else {
                for (cat in RaniTools.skillCategories) {
                    val list = filtered.filter { it.category == cat }
                    if (list.isEmpty()) continue
                    item {
                        Text("$cat · ${list.size}", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp))
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { skill ->
                                        Card(
                                            { selected = skill; input = ""; vm.result.value = null; vm.imageBytes.value = null },
                                            Modifier.weight(1f).height(104.dp),
                                            colors = CardDefaults.cardColors(containerColor = Panel),
                                            shape = RoundedCornerShape(18.dp),
                                        ) {
                                            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                                Text(skill.description, style = MaterialTheme.typography.bodySmall, color = Mute, maxLines = 2)
                                                if (skill.kind == RaniTools.SkillKind.COMING_SOON) {
                                                    Text("Coming soon", style = MaterialTheme.typography.labelSmall, color = Gold)
                                                }
                                            }
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            // Analyze File — the 8th original tool. It is NOT a skill card
            // (the runtime total stays exactly the 65 skills above); it
            // keeps its own file-picker flow here.
            item {
                Text("Analyze a File", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose from files you created:", style = MaterialTheme.typography.bodySmall, color = Mute)
                        if (files.isEmpty()) Text("No files yet — create something with WebForge or Image Studio first.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        files.forEach { f ->
                            OutlinedButton({ vm.runAnalyzeFile(f) }, Modifier.fillMaxWidth()) { Text(f.name, maxLines = 1) }
                        }
                    }
                }
            }
            // Search engines + quick sites — already real, only hidden.
            // They launch the real search / site, and are NOT counted in
            // the skills total.
            item {
                Text("Search & Quick Sites", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Search engines", style = MaterialTheme.typography.bodySmall, color = Mute)
                        RaniTools.engines.forEach { e ->
                            OutlinedButton(
                                {
                                    val url = if (q.isEmpty()) e.urlTemplate.substringBefore("?") else e.urlTemplate.replace("{q}", URLEncoder.encode(query.trim(), "UTF-8"))
                                    openUrl(url)
                                },
                                Modifier.fillMaxWidth(),
                            ) { Text(e.label, maxLines = 1) }
                        }
                        Text("Quick sites", style = MaterialTheme.typography.bodySmall, color = Mute)
                        RaniTools.quickSites.forEach { s ->
                            OutlinedButton({ openUrl(s.url) }, Modifier.fillMaxWidth()) { Text(s.name, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}
