package com.badshah222.rani.ai.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import com.badshah222.rani.ai.data.phone.ContactsHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.8.0 §20.1.3 — ONE shared Live-page bridge, two hosts.
 *
 * The hero WebView (HeroLiveWebView, §20.1) and RaniLiveActivity need the SAME
 * `RaniApp` bridge, so the two paths cannot drift. Every method below is
 * copied verbatim from RaniLiveActivity's inner RaniBridge (v1.6.1/v1.7.0,
 * the PROVEN call engine) — the only changes are: the Activity-specific
 * parts (finish(), ensureCallOverlay(), minimizeCall()) arrive as callbacks,
 * and the NEW onLiveAudioLevel (§20.2.5) feeds LiveState.audioLevel.
 *
 * @param ctx any Context (the host Activity for RaniLiveActivity, the
 *   AndroidView context — MainActivity — for the hero). Intents always
 *   carry FLAG_ACTIVITY_NEW_TASK, so an application context is safe too.
 * @param onConnected called (main thread) once the page's Live socket
 *   completed setup — the host starts the floating overlay then.
 * @param onMinimizeRequested called (main thread) when the page's Minimize
 *   button is tapped — the host drops to the floating call card. In the
 *   hero there is no call screen to minimise; the host only ensures the
 *   floating overlay is up.
 * @param onEnded called (main thread) when the page ends the call — the
 *   host finishes (RaniLiveActivity) or clears the hero key (hero).
 */
class RaniLiveBridge(
    ctx: Context,
    private val onConnected: () -> Unit = {},
    private val onMinimizeRequested: () -> Unit = {},
    private val onEnded: () -> Unit = {},
) {
    private val appCtx: Context = ctx.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Held audio-focus request (BADSHAH micTakeover pattern) — released by releaseMic(). */
    private var audioFocusReq: AudioFocusRequest? = null

    /** v2.0.0 §22.2 ended-latch: after CallEngine.end() sets isActive=false,
     *  no stale page callback may set LiveState.active=true ever again.
     *  The engine is the single source of truth for "is a call alive";
     *  a null engine (no call engine exists) does not block — the latch
     *  only bites once a call has existed and ended. onCallEnded only
     *  ever clears state, so it needs no latch. */
    @JavascriptInterface
    fun onLiveConnected() {
        if (CallEngineHolder.engine?.isActive == false) return
        LiveState.onConnected()
        mainHandler.post { runCatching { onConnected() } }
    }

    @JavascriptInterface
    fun onCallEnded() {
        LiveState.onEnded()
        mainHandler.post { runCatching { onEnded() } }
    }

    /** v1.7.0: live state word from the page — "connecting" | "listening"
     *  | "speaking" | "thinking" | "error" | "ended". Feeds LiveState,
     *  which the hero, the status pill, the character animation and the
     *  floating card all read. */
    @JavascriptInterface
    fun onLiveState(state: String) {
        if (CallEngineHolder.engine?.isActive == false) return
        LiveState.setState(state)
    }

    /** v1.7.0: latest caption line from the page (who = "RANI" or "Babu Jaan"). */
    @JavascriptInterface
    fun onLiveCaption(who: String, text: String) {
        LiveState.setCaption(who, text)
    }

    /** v1.7.0: page mute state (true = muted). */
    @JavascriptInterface
    fun onLiveMuted(muted: Boolean) {
        LiveState.setMuted(muted)
    }

    /** v1.7.0: a full transcript turn — user speech ("user") or RANI's
     *  reply ("assistant"). HomeViewModel reads this to auto-detect the
     *  emotion DURING the call, with no tap and no restart. */
    @JavascriptInterface
    fun onTranscript(who: String, text: String) {
        if (CallEngineHolder.engine?.isActive == false) return
        LiveState.onTranscript(who, text)
    }

    /** v2.0.0 §22.1 diagnostics: the page's tool-call stages reach logcat
     *  on the SAME tag as the bridge's own stages ("RANI-Live-Tool"), so
     *  one `adb logcat` grep tells the whole story: "toolCall received"
     *  -> "tool dispatched" -> "toolResponse sent". */
    @JavascriptInterface
    fun toolLog(msg: String) {
        Log.d("RANI-Live-Tool", msg)
    }

    /** v1.8.0 §20.2.5: live output audio level 0..1 from the page's 100 ms
     *  analyser interval — the dot field vibrates with her voice. */
    @JavascriptInterface
    fun onLiveAudioLevel(level: Float) {
        LiveState.onAudioLevel(level)
    }

    /** v1.7.0 B-2 — ONE dispatcher for every Live function call. The
     *  page's tool handler calls this with the tool name + its args as
     *  a JSON object; the result string goes straight back to the model
     *  as the toolResponse. Actions run through the accessibility
     *  service when it is on; the pure-intent actions (open an app,
     *  YouTube/Chrome search, WhatsApp) fall back to plain intents,
     *  because they need no accessibility API at all — the chat path
     *  already opens apps the same way. Type/tap/scroll/read DO need the
     *  service and report "noaccessibility" without it, so a command
     *  never silently does nothing (§19.9.5). sendWhatsApp only ever
     *  opens a wa.me DRAFT — the user presses Send himself; nothing here
     *  taps Send. */
    @JavascriptInterface
    fun raniPhoneAction(action: String, argsJson: String): String {
        val args = runCatching { org.json.JSONObject(argsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "fail"
        fun s(vararg keys: String): String {
            for (k in keys) { val v = args.optString(k, ""); if (v.isNotEmpty()) return v }
            return ""
        }
        val svc = RaniAccessibilityService.instance
        // v2.0.0 §22.1 diagnostics, stage 2 of 3 (same logcat tag as the
        // page stages — "RANI-Live-Tool").
        Log.d("RANI-Live-Tool", "tool dispatched: $action -> raniPhoneAction")
        val result = try {
            when (action) {
                // v1.9.0 §21.1: chain on FAILURE, not on null — the service
                // result is only kept when it actually worked ("ok") or
                // asked a clarification ("multiple:…"); anything else falls
                // through to the plain-intent path, which needs no
                // accessibility API at all.
                "open_app" -> openAppResolved(s("packageName", "app"))
                "search_youtube", "open_youtube" -> chain(svc?.searchYouTube(s("query"))) { intentSearchYouTube(s("query")) }
                "search_chrome" -> chain(svc?.searchChrome(s("query"))) { intentSearchChrome(s("query")) }
                "open_whatsapp" -> chain(svc?.openWhatsApp()) {
                    val r = intentOpenApp("com.whatsapp")
                    if (r == "ok") "ok" else intentOpenApp("com.whatsapp.w4b")
                }
                "send_whatsapp" -> chain(svc?.sendWhatsApp(s("contact", "to", "target"), s("message"))) {
                    intentSendWhatsApp(s("contact", "to", "target"), s("message"))
                }
                "type_text" -> svc?.typeText(s("text")) ?: "noaccessibility"
                "tap_on_screen" -> svc?.tapText(s("text", "label")) ?: "noaccessibility"
                "scroll_screen" -> svc?.scroll(s("direction")) ?: "noaccessibility"
                "press_back" -> svc?.goBack() ?: "noaccessibility"
                "go_home" -> svc?.goHome() ?: "noaccessibility"
                "read_screen" -> svc?.readScreen() ?: "noaccessibility"
                "end_call" -> "ok" // the page ends the call itself, after the toolResponse is sent
                // ---- v1.9.0 §21.8 — full voice phone control ----
                "call_contact" -> callContact(s("contact", "name", "target"))
                "send_sms" -> sendSms(s("contact", "to", "target"), s("message"))
                "set_alarm" -> setAlarm(s("time"), s("hour").toIntOrNull(), s("minute").toIntOrNull(), s("label"))
                "set_timer" -> setTimer(s("duration"), s("seconds").toIntOrNull(), s("minutes").toIntOrNull(), s("label"))
                "set_reminder" -> setReminder(s("text", "reminder", "title"), s("when", "time", "due"))
                "open_settings" -> openSettings(s("section", "page", "setting"))
                "set_volume" -> setVolume(s("direction", "level"))
                "get_time" -> getTime()
                "get_battery" -> getBattery()
                "play_music" -> playMusic(s("query", "song"), s("app"))
                "open_camera" -> openCamera()
                "take_photo" -> takePhoto()
                "open_gallery" -> openGallery()
                "open_browser" -> openBrowser(s("url", "site", "query"))
                else -> "unknown"
            }
        } catch (e: Exception) { "fail" }
        // v2.0.0 §22.1 diagnostics: the bridge logs the same result string
        // it returned, so "dispatched" with no "result" means the bridge
        // swallowed the call.
        Log.d("RANI-Live-Tool", "tool result: $action = $result")
        return result
    }

    /** v1.9.0 §21.1: keep the service result only when it worked or asked
     *  a clarification; on any other result (or no service) run the
     *  fallback. A command never silently does nothing. */
    private inline fun chain(serviceResult: String?, fallback: () -> String): String {
        if (serviceResult != null && (serviceResult == "ok" || serviceResult.startsWith("multiple:"))) return serviceResult
        return fallback()
    }

    /** v1.9.0 §21.1: ONE shared resolver + intent fallback. Opening an app
     *  NEVER returns "noaccessibility" — it needs no accessibility API. */
    private fun openAppResolved(query: String): String {
        val svc = RaniAccessibilityService.instance
        val r = svc?.openApp(query)
        if (r != null && (r == "ok" || r.startsWith("multiple:"))) return r
        return AppResolver.launch(appCtx, query)
    }

    /** v1.7.0 B-1(c): the call page's Minimize button. */
    @JavascriptInterface
    fun onMinimize() {
        mainHandler.post { runCatching { onMinimizeRequested() } }
    }

    @JavascriptInterface
    fun openApp(packageName: String): String = openAppResolved(packageName)

    @JavascriptInterface
    fun searchYouTube(query: String): String =
        chain(RaniAccessibilityService.instance?.searchYouTube(query)) { intentSearchYouTube(query) }

    @JavascriptInterface
    fun searchChrome(query: String): String =
        chain(RaniAccessibilityService.instance?.searchChrome(query)) { intentSearchChrome(query) }

    /** Opens the WhatsApp chat with the message drafted — the user
     *  presses Send himself. Never auto-sends from the call page. */
    @JavascriptInterface
    fun sendWhatsApp(contactOrNumber: String, message: String): String =
        chain(RaniAccessibilityService.instance?.sendWhatsApp(contactOrNumber, message)) {
            intentSendWhatsApp(contactOrNumber, message)
        }

    @JavascriptInterface
    fun openWhatsApp(): String = chain(RaniAccessibilityService.instance?.openWhatsApp()) {
        val r = intentOpenApp("com.whatsapp")
        if (r == "ok") "ok" else intentOpenApp("com.whatsapp.w4b")
    }

    @JavascriptInterface
    fun scrollPhone(dir: String): String =
        RaniAccessibilityService.instance?.scroll(dir) ?: "noaccessibility"

    @JavascriptInterface
    fun typeText(text: String): String =
        RaniAccessibilityService.instance?.typeText(text) ?: "noaccessibility"

    /** Screen text, on-device only — used to answer the current question,
     *  never stored or uploaded. */
    @JavascriptInterface
    fun readScreen(): String =
        RaniAccessibilityService.instance?.readScreen() ?: ""

    @JavascriptInterface
    fun micTakeover() {
        // BADSHAH's BadshahBridge.micTakeover (v6.6 MIC CHHEEN-LO): audio
        // focus + MODE_IN_COMMUNICATION so the system frees the mic for
        // the WebView's getUserMedia, speaker ON, and the foreground
        // CallService keeps the call alive if the host backgrounds.
        mainHandler.post {
            try {
                val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusReq = req
                am.requestAudioFocus(req)
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = true
            } catch (e: Exception) { }
            try { CallService.start(appCtx) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun micRelease() {
        mainHandler.post { releaseMic() }
    }

    /** Shared mic-release logic (called by the JS bridge and by the host's
     *  onDestroy). Must run on the main thread — onDestroy already does. */
    fun releaseMic() {
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusReq?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            audioFocusReq = null
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) { }
        try { CallService.stop(appCtx) } catch (e: Exception) { }
    }

    /** Plain-intent fallbacks for the phone actions that need no
     *  accessibility API (B-2) — the same intents the chat path already
     *  fires. Each returns the same status strings as the service.
     *  v1.9.0 §21.1: opening an app goes through the ONE shared resolver. */
    private fun intentOpenApp(packageName: String): String = AppResolver.launch(appCtx, packageName)

    private fun intentSearchYouTube(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return intentOpenApp("com.google.android.youtube")
        return try {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appCtx.startActivity(i); return "ok" }
            appCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ok"
        } catch (e: Exception) { "fail" }
    }

    private fun intentSearchChrome(query: String): String {
        val q = query.trim()
        return try {
            val url = if (q.isEmpty()) "https://www.google.com"
                else "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appCtx.startActivity(i); return "ok" }
            appCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ok"
        } catch (e: Exception) { "fail" }
    }

    /** WhatsApp draft via wa.me (mirrors the service): the chat opens with
     *  the message drafted — the user presses Send himself. Never auto-sends. */
    private fun intentSendWhatsApp(contactOrNumber: String, message: String): String {
        val target = contactOrNumber.trim()
        if (target.isEmpty()) return "fail"
        return try {
            val digitsOnly = target.matches(Regex("^[+]?[0-9][0-9 \\-]{5,}$"))
            val number = if (digitsOnly) target.replace(" ", "").replace("-", "")
            else {
                val entry = runCatching { ContactsHelper.findBest(appCtx, target) }.getOrNull()
                    ?: return "notfound"
                runCatching { ContactsHelper.bestNumber(entry) }.getOrNull() ?: return "notfound"
            }
            val e164 = runCatching { ContactsHelper.toE164(appCtx, number) }.getOrNull() ?: number
            var url = "https://wa.me/" + e164.removePrefix("+")
            if (message.isNotBlank()) url += "?text=" + URLEncoder.encode(message, "UTF-8")
            appCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ok"
        } catch (e: Exception) { "fail" }
    }

    // ---- v1.9.0 §21.8 — full voice phone control (intent + native tools) ----
    // Every tool reports back "ok" / "notfound" / "fail" / "noaccessibility"
    // / "multiple:…" — a command NEVER silently does nothing. Messages stay
    // draft/confirm, always. Contacts stay on-device, never uploaded.
    // Explicitly NOT added: notification reading, SMS/call-log reading,
    // remote control from any other device.

    private fun launchIntent(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appCtx.startActivity(intent)
        true
    } catch (e: Exception) { false }

    /** Resolves a contact name (or digits) to a number. Returns
     *  "multiple:Name1, Name2" on a tie so RANI asks which one. */
    private fun resolveNumber(target: String): String {
        val t = target.trim()
        if (t.isEmpty()) return "fail"
        if (t.matches(Regex("^[+]?[0-9][0-9 \\-]{5,}$"))) return t.replace(" ", "").replace("-", "")
        if (!ContactsHelper.hasPermission(appCtx)) return "nocontacts"
        val all = runCatching { ContactsHelper.loadAll(appCtx) }.getOrDefault(emptyList())
        val exact = all.filter { it.name.equals(t, ignoreCase = true) }
        if (exact.size > 1) {
            val names = exact.map { it.name }.distinct().take(3)
            if (names.size > 1) return "multiple:" + names.joinToString(", ")
        }
        val entry = runCatching { ContactsHelper.findBest(appCtx, t) }.getOrNull() ?: return "notfound"
        return runCatching { ContactsHelper.bestNumber(entry) }.getOrNull() ?: "notfound"
    }

    private fun callContact(target: String): String {
        val num = resolveNumber(target)
        if (num == "fail" || num == "notfound" || num == "nocontacts" || num.startsWith("multiple:")) return num
        return try {
            // ACTION_CALL places the call at once — it is visible and he can
            // hang up, so no confirm dialog (CALL_PHONE is already declared).
            if (launchIntent(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))) "ok" else "fail"
        } catch (e: SecurityException) { "fail" } catch (e: Exception) { "fail" }
    }

    /** SMS draft only — the user presses Send himself. Never auto-sent. */
    private fun sendSms(target: String, message: String): String {
        val num = resolveNumber(target)
        if (num == "fail" || num == "notfound" || num == "nocontacts" || num.startsWith("multiple:")) return num
        return try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
            if (message.isNotBlank()) i.putExtra("sms_body", message)
            if (launchIntent(i)) "ok" else "fail"
        } catch (e: Exception) { "fail" }
    }

    /** Parses "6", "6:30", "6 am", "6:30 pm" -> (hour24, minute) or null. */
    private fun parseTime(raw: String): Pair<Int, Int>? {
        val m = Regex("(?i)^\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*$").find(raw.trim()) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun setAlarm(time: String, hour: Int?, minute: Int?, label: String): String {
        val hm = if (hour != null) hour to (minute ?: 0) else parseTime(time)
        if (hm == null) return "fail"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hm.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, hm.second)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "RANI AI" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        // Opens the system alarm editor with time + label prefilled; the
        // user confirms in the system UI.
        return if (launchIntent(i)) "ok" else "fail"
    }

    private fun parseDuration(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        Regex("(\\d+)\\s*(?:h|hour|hours)").find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 3600 }
        Regex("(\\d+)\\s*(?:m|min|mins|minute|minutes)").find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 60 }
        Regex("(\\d+)\\s*(?:s|sec|secs|second|seconds)").find(s)?.let { return it.groupValues[1].toIntOrNull() }
        return s.toIntOrNull()
    }

    private fun setTimer(duration: String, seconds: Int?, minutes: Int?, label: String): String {
        val secs = seconds ?: minutes?.let { it * 60 } ?: parseDuration(duration)
        if (secs == null || secs <= 0) return "fail"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "RANI AI" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return if (launchIntent(i)) "ok" else "fail"
    }

    /** Reminder: saved on-device in the existing tasks store (local only).
     *  RANI confirms what she saved and when. */
    private fun setReminder(text: String, whenText: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "fail"
        return try {
            val store = SecureStore(appCtx)
            val arr = runCatching { JSONArray(store.tasksJson) }.getOrNull() ?: JSONArray()
            arr.put(JSONObject()
                .put("id", "rem_${System.currentTimeMillis()}")
                .put("title", t)
                .put("due", whenText.trim())
                .put("status", "pending"))
            store.tasksJson = arr.toString()
            "ok"
        } catch (e: Exception) { "fail" }
    }

    /** Opens the exact settings page; she does not toggle radios herself —
     *  toggles live where Android puts them, and she says so. */
    private fun openSettings(section: String): String {
        val action = when (section.trim().lowercase()) {
            "wifi", "wi-fi", "wireless" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume", "audio" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS // FIX (CI run #45): ACTION_BATTERY_SETTINGS does not exist in android.provider.Settings (verified in SDK API 35/36) — Battery Saver settings is the real battery page.
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val i = Intent(action)
            if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                i.putExtra(Settings.EXTRA_APP_PACKAGE, appCtx.packageName)
            }
            if (launchIntent(i)) "ok" else "fail"
        } catch (e: Exception) { "fail" }
    }

    /** Native bridge: AudioManager — no accessibility API needed. */
    private fun setVolume(direction: String): String {
        return try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = AudioManager.STREAM_MUSIC
            when (direction.trim().lowercase()) {
                "up", "raise", "louder", "increase" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down", "lower", "quieter", "decrease" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                else -> return "fail"
            }
            "ok"
        } catch (e: Exception) { "fail" }
    }

    /** Answered in voice — the page passes the value through as the tool
     *  result text (see handleToolCall in rani-live.html). */
    private fun getTime(): String =
        "time:" + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())

    private fun getBattery(): String {
        return try {
            val bm = appCtx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            "battery:$pct percent" + if (charging) " and charging" else ""
        } catch (e: Exception) { "fail" }
    }

    /** Opens the music app at the search; playback starts from the app's
     *  own UI. */
    private fun playMusic(query: String, app: String): String {
        val q = query.trim()
        val wantSpotify = app.lowercase().contains("spotify")
        if (wantSpotify) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + URLEncoder.encode(q, "UTF-8")))
                .setPackage("com.spotify.music")
            if (q.isEmpty()) {
                if (AppResolver.launch(appCtx, "Spotify") == "ok") return "ok"
            } else if (launchIntent(i)) return "ok"
            if (AppResolver.launch(appCtx, "Spotify") == "ok") return "ok"
            return "notfound"
        }
        // YouTube Music (default): search URL, browser/app handles it.
        val url = if (q.isEmpty()) "https://music.youtube.com"
        else "https://music.youtube.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "ok" else "fail"
    }

    private fun openCamera(): String {
        return if (launchIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) "ok" else "fail"
    }

    /**
     * Take a photo: open the camera, then tap the shutter via accessibility
     * when the service is ON. Service OFF -> the camera still opens and
     * RANI says "tap the shutter" ("opened", never a silent nothing).
     *
     * F1 fix: the shutter node does NOT exist the instant the camera intent
     * returns — the camera app has not appeared yet — so a single immediate
     * tap always missed and the function always fell through to "opened".
     * This runs on the WebView JS-bridge (worker) thread, so it polls the
     * shutter labels for up to ~3 s (every ~300 ms) and returns "ok" only
     * on a real tap.
     */
    private fun takePhoto(): String {
        if (openCamera() != "ok") return "fail"
        val svc = RaniAccessibilityService.instance ?: return "opened"
        val labels = listOf("Shutter", "Take photo", "Capture")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            for (label in labels) {
                if (svc.tapText(label) == "ok") return "ok"
            }
            try { Thread.sleep(300) } catch (e: InterruptedException) { break }
        }
        return "opened"
    }

    private fun openGallery(): String {
        val r = AppResolver.launch(appCtx, "Photos")
        if (r == "ok") return "ok"
        val g = AppResolver.launch(appCtx, "Gallery")
        if (g == "ok") return "ok"
        return try {
            if (launchIntent(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))) "ok"
            else if (launchIntent(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))) "ok"
            else "notfound"
        } catch (e: Exception) { "fail" }
    }

    private fun openBrowser(urlOrQuery: String): String {
        val q = urlOrQuery.trim()
        if (q.isEmpty()) return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))) "ok" else "fail"
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains(".") && !q.contains(" ") -> "https://$q"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        }
        return if (launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "ok" else "fail"
    }
}
