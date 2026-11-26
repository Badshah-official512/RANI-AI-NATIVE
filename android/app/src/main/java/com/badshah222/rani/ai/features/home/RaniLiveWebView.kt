package com.badshah222.rani.ai.features.home

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Live voice call, BADSHAH-style (v1.3.2) — INSIDE the app, like BADSHAH AI.
 *
 * BADSHAH AI's call runs in the app's own WebView (its MainActivity WebView and
 * BrowserScreen). Chrome never opens there. RANI does the same: this WebView
 * loads the RANI Live web page (badshah222-ai.pages.dev/rani-live), which opens
 * a plain `new WebSocket` to Google Live, captures the mic and plays Gemini's
 * voice. Everything else in the app stays native — only the call rides the WebView.
 *
 * v1.3.2 fix — why v1.3.0 stayed blank white: v1.3.0 created the WebView and
 * called loadUrl() inside remember() during Compose composition, BEFORE the
 * WebView was attached to the window. BADSHAH AI loads its URL only when the
 * WebView is being placed into the view hierarchy (MainActivity adds it to a
 * FrameLayout, then loadUrl; BrowserScreen does create + loadUrl inside
 * AndroidView's factory). This file now follows BADSHAH AI exactly: the WebView
 * is created, configured and loaded INSIDE AndroidView's factory. It also
 * copies BADSHAH AI's settings (database, cache, safe-browsing, cookies) and
 * gives the WebView the page's dark background so there is never a white flash.
 *
 * The page gets the Gemini key + voice + model in its URL (from the app, which
 * resolved the key the same way LiveSession does: pooled first, own key fallback).
 * The page calls back through the "RaniApp" JS bridge when the call ends.
 * v1.3.x note: the call lives in this screen — there is no background/minimize
 * for the web call (the page has no Minimize button).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RaniLiveWebView(url: String, onCallEnded: () -> Unit, onLoadError: (String) -> Unit = {}) {
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    // Keep the bridge stable across recompositions so the page's callbacks
    // always reach the same handler.
    val bridge = remember(onCallEnded) {
        object {
            @JavascriptInterface
            fun onCallEnded() { onCallEnded() }
            @JavascriptInterface
            fun onLiveConnected() { /* the page is live; nothing to do natively */ }
            @JavascriptInterface
            fun onMinimize() { onCallEnded() }
        }
    }
    // Back = end the call (same as the page's End button).
    BackHandler { onCallEnded() }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewState.value = this
                // Dark background (the page's own colour) so there is never a white flash.
                setBackgroundColor(Color.parseColor("#0B0B1A"))
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    // The greeting must play the moment the call connects — no tap first.
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = false
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // The Live page is a call screen, not a document — no zoom chrome.
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // Mic permission: the app already holds RECORD_AUDIO (VoiceScreen
                // requests it before starting the call); grant the page's request
                // for the audio resource so getUserMedia() succeeds.
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                        if (audio.isNotEmpty()) request.grant(audio) else request.deny()
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                        // Keep the RANI Live page + Google inside this WebView.
                        val host = req.url.host ?: ""
                        val internal = host.endsWith("pages.dev") || host.contains("google") ||
                            host.contains("firebase") || host.contains("gstatic") || host.contains("googleapis")
                        return !internal
                    }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame) onLoadError(err.description?.toString() ?: "Page load failed")
                    }
                }
                addJavascriptInterface(bridge, "RaniApp")
                // Load here, in the factory — when the WebView is being placed
                // into the window (BADSHAH AI's way). Loading earlier, in
                // remember() before attachment, left the page blank white.
                loadUrl(url)
            }
        },
        update = { wv ->
            // A fresh call URL after the last one ended — load the new page.
            if (wv.url != url) wv.loadUrl(url)
        },
        modifier = Modifier.fillMaxSize()
    )
    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.let { wv ->
                // Stop the Live session in the page, then tear the WebView down.
                runCatching { wv.evaluateJavascript("if(window.endRaniCall) endRaniCall();", null) }
                runCatching { wv.stopLoading() }
                runCatching { wv.loadUrl("about:blank") }
                runCatching { wv.removeJavascriptInterface("RaniApp") }
                runCatching { wv.destroy() }
            }
            webViewState.value = null
        }
    }
}
