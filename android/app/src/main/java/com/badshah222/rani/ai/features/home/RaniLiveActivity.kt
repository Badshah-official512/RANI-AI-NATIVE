package com.badshah222.rani.ai.features.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.FloatingOrbService
import com.badshah222.rani.ai.core.LiveState
import com.badshah222.rani.ai.core.RaniLiveBridge
import java.net.URLEncoder

/**
 * RANI AI GF — Live voice call screen (v1.6.1, boss's order 2026-11-27).
 *
 * The call runs on BADSHAH AI's PROVEN WebView engine: a plain Activity (NOT
 * Compose AndroidView — the Compose WebView went BLACK in v1.3.2, that
 * approach is dead) hosting the RANI Live page, which is BUNDLED INSIDE THE
 * APK (file:///android_asset/rani-live.html) — the page never loads from the
 * website (boss's order). The page uses the browser's own `new WebSocket`
 * direct to Google Live — BADSHAH's exact way, the path that works perfectly
 * on the boss's phone while every native socket engine (OkHttp, Java-WebSocket)
 * and the HTTP relay (v1.5.0-v1.5.2) hung.
 *
 * Everything else in the app stays native Stonic UI; this Activity is ONLY
 * the call transport + screen. The page renders the whole RANI call UI
 * (character, status, captions, Mute/End) — no native call UI on top.
 *
 * BADSHAH pattern copied: WebView created in onCreate, settings,
 * CookieManager, WebChromeClient permission-hold/grant (RECORD_AUDIO),
 * WebViewClient internal-host routing + onReceivedError reload, loadUrl()
 * AFTER the WebView is added to its FrameLayout.
 */
class RaniLiveActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KEY = "EXTRA_KEY"
        const val EXTRA_VOICE = "EXTRA_VOICE"
        const val EXTRA_MODEL = "EXTRA_MODEL"
        const val EXTRA_VER = "EXTRA_VER"

        /** Broadcast that ends this call Activity (sent by HomeViewModel.stopLiveSession()). */
        const val ACTION_END_LIVE = "com.badshah222.rani.ai.END_LIVE_CALL"

        /** Broadcast that mutes/unmutes the Live page mic (sent by
         *  HomeViewModel.setLiveMuted() and the floating call card).
         *  Extra EXTRA_MUTED (boolean). */
        const val ACTION_SET_LIVE_MUTED = "com.badshah222.rani.ai.SET_LIVE_MUTED"
        const val EXTRA_MUTED = "EXTRA_MUTED"

        /** Broadcast that pushes a newly auto-detected emotion into the Live
         *  page (sent by HomeViewModel.applyEmotion() while a call is live).
         *  Extras: EXTRA_EMOTION_ID / LABEL / COLOR ("#RRGGBB") / STYLE.
         *  The page updates its chip + glow and sends a clientContent turn
         *  so RANI's voice/style changes mid-call (B-3). */
        const val ACTION_SET_LIVE_EMOTION = "com.badshah222.rani.ai.SET_LIVE_EMOTION"
        const val EXTRA_EMOTION_ID = "EXTRA_EMOTION_ID"
        const val EXTRA_EMOTION_LABEL = "EXTRA_EMOTION_LABEL"
        const val EXTRA_EMOTION_COLOR = "EXTRA_EMOTION_COLOR"
        const val EXTRA_EMOTION_STYLE = "EXTRA_EMOTION_STYLE"

        /** True while this call Activity is the foreground Activity. The
         *  floating service reads it (with MainActivity.appInForeground) so
         *  the call card / avatar hide while the call page itself is up and
         *  appear the moment the user leaves it (B-1). MainActivity's own
         *  onStop fires AFTER this Activity resumes, so this flag — not
         *  MainActivity's — is the authority while a call is on screen. */
        @Volatile var callInForeground = false

        /** N-2: true while a RaniLiveActivity instance exists (onCreate →
         *  onDestroy). HomeViewModel pairs it with LiveState.active so a
         *  return-to-call tap (floating card / avatar) brings the EXISTING
         *  call forward instead of starting a second Live socket + mic
         *  pipeline on top of it. */
        @Volatile var isAlive = false

        private const val REQ_WEB = 45
        private const val LIVE_URL = "file:///android_asset/rani-live.html"
    }

    private lateinit var web: WebView
    private var pendingPermReq: PermissionRequest? = null
    private var triedReload = false
    private var liveUrl = ""

    /** v1.8.0 §20.1.3 — the shared bridge (same class the hero WebView
     *  uses). Created in onCreate; the mic takeover/release + phone-action
     *  dispatcher live in it now. */
    private lateinit var bridge: RaniLiveBridge

    /** True once the page's Live socket completed setup (window.RaniApp.onLiveConnected). */
    @Volatile private var liveConnected = false

    /** The overlay-permission ask for a call fires at most once per call, so
     *  minimizing twice does not nag the boss with the Settings screen. */
    private var callOverlayAsked = false

    /** One-time overlay-permission ask, launched from minimizeCall(). On
     *  return with the permission granted, the floating service starts so
     *  the call card can appear over other apps. */
    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) ensureCallOverlay()
    }

    private val endReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_END_LIVE) finish()
        }
    }

    /** v1.7.0: hero / floating-card Mute arrives here and is forwarded into
     *  the page via window.setRaniMuted(bool). */
    private val muteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SET_LIVE_MUTED) {
                setRaniMuted(intent.getBooleanExtra(EXTRA_MUTED, false))
            }
        }
    }

    /** v1.7.0 B-3: a newly auto-detected emotion arrives here and is pushed
     *  into the page via window.setRaniEmotion(...) — chip + glow update
     *  and a clientContent turn so her voice changes mid-call. */
    private val emotionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SET_LIVE_EMOTION) {
                pushEmotionToPage(
                    intent.getStringExtra(EXTRA_EMOTION_ID) ?: "",
                    intent.getStringExtra(EXTRA_EMOTION_LABEL) ?: "",
                    intent.getStringExtra(EXTRA_EMOTION_COLOR) ?: "#8B3DFF",
                    intent.getStringExtra(EXTRA_EMOTION_STYLE) ?: "",
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        val voice = intent.getStringExtra(EXTRA_VOICE) ?: "Aoede"
        val model = intent.getStringExtra(EXTRA_MODEL) ?: "gemini-3.8-live"
        val ver = intent.getStringExtra(EXTRA_VER) ?: "1.8.0"
        liveUrl = "$LIVE_URL?key=" + URLEncoder.encode(key, "UTF-8") +
            "&voice=" + URLEncoder.encode(voice, "UTF-8") +
            "&model=" + URLEncoder.encode(model, "UTF-8") +
            "&v=" + URLEncoder.encode(ver, "UTF-8")

        ContextCompat.registerReceiver(
            this, endReceiver, IntentFilter(ACTION_END_LIVE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, muteReceiver, IntentFilter(ACTION_SET_LIVE_MUTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, emotionReceiver, IntentFilter(ACTION_SET_LIVE_EMOTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0B0B1A"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // v1.6.1: the call page is BUNDLED inside the APK
                // (file:///android_asset/) — file access MUST be on for it to
                // load, and file-origin pages need these flags to open the wss
                // WebSocket to Google. The one deliberate difference from
                // BADSHAH's settings (BADSHAH sets allowFileAccess=false
                // because its page comes from the website).
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
            // v1.8.0 §20.1.3: the shared bridge — same class the hero
            // WebView uses, so the two paths cannot drift. Callbacks keep
            // this Activity's behaviour exactly as before.
            // FIX (CI run #41): `this` here is the WebView (apply receiver),
            // not the Activity — the bridge needs a Context, so qualify it.
            bridge = RaniLiveBridge(
                this@RaniLiveActivity,
                onConnected = { liveConnected = true; ensureCallOverlay() },
                onMinimizeRequested = { minimizeCall() },
                onEnded = { finish() },
            )
            addJavascriptInterface(bridge, "RaniApp")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // BADSHAH's way: NEVER deny outright — hold the web request,
                    // ask Android, then grant/deny in onRequestPermissionsResult
                    // so the SAME call connects.
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!wantsAudio && !wantsVideo) { request.deny(); return }
                    val missing = ArrayList<String>()
                    if (wantsAudio && ContextCompat.checkSelfPermission(this@RaniLiveActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
                    if (wantsVideo && ContextCompat.checkSelfPermission(this@RaniLiveActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA)
                    if (missing.isEmpty()) { request.grant(request.resources); return }
                    if (pendingPermReq != null) { runCatching { pendingPermReq?.deny() }; pendingPermReq = null }
                    pendingPermReq = request
                    ActivityCompat.requestPermissions(this@RaniLiveActivity, missing.toTypedArray(), REQ_WEB)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val host = req.url.host ?: ""
                    val internal = host.endsWith("pages.dev") || host.contains("google") ||
                        host.contains("firebase") || host.contains("gstatic") || host.contains("googleapis")
                    if (internal) return false
                    startActivity(Intent(Intent.ACTION_VIEW, req.url))
                    return true
                }

                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame && !triedReload) {
                        triedReload = true
                        view.loadUrl(liveUrl)
                    }
                }
            }
        }
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0B0B1A"))
            addView(web)
        }
        setContentView(root)
        // Load AFTER the WebView is attached — loading before attachment is
        // what left the Compose WebView blank white in v1.3.0.
        web.loadUrl(liveUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                endCall()
                finish()
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WEB) {
            val req = pendingPermReq ?: return
            pendingPermReq = null
            val grant = ArrayList<String>()
            for (res in req.resources) {
                val perm = when (res) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    else -> null
                }
                if (perm != null && ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) grant.add(res)
            }
            if (grant.isNotEmpty()) req.grant(grant.toTypedArray()) else {
                req.deny()
                Toast.makeText(this, "Microphone permission is needed for the voice call.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Shared mic-release logic (called by the JS bridge and by onDestroy)
     *  — now in the shared bridge (§20.1.3); this only delegates. */
    private fun releaseMicNative() {
        if (::bridge.isInitialized) runCatching { bridge.releaseMic() }
    }

    /** End the call from native code (Back press, stopLiveSession). */
    fun endCall() {
        runCatching { web.evaluateJavascript("window.endRaniCall && endRaniCall()", null) }
    }

    /** v1.7.0: mute/unmute the Live page mic from the hero or the floating
     *  call card — same page function the page's own Mute button uses. */
    fun setRaniMuted(muted: Boolean) {
        LiveState.setMuted(muted)
        runCatching { web.evaluateJavascript("window.setRaniMuted && setRaniMuted($muted)", null) }
    }

    /** v1.7.0 B-3: push a newly detected emotion into the Live page —
     *  chip + glow update there, plus a clientContent turn so RANI's
     *  voice/style changes mid-call. Called from the emotion broadcast. */
    fun pushEmotionToPage(id: String, label: String, colorHex: String, style: String) {
        runCatching {
            web.evaluateJavascript(
                "window.setRaniEmotion && setRaniEmotion('${jsEsc(id)}','${jsEsc(label)}','${jsEsc(colorHex)}','${jsEsc(style)}')",
                null
            )
        }
    }

    /** Escape a string for a single-quoted JS literal (styles contain
     *  apostrophes, e.g. "the user's mood"). */
    private fun jsEsc(s: String): String = s
        .replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")

    /** B-1: start the floating service for this call (moved here from the
     *  dead HttpLiveSession.ensureCallOverlay — the live path is the WebView
     *  page, so the overlay must start from the live path). No-op without
     *  the overlay permission. */
    fun ensureCallOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        runCatching { FloatingOrbService.start(this) }
    }

    /** B-1(c): minimize the live call to the floating call card. With the
     *  overlay permission the service starts and the Activity drops to the
     *  background (onPause starts the service too); without it, the
     *  one-time permission ask fires once per call. */
    fun minimizeCall() {
        if (!liveConnected && !LiveState.active.value) { moveTaskToBack(true); return }
        if (Settings.canDrawOverlays(this)) {
            ensureCallOverlay()
            moveTaskToBack(true)
            return
        }
        if (!callOverlayAsked) {
            callOverlayAsked = true
            runCatching {
                overlayPermLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // B-1: while the call page is in front, the floating service hides
        // both the call card and the avatar (see FloatingOrbService).
        callInForeground = true
    }

    override fun onPause() {
        // B-1(a): the user left the call screen (Home, another app, a phone
        // action that opened YouTube/Chrome/WhatsApp) mid-call — the
        // floating service starts so the call card appears over the other app.
        callInForeground = false
        if (liveConnected) ensureCallOverlay()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        // Home pressed mid-call — same as onPause, but explicit: the card
        // must be up by the time the home screen shows.
        if (liveConnected) ensureCallOverlay()
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        runCatching { releaseMicNative() }
        runCatching { unregisterReceiver(endReceiver) }
        runCatching { unregisterReceiver(muteReceiver) }
        runCatching { unregisterReceiver(emotionReceiver) }
        // B-1: when the call is over and neither Live Voice nor the Floating
        // orb wants the service any more, stop it so no unwanted orb is
        // left behind. Rotation is safe: LiveState is still active then,
        // so nothing stops mid-call.
        if (!LiveState.active.value) {
            val store = (application as RaniApp).container.store
            if (!store.autoVoiceEnabled && !store.floatingOrbEnabled) {
                runCatching { FloatingOrbService.stop(this) }
            }
        }
        callInForeground = false
        isAlive = false
        runCatching { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}
