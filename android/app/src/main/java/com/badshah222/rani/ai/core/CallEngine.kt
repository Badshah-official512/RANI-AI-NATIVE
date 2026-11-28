package com.badshah222.rani.ai.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.MainActivity
import com.badshah222.rani.ai.features.home.HeroMicPermission
import java.net.URLEncoder

/**
 * v1.9.0 §21.4 — the persistent call engine (MOST IMPORTANT FIX).
 *
 * The call WebView lives OUT of the hero composable, owned by MainActivity
 * (BADSHAH's pattern — a WebView cannot live in a Service: it needs an
 * Activity window for rendering + getUserMedia). MainActivity keeps a
 * persistent holder FrameLayout (GONE, 1 px, never visible) that OWNS the
 * WebView between screens. The hero box on Home is a SLOT, not an owner:
 * the engine attaches the WebView into the slot while Home is visible and
 * detaches it back into the holder on navigation — reparenting
 * (removeView -> addView), never recreation. The WebView is destroyed ONLY
 * on an explicit End — never on navigation, rotation or recomposition.
 *
 * RaniLiveActivity stays in the codebase and the manifest, unused by the
 * hero path — exactly as in v1.8.0. If this holder cannot be made to work,
 * dev must NOT silently fall back to launching RaniLiveActivity — report
 * the exact failure instead.
 */
object CallEngineHolder {
    @Volatile var engine: CallEngine? = null
    @Volatile var activity: MainActivity? = null
    /** Set by HomeViewModel: the page ended the call itself — clear the
     *  hero key + LiveState (stopLiveSession). Guarded against re-entry
     *  by the engine (webView is nulled before the callback fires). */
    var onCallEnded: (() -> Unit)? = null
}

@SuppressLint("SetJavaScriptEnabled")
class CallEngine(private val activity: MainActivity) {

    /** Persistent holder — owns the WebView between screens. */
    private val holder = FrameLayout(activity).apply {
        layoutParams = ViewGroup.LayoutParams(1, 1)
        visibility = android.view.View.GONE
    }

    @Volatile
    private var webView: WebView? = null

    @Volatile
    var isActive = false
        private set

    private var currentUrl = ""
    private var triedReload = false
    private var bridge: RaniLiveBridge? = null

    init {
        (activity.window.decorView as? ViewGroup)?.addView(holder)
    }

    /**
     * Creates the WebView ONCE per call, with the MainActivity context
     * (never a Compose context), the factory settings copied verbatim
     * from HeroLiveWebView, the SAME shared RaniLiveBridge, and the SAME
     * URL (+ v=3.0.0, &memory=, &pet=, &name=, &uninstall=). Refuses a second start while one
     * is active (the N-2 guard, now enforced in the engine).
     */
    fun start(key: String, voice: String, memory: String, petName: String, userName: String = "Badshah"): Boolean {
        if (webView != null) return false
        triedReload = false
        currentUrl = "file:///android_asset/rani-live.html?key=" + URLEncoder.encode(key, "UTF-8") +
            "&voice=" + URLEncoder.encode(voice, "UTF-8") +
            "&model=gemini-3.8-live&v=3.0.0&embed=1" +
            "&memory=" + URLEncoder.encode(memory, "UTF-8") +
            "&pet=" + URLEncoder.encode(petName, "UTF-8") +
            "&name=" + URLEncoder.encode(userName, "UTF-8") +
            // v3.0.0 §23.5 (dev-finish): the self-uninstall switch gates the
            // tool's DECLARATION in the page (&uninstall=1 only when ON).
            "&uninstall=" + (if (SecureStore(activity.applicationContext).selfUninstallEnabled) "1" else "0")
        val url = currentUrl
        val wv = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
            bridge = RaniLiveBridge(
                activity,
                onConnected = {
                    if (Settings.canDrawOverlays(activity)) {
                        runCatching { FloatingOrbService.start(activity) }
                    }
                },
                onMinimizeRequested = {
                    if (Settings.canDrawOverlays(activity)) {
                        runCatching { FloatingOrbService.start(activity) }
                    }
                },
                onEnded = {
                    // The page ended the call itself: tear the engine down
                    // (fromPage = true, so we do NOT re-ask the page to end)
                    // and tell the ViewModel to clear the hero key + state.
                    end(fromPage = true)
                    CallEngineHolder.onCallEnded?.invoke()
                },
            )
            addJavascriptInterface(bridge!!, "RaniApp")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // BADSHAH hold-and-grant: NEVER deny outright — hold the
                    // web request, ask Android, then grant/deny in
                    // MainActivity.onRequestPermissionsResult so the SAME
                    // call connects.
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!wantsAudio && !wantsVideo) { request.deny(); return }
                    val missing = ArrayList<String>()
                    if (wantsAudio && ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
                    if (wantsVideo && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA)
                    if (missing.isEmpty()) { request.grant(request.resources); return }
                    if (HeroMicPermission.pending != null) {
                        runCatching { HeroMicPermission.pending?.deny() }
                        HeroMicPermission.pending = null
                    }
                    HeroMicPermission.pending = request
                    ActivityCompat.requestPermissions(activity, missing.toTypedArray(), HeroMicPermission.REQ_WEB)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val host = req.url.host ?: ""
                    val internal = host.endsWith("pages.dev") || host.contains("google") ||
                        host.contains("firebase") || host.contains("gstatic") || host.contains("googleapis")
                    if (internal) return false
                    activity.startActivity(Intent(Intent.ACTION_VIEW, req.url))
                    return true
                }

                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame && !triedReload) {
                        triedReload = true
                        view.loadUrl(url)
                    }
                }
            }
        }
        webView = wv
        isActive = true
        holder.addView(wv, FrameLayout.LayoutParams(1, 1))
        // Load AFTER the WebView is attached — loading before attachment
        // is what left the Compose WebView blank white in v1.3.0.
        wv.loadUrl(url)
        return true
    }

    /** Attach the live WebView into the hero slot (reparenting, never
     *  recreation). A View with a parent throws on addView — always
     *  removeView from the old parent first, on the main thread. */
    fun attachTo(slot: FrameLayout) {
        val wv = webView ?: return
        activity.runOnUiThread {
            (wv.parent as? ViewGroup)?.removeView(wv)
            if (wv.parent == null) {
                slot.addView(
                    wv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    /** Detach the WebView from the hero slot back into the persistent
     *  holder. The socket stays open, the mic pipeline keeps running. */
    fun detachToHolder() {
        val wv = webView ?: return
        activity.runOnUiThread {
            (wv.parent as? ViewGroup)?.removeView(wv)
            if (wv.parent == null) holder.addView(wv, FrameLayout.LayoutParams(1, 1))
        }
    }

    fun setMuted(m: Boolean) {
        val wv = webView ?: return
        activity.runOnUiThread {
            runCatching { wv.evaluateJavascript("window.setRaniMuted && setRaniMuted($m)", null) }
        }
    }

    fun pushEmotion(id: String, label: String, colorHex: String, style: String) {
        val wv = webView ?: return
        val esc = { s: String -> s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ") }
        activity.runOnUiThread {
            runCatching {
                wv.evaluateJavascript(
                    "window.setRaniEmotion && setRaniEmotion('${esc(id)}','${esc(label)}','${esc(colorHex)}','${esc(style)}')",
                    null,
                )
            }
        }
    }

    /** Awareness (§21.3): feed one JPEG frame into the Live session as
     *  realtimeInput.video. A video failure never touches audio. */
    fun sendCameraFrame(base64Jpeg: String) {
        val wv = webView ?: return
        activity.runOnUiThread {
            runCatching {
                wv.evaluateJavascript("window.onCameraFrame && onCameraFrame('$base64Jpeg')", null)
            }
        }
    }

    /** v3.0.0 §23.1 item 5 / §23.8: feed one screen-share JPEG frame into
     *  the Live session (same realtimeInput.video entry as Awareness
     *  frames — the caller pauses Awareness frames while screen-sharing
     *  so the two sources never collide in one stream). */
    /** v3.0.0 §23.1 item 3: the finished image (data URI, or "" on
     *  failure) is pushed into the page's window.onGeneratedImage — the
     *  voice never waited for it. */
    fun onGeneratedImage(dataUri: String) {
        val wv = webView ?: return
        activity.runOnUiThread {
            runCatching {
                wv.evaluateJavascript("window.onGeneratedImage && onGeneratedImage('$dataUri')", null)
            }
        }
    }

    fun sendScreenFrame(base64Jpeg: String) {
        val wv = webView ?: return
        activity.runOnUiThread {
            runCatching {
                wv.evaluateJavascript("window.onScreenFrame && onScreenFrame('$base64Jpeg')", null)
            }
        }
    }

    fun currentWebView(): WebView? = webView

    /** Destroy the WebView — ONLY on an explicit End. Never on navigation,
     *  rotation or recomposition.
     *
     *  v2.0.0 §22.2: the 400 ms delayed destroy is GONE. That delay was the
     *  window in which stale page callbacks resurrected LiveState after
     *  end (the bridge stayed attached until removeJavascriptInterface,
     *  and a pending scheduleReconnect could still fire). The WebView is
     *  destroyed SYNCHRONOUSLY on the main thread now: the JS interface
     *  is removed first so no page JS can reach the bridge afterwards,
     *  and LiveState.onEnded() is forced after destroy completes — the
     *  authoritative final clear that nothing after it can undo (the
     *  bridge ended-latch blocks anything later). The page's own teardown
     *  is best-effort here (evaluateJavascript is async and may not run
     *  before destroy); the socket dies with the WebView and the mic is
     *  released natively, so nothing is lost. After end() returns, no
     *  page JS and no timer can reach LiveState again. */
    fun end(fromPage: Boolean = false) {
        val wv = webView ?: return
        webView = null
        isActive = false
        // Capture the bridge now: the block below is posted to the main
        // thread, and nulling the field first would skip releaseMic().
        val b = bridge
        bridge = null
        activity.runOnUiThread {
            if (!fromPage) {
                runCatching { wv.evaluateJavascript("if(window.endRaniCall) endRaniCall();", null) }
            }
            // Mic release is immediate (and must run on the main thread).
            runCatching { b?.releaseMic() }
            runCatching { wv.removeJavascriptInterface("RaniApp") }
            runCatching { wv.stopLoading() }
            runCatching { wv.loadUrl("about:blank") }
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv.destroy() }
            LiveState.onEnded()
        }
    }

    /** Activity is going away for good — release everything. */
    fun shutdown() {
        end(fromPage = true)
        activity.runOnUiThread {
            runCatching { (holder.parent as? ViewGroup)?.removeView(holder) }
        }
    }
}
