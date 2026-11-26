package com.badshah222.rani.ai.features.home

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Live voice call, BADSHAH-style (v1.3.0).
 *
 * The native app's own WebSocket engines (OkHttp, then Java-WebSocket) could
 * not open a socket on the boss's phone — every attempt hung silently for
 * 15 s, while the browser's WebSocket works perfectly (BADSHAH AI proves it).
 * So the call runs on the browser's engine: this WebView loads the RANI Live
 * web page (badshah222-ai.pages.dev/rani-live), which opens a plain
 * `new WebSocket` to Google Live, captures the mic and plays Gemini's voice.
 * Everything else in the app stays native — only the call rides the WebView.
 *
 * The page gets the Gemini key + voice + model in its URL (from the app, which
 * resolved the key the same way LiveSession does: pooled first, own key fallback).
 * The page calls back through the "RaniApp" JS bridge when the call ends.
 * v1.3.0 note: the call lives in this screen — there is no background/minimize
 * for the web call (the page has no Minimize button).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RaniLiveWebView(url: String, onCallEnded: () -> Unit) {
    val ctx = LocalContext.current
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
    val webView = remember(url) {
        WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The greeting must play the moment the call connects — no tap first.
                mediaPlaybackRequiresUserGesture = false
                // The Live page is a call screen, not a document — no zoom/scroll chrome.
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            // Mic permission: the app already holds RECORD_AUDIO (VoiceScreen
            // requests it before starting the call); grant the page's request
            // for the audio resource so getUserMedia() succeeds.
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                    if (audio.isNotEmpty()) request.grant(audio) else request.deny()
                }
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(bridge, "RaniApp")
            loadUrl(url)
        }
    }
    // Back = end the call (same as the page's End button).
    BackHandler { onCallEnded() }
    DisposableEffect(webView) {
        onDispose {
            // Stop the Live session in the page, then tear the WebView down.
            runCatching { webView.evaluateJavascript("if(window.endRaniCall) endRaniCall();", null) }
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeJavascriptInterface("RaniApp") }
            runCatching { webView.destroy() }
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}
