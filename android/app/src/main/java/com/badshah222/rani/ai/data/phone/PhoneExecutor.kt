package com.badshah222.rani.ai.data.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.domain.PhoneAction
import java.net.URLEncoder

sealed class PhoneResult {
    data class Done(val message: String) : PhoneResult()
    data class NeedPermission(val permission: String, val why: String) : PhoneResult()
    data class Failed(val message: String) : PhoneResult()
    object LaunchContactPicker : PhoneResult()
}

/**
 * Executes assistant actions through OFFICIAL Android intents only.
 * Draft messages (Sms / WhatsAppMessage): the dialer / SMS app / WhatsApp opens
 * pre-filled and the user presses the final button. Auto-send messages
 * (AutoSendMessage) are NOT executed here — HomeViewModel runs them through
 * MessageSender (accessibility type + tap Send) only after the user confirmed.
 * Contacts are resolved on-device only (ContactsHelper) — never uploaded.
 */
class PhoneExecutor(private val ctx: Context) {
    private fun launch(i: Intent): Boolean = try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
    private fun launchPackage(pkg: String): Boolean = ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(it) } ?: false
    private fun url(u: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))

    fun execute(a: PhoneAction): PhoneResult = when (a) {
        PhoneAction.OpenYouTube -> if (launchPackage("com.google.android.youtube") || url("https://www.youtube.com")) PhoneResult.Done("Opening YouTube.") else PhoneResult.Failed("Could not open YouTube.")
        PhoneAction.OpenWhatsApp -> if (launchPackage("com.whatsapp") || launchPackage("com.whatsapp.w4b")) PhoneResult.Done("Opening WhatsApp.") else PhoneResult.Failed("WhatsApp is not installed on this phone.")
        PhoneAction.OpenChrome -> if (launchPackage("com.android.chrome") || url("https://www.google.com")) PhoneResult.Done("Opening Chrome.") else PhoneResult.Failed("Could not open a browser.")
        is PhoneAction.OpenBrowser -> PhoneResult.Failed("Browser app ke andar se khulta hai.") // intercepted by HomeViewModel before execute()
        PhoneAction.OpenScreen -> PhoneResult.Failed("Screen share app ke andar se khulta hai.") // intercepted by HomeViewModel before execute()
        PhoneAction.OpenContacts -> PhoneResult.Failed("Contacts app ke andar se khulte hain.") // intercepted by HomeViewModel before execute()
        is PhoneAction.AutoSendMessage -> PhoneResult.Failed("Auto-send HomeViewModel se chalta hai.") // intercepted by HomeViewModel before execute()
        is PhoneAction.YouTubeSearch -> youTubeSearch(a.query)
        is PhoneAction.DeviceInfo -> deviceInfo(a.kind)
        is PhoneAction.Volume -> volume(a.dir)
        PhoneAction.OpenCamera -> if (launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) PhoneResult.Done("Opening the camera.") else PhoneResult.Failed("No camera app found.")
        PhoneAction.OpenGallery -> if (launch(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) || launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))) PhoneResult.Done("Opening your gallery.") else PhoneResult.Failed("No gallery app found.")
        PhoneAction.OpenVideos -> if (launch(Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) || launch(Intent(Intent.ACTION_VIEW).setType("video/*"))) PhoneResult.Done("Opening your videos.") else PhoneResult.Failed("No video gallery found.")
        PhoneAction.OpenFiles -> if (launch(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) || launch(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE))) PhoneResult.Done("Opening your files.") else PhoneResult.Failed("No file manager found.")
        PhoneAction.PickContact -> PhoneResult.LaunchContactPicker
        is PhoneAction.OpenApp -> openByName(a.appName)
        is PhoneAction.WebSearch -> if (launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", a.query)) || url("https://www.google.com/search?q=" + URLEncoder.encode(a.query, "UTF-8"))) PhoneResult.Done("Searching for ${a.query}.") else PhoneResult.Failed("Could not start a search.")
        is PhoneAction.SetAlarm -> if (launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.hour24).putExtra(AlarmClock.EXTRA_MINUTES, a.minute).putExtra(AlarmClock.EXTRA_MESSAGE, a.label ?: "RANI AI").putExtra(AlarmClock.EXTRA_SKIP_UI, false)))
            PhoneResult.Done("Alarm set for %02d:%02d. Confirm it in the clock app.".format(a.hour24, a.minute)) else PhoneResult.Failed("No clock app found.")
        is PhoneAction.StartTimer -> if (launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.seconds).putExtra(AlarmClock.EXTRA_MESSAGE, a.label ?: "RANI AI").putExtra(AlarmClock.EXTRA_SKIP_UI, false)))
            PhoneResult.Done("Timer started for ${a.seconds / 60} min ${a.seconds % 60} sec.") else PhoneResult.Failed("No clock app found.")
        is PhoneAction.Call -> withNumber(a.target) { num -> if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))) PhoneResult.Done("Dialer opened for $num. Press call to connect.") else PhoneResult.Failed("No dialer found.") }
        is PhoneAction.Flashlight -> torch(a.on)
        is PhoneAction.Sms -> withNumber(a.target) { num -> if (launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).putExtra("sms_body", a.message))) PhoneResult.Done("Message ready for $num. Press send in your messages app.") else PhoneResult.Failed("No SMS app found.") }
        is PhoneAction.WhatsAppMessage -> withNumber(a.target) { num ->
            val e164 = toE164(num) ?: return@withNumber PhoneResult.Failed("I need the number with country code (for example +92...) to open WhatsApp.")
            if (url("https://wa.me/${e164.removePrefix("+")}?text=" + URLEncoder.encode(a.message, "UTF-8"))) PhoneResult.Done("WhatsApp chat opened with your message ready. Press send.") else PhoneResult.Failed("Could not open WhatsApp.")
        }
    }

    /** Torch mode needs no permission and no camera app: CameraManager does it directly. */
    private fun torch(on: Boolean): PhoneResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            runCatching { cm.getCameraCharacteristics(cid).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
        } ?: return PhoneResult.Failed("This phone has no flashlight.")
        return try {
            cm.setTorchMode(id, on)
            PhoneResult.Done(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            PhoneResult.Failed("Could not switch the flashlight.")
        }
    }

    /** YouTube search (spec §14.2): results inside the YouTube app, browser fallback. */
    private fun youTubeSearch(query: String): PhoneResult {
        val q = URLEncoder.encode(query, "UTF-8")
        val yt = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))
            .setPackage("com.google.android.youtube")
        if (launch(yt)) return PhoneResult.Done("YouTube par \"$query\" search kar rahi hoon, Babu Jaan.")
        if (url("https://www.youtube.com/results?search_query=$q")) return PhoneResult.Done("YouTube par \"$query\" search kar rahi hoon, Babu Jaan.")
        return PhoneResult.Failed("YouTube search nahi khul saki.")
    }

    private fun withNumber(target: String, f: (String) -> PhoneResult): PhoneResult {
        if (target.matches(Regex("^[+]?[0-9]{5,}$"))) return f(target)
        if (!ContactsHelper.hasPermission(ctx)) return PhoneResult.NeedPermission(Manifest.permission.READ_CONTACTS, "To find \"$target\" in your contacts I need permission to read contacts. Or say the phone number instead.")
        val entry = ContactsHelper.findBest(ctx, target)
            ?: return PhoneResult.Failed("I could not find \"$target\" in your contacts.")
        val num = ContactsHelper.bestNumber(entry)
            ?: return PhoneResult.Failed("I found \"$target\" but there is no number saved for them.")
        return f(num)
    }

    private fun toE164(raw: String): String? = ContactsHelper.toE164(ctx, raw)

    private fun openByName(name: String): PhoneResult {
        // v1.9.0 §21.1 — the chat path resolves through the SAME shared
        // AppResolver as the Live bridge (exact / normalised / fuzzy /
        // well-known table / "multiple:…" clarification).
        return when (val r = com.badshah222.rani.ai.core.AppResolver.resolve(ctx, name)) {
            is com.badshah222.rani.ai.core.AppResolver.Result.Hit ->
                if (launchPackage(r.packageName)) PhoneResult.Done("Opening ${r.label}.")
                else PhoneResult.Failed("I could not open \"${r.label}\".")
            is com.badshah222.rani.ai.core.AppResolver.Result.Multiple ->
                PhoneResult.Failed("Do you mean ${r.names.joinToString(" or ")}?")
            com.badshah222.rani.ai.core.AppResolver.Result.NotFound ->
                PhoneResult.Failed("I could not find an app called \"$name\".")
        }
    }

    /** MYRA-style system monitor answers, computed on-device — no internet needed. */
    private fun deviceInfo(kind: String): PhoneResult = try {
        when (kind) {
            "battery" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                PhoneResult.Done("Battery $pct percent hai, Babu Jaan${if (charging) " — aur charging par lagi hai" else ""}.")
            }
            "storage" -> {
                val dir = android.os.Environment.getDataDirectory()
                val fs = android.os.StatFs(dir.path)
                val total = fs.totalBytes / (1024.0 * 1024 * 1024)
                val free = fs.availableBytes / (1024.0 * 1024 * 1024)
                PhoneResult.Done("Storage: kul ${"%.0f".format(total)} GB mein se ${"%.1f".format(free)} GB khali hai, Babu Jaan.")
            }
            "time" -> PhoneResult.Done("Abhi waqt hai " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date()) + ", Babu Jaan.")
            "date" -> PhoneResult.Done("Aaj ki tareekh hai " + java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale.ENGLISH).format(java.util.Date()) + ", Babu Jaan.")
            else -> PhoneResult.Failed("Ye maloomat nahi mili.")
        }
    } catch (e: Exception) { PhoneResult.Failed("Device info nahi mil saki: ${e.message?.take(60)}") }

    private fun volume(dir: String): PhoneResult = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_MUSIC
        when (dir) {
            "up" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI); PhoneResult.Done("Volume barha diya, Babu Jaan.") }
            "down" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI); PhoneResult.Done("Volume kam kar diya, Babu Jaan.") }
            "mute" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI); PhoneResult.Done("Mute kar diya, Babu Jaan.") }
            else -> PhoneResult.Failed("Volume samajh nahi aya.")
        }
    } catch (e: Exception) { PhoneResult.Failed("Volume change nahi hua: ${e.message?.take(60)}") }
}
