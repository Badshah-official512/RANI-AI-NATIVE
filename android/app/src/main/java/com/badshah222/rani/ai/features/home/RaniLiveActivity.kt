package com.badshah222.rani.ai.features.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.core.CallService
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

        private const val REQ_WEB = 45
        private const val LIVE_URL = "file:///android_asset/rani-live.html"
    }

    private lateinit var web: WebView
    private var pendingPermReq: PermissionRequest? = null
    private var triedReload = false
    private var liveUrl = ""

    /** Held audio-focus request (BADSHAH micTakeover pattern) — released by micRelease(). */
    private var audioFocusReq: AudioFocusRequest? = null

    /** True once the page's Live socket completed setup (window.RaniApp.onLiveConnected). */
    @Volatile private var liveConnected = false

    private val endReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_END_LIVE) finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        val voice = intent.getStringExtra(EXTRA_VOICE) ?: "Aoede"
        val model = intent.getStringExtra(EXTRA_MODEL) ?: "gemini-3.8-live"
        val ver = intent.getStringExtra(EXTRA_VER) ?: "1.6.1"
        liveUrl = "$LIVE_URL?key=" + URLEncoder.encode(key, "UTF-8") +
            "&voice=" + URLEncoder.encode(voice, "UTF-8") +
            "&model=" + URLEncoder.encode(model, "UTF-8") +
            "&v=" + URLEncoder.encode(ver, "UTF-8")

        ContextCompat.registerReceiver(
            this, endReceiver, IntentFilter(ACTION_END_LIVE),
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
            addJavascriptInterface(RaniBridge(), "RaniApp")
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

    /** JS bridge (window.RaniApp) — the Live page calls these. */
    inner class RaniBridge {
        @JavascriptInterface
        fun onLiveConnected() {
            liveConnected = true
        }

        @JavascriptInterface
        fun onCallEnded() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun micTakeover() {
            // BADSHAH's BadshahBridge.micTakeover (v6.6 MIC CHHEEN-LO): audio
            // focus + MODE_IN_COMMUNICATION so the system frees the mic for
            // the WebView's getUserMedia, speaker ON, and the foreground
            // CallService keeps the call alive if the Activity backgrounds.
            runOnUiThread {
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
                try { CallService.start(this@RaniLiveActivity) } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun micRelease() {
            runOnUiThread { releaseMicNative() }
        }
    }

    /** Shared mic-release logic (called by the JS bridge and by onDestroy). */
    private fun releaseMicNative() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusReq?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            audioFocusReq = null
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) { }
        try { CallService.stop(this@RaniLiveActivity) } catch (e: Exception) { }
    }

    /** End the call from native code (Back press, stopLiveSession). */
    fun endCall() {
        runCatching { web.evaluateJavascript("window.endRaniCall && endRaniCall()", null) }
    }

    override fun onDestroy() {
        runCatching { releaseMicNative() }
        runCatching { unregisterReceiver(endReceiver) }
        runCatching { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}
