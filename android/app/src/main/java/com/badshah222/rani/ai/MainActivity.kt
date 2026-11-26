package com.badshah222.rani.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.badshah222.rani.ai.core.AgentService
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
}
