package com.badshah222.rani.ai

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

/**
 * RANI AI GF — native entry point. The app is pure Jetpack Compose:
 * Auth (Supabase + Firebase) -> Home / Chat / Live voice / Settings.
 * NO WebView, NO website dependency — everything runs on-device + Gemini.
 */
class MainActivity : ComponentActivity() {
    companion object {
        /** True while this activity is in the foreground — the floating orb hides itself then. */
        @Volatile var appInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentService.start(this)
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
}
