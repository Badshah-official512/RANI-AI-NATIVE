package com.badshah222.rani.ai

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.badshah222.rani.ai.core.AgentService
import com.badshah222.rani.ai.core.FloatingOrbService
import com.badshah222.rani.ai.features.home.HeroMicPermission
import com.badshah222.rani.ai.ui.AppNav
import com.badshah222.rani.ai.ui.theme.RaniTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * RANI AI GF — native entry point. The app is pure Jetpack Compose:
 * Auth (Supabase + Firebase) -> Home / Chat / Live voice / Settings.
 * NO WebView, NO website dependency — everything runs on-device + Gemini.
 */
class MainActivity : ComponentActivity() {
    companion object {
        /** True while this activity is in the foreground — the floating orb hides itself then. */
        @Volatile var appInForeground = false
        /** Set when the floating orb (or another entry) asks to open straight into
         *  the voice call — the call comes up on top of the screen, BADSHAH-style.
         *  A StateFlow so Compose navigates the moment it is set (onCreate AND
         *  onNewIntent — the orb uses SINGLE_TOP, so a tap while the app is
         *  already running arrives as onNewIntent, not a fresh onCreate). */
        val wakeVoiceFlow = MutableStateFlow(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentService.start(this)
        // v1.7.0 B-1(b) cold start: Live Voice ON + overlay granted → the
        // floating service starts now, so the avatar appears when the app is
        // closed/minimised with no call, and the call card appears when a
        // call is minimised (§19.3: app-closed + Live Voice ON + overlay
        // granted). The Floating toggle is NOT a condition here — with
        // defaults (Floating OFF, Live Voice ON) the avatar still appears.
        // While the app is in front the service hides both surfaces itself.
        val store = (application as RaniApp).container.store
        if (store.autoVoiceEnabled && Settings.canDrawOverlays(this)) {
            runCatching { FloatingOrbService.start(this) }
        }
        wakeVoiceFlow.value = intent?.getStringExtra("EXTRA_WAKE") == "1"
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appInForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                appInForeground = false
            }
        })
        setContent {
            RaniTheme {
                AppNav(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wakeVoiceFlow.value = intent.getStringExtra("EXTRA_WAKE") == "1"
    }

    /** v1.8.0 §20.1.2: the hero WebView lives in this Activity now (not in
     *  RaniLiveActivity), so its hold-and-grant mic permission result
     *  arrives here — delegate it to the HeroLiveWebView holder (same
     *  REQ code + grant-mapping as RaniLiveActivity, REQ_WEB = 45). */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        HeroMicPermission.handleResult(this, requestCode, permissions, grantResults)
    }
}
