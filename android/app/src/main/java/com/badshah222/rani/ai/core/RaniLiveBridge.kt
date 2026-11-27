package com.badshah222.rani.ai.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.badshah222.rani.ai.data.phone.ContactsHelper
import java.net.URLEncoder

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

    @JavascriptInterface
    fun onLiveConnected() {
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
        LiveState.onTranscript(who, text)
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
        return try {
            when (action) {
                "open_app" -> svc?.openApp(s("packageName", "app")) ?: intentOpenApp(s("packageName", "app"))
                "search_youtube", "open_youtube" -> svc?.searchYouTube(s("query")) ?: intentSearchYouTube(s("query"))
                "search_chrome" -> svc?.searchChrome(s("query")) ?: intentSearchChrome(s("query"))
                "open_whatsapp" -> svc?.openWhatsApp() ?: intentOpenApp("com.whatsapp").let {
                    if (it == "ok") "ok" else intentOpenApp("com.whatsapp.w4b")
                }
                "send_whatsapp" -> svc?.sendWhatsApp(s("contact", "to", "target"), s("message"))
                    ?: intentSendWhatsApp(s("contact", "to", "target"), s("message"))
                "type_text" -> svc?.typeText(s("text")) ?: "noaccessibility"
                "tap_on_screen" -> svc?.tapText(s("text", "label")) ?: "noaccessibility"
                "scroll_screen" -> svc?.scroll(s("direction")) ?: "noaccessibility"
                "press_back" -> svc?.goBack() ?: "noaccessibility"
                "go_home" -> svc?.goHome() ?: "noaccessibility"
                "read_screen" -> svc?.readScreen() ?: "noaccessibility"
                "end_call" -> "ok" // the page ends the call itself, after the toolResponse is sent
                else -> "unknown"
            }
        } catch (e: Exception) { "fail" }
    }

    /** v1.7.0 B-1(c): the call page's Minimize button. */
    @JavascriptInterface
    fun onMinimize() {
        mainHandler.post { runCatching { onMinimizeRequested() } }
    }

    @JavascriptInterface
    fun openApp(packageName: String): String =
        RaniAccessibilityService.instance?.openApp(packageName) ?: "noaccessibility"

    @JavascriptInterface
    fun searchYouTube(query: String): String =
        RaniAccessibilityService.instance?.searchYouTube(query) ?: "noaccessibility"

    @JavascriptInterface
    fun searchChrome(query: String): String =
        RaniAccessibilityService.instance?.searchChrome(query) ?: "noaccessibility"

    /** Opens the WhatsApp chat with the message drafted — the user
     *  presses Send himself. Never auto-sends from the call page. */
    @JavascriptInterface
    fun sendWhatsApp(contactOrNumber: String, message: String): String =
        RaniAccessibilityService.instance?.sendWhatsApp(contactOrNumber, message) ?: "noaccessibility"

    @JavascriptInterface
    fun openWhatsApp(): String =
        RaniAccessibilityService.instance?.openWhatsApp() ?: "noaccessibility"

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
     *  fires. Each returns the same status strings as the service. */
    private fun intentOpenApp(packageName: String): String {
        val q = packageName.trim()
        if (q.isEmpty()) return "fail"
        return try {
            val pm = appCtx.packageManager
            var intent = runCatching { pm.getLaunchIntentForPackage(q) }.getOrNull()
            if (intent == null) {
                val apps = runCatching { pm.getInstalledApplications(0) }.getOrNull() ?: emptyList()
                val hit = apps.firstOrNull {
                    runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()?.equals(q, ignoreCase = true) == true ||
                        it.packageName.contains(q.lowercase().replace(" ", ""), ignoreCase = true)
                }
                intent = hit?.let { runCatching { pm.getLaunchIntentForPackage(it.packageName) }.getOrNull() }
            }
            if (intent == null) return "notfound"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(intent)
            "ok"
        } catch (e: Exception) { "fail" }
    }

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
}
