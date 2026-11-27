package com.badshah222.rani.ai.features.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.core.FloatingOrbService
import com.badshah222.rani.ai.core.RaniLiveBridge
import java.net.URLEncoder

/**
 * v1.8.0 §20.1.2 — the hero WebView: the Live call IS the home hero.
 *
 * The SAME proven page (bundled asset rani-live.html, gemini-3.8-live only)
 * embedded in the hero via Compose AndroidView, BADSHAH factory pattern:
 * create + configure + loadUrl() ALL inside the factory — never loadUrl()
 * in remember{} or update{} (v1.3.0's blank-white failure was exactly that).
 * Background TRANSPARENT so the dot field behind it shows through; the
 * page's own UI is hidden in embed mode (?embed=1, §20.1.5) — the native
 * hero provides pill, caption, Mute/End and the emotion chip.
 *
 * Settings are copied VERBATIM from RaniLiveActivity. The bridge is the
 * shared RaniLiveBridge (§20.1.3) — the same class the Activity uses, so
 * the two paths cannot drift. Lifecycle: created only when a key is
 * available (heroLiveKey); a DisposableEffect destroys the WebView when
 * the call ends (no leaked socket, no leaked mic); a fresh call creates a
 * fresh WebView. Rotation does NOT kill the call — MainActivity has
 * configChanges (same line RaniLiveActivity already has).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeroLiveWebView(
    heroKey: String,
    voice: String,
    onWebView: (WebView?) -> Unit,
    onPageEnded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val url = remember(heroKey, voice) {
        "file:///android_asset/rani-live.html?key=" + URLEncoder.encode(heroKey, "UTF-8") +
            "&voice=" + URLEncoder.encode(voice, "UTF-8") +
            "&model=gemini-3.8-live&v=1.8.0&embed=1"
    }
    val webRef = remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        factory = { ctx ->
            var triedReload = false
            WebView(ctx).apply {
                webRef.value = this
                // Transparent — the dot field behind the WebView shows through.
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // The call page is BUNDLED inside the APK
                    // (file:///android_asset/) — file access MUST be on for
                    // it to load, and file-origin pages need these flags to
                    // open the wss WebSocket to Google.
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                // Shared bridge (§20.1.3) — same class RaniLiveActivity uses.
                addJavascriptInterface(
                    RaniLiveBridge(
                        ctx,
                        onConnected = {
                            // B-1(a): a Live call has started — the floating
                            // service starts now so the call card is ready
                            // the moment the user leaves the app mid-call.
                            if (Settings.canDrawOverlays(ctx)) {
                                runCatching { FloatingOrbService.start(ctx) }
                            }
                        },
                        onMinimizeRequested = {
                            // §20.1.3: in the hero there is no call screen
                            // to minimise — only ensure the floating overlay
                            // (dot field + call card) is up.
                            if (Settings.canDrawOverlays(ctx)) {
                                runCatching { FloatingOrbService.start(ctx) }
                            }
                        },
                        onEnded = onPageEnded,
                    ),
                    "RaniApp",
                )
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        // BADSHAH hold-and-grant: NEVER deny outright — hold
                        // the web request, ask Android, then grant/deny in
                        // onRequestPermissionsResult so the SAME call connects.
                        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        if (!wantsAudio && !wantsVideo) { request.deny(); return }
                        val missing = ArrayList<String>()
                        if (wantsAudio && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
                        if (wantsVideo && ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA)
                        if (missing.isEmpty()) { request.grant(request.resources); return }
                        val activity = ctx.findActivity()
                        if (activity == null) { request.deny(); return }
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
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, req.url))
                        return true
                    }

                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame && !triedReload) {
                            triedReload = true
                            view.loadUrl(url)
                        }
                    }
                }
                // Load here, in the factory — when the WebView is being
                // placed into the window (BADSHAH AI's way). Loading
                // earlier, in remember() before attachment, left the
                // Compose WebView blank white in v1.3.0.
                loadUrl(url)
            }
        },
        update = { wv ->
            // The update block ONLY hands the live WebView reference to the
            // HomeViewModel (for Mute/End/emotion evaluateJavascript) and
            // does nothing else — never loadUrl() here.
            onWebView(wv)
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            webRef.value?.let { wv ->
                // Stop the Live session in the page, then tear the WebView
                // down — no leaked socket, no leaked mic.
                runCatching { wv.evaluateJavascript("if(window.endRaniCall) endRaniCall();", null) }
                runCatching { wv.stopLoading() }
                runCatching { wv.loadUrl("about:blank") }
                runCatching { wv.removeJavascriptInterface("RaniApp") }
                runCatching { wv.destroy() }
            }
            webRef.value = null
            onWebView(null)
            // F3 (v1.8.0 fix): leaving Home destroys the ENGINE — the state
            // must follow it, or returning Home re-embeds with the same key
            // (a second socket + second greeting). onPageEnded is
            // stopLiveSession(): it clears heroLiveKey + LiveState, and is
            // idempotent with the normal stop path (stop already clears the
            // key). Safe as a plain callback: the hero AndroidView in
            // HomeScreen is NOT wrapped in key(heroKey) — the flow is only
            // null -> key -> null, so a dispose never fires while a NEW call
            // is starting.
            onPageEnded()
        }
    }
}

/** Unwrap a (possibly wrapped) Context to its Activity, if it has one. */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * v1.8.0 §20.1.2 — static holder for the hero WebView's pending mic
 * permission request. MainActivity.onRequestPermissionsResult delegates
 * here (same REQ code + grant-mapping as
 * RaniLiveActivity.onRequestPermissionsResult, REQ_WEB = 45).
 */
object HeroMicPermission {
    const val REQ_WEB = 45

    @Volatile var pending: PermissionRequest? = null

    fun handleResult(activity: Activity, requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != REQ_WEB) return
        val req = pending ?: return
        pending = null
        val grant = ArrayList<String>()
        for (res in req.resources) {
            val perm = when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
            if (perm != null && ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) grant.add(res)
        }
        if (grant.isNotEmpty()) req.grant(grant.toTypedArray()) else {
            req.deny()
            Toast.makeText(activity, "Microphone permission is needed for the voice call.", Toast.LENGTH_LONG).show()
        }
    }
}
