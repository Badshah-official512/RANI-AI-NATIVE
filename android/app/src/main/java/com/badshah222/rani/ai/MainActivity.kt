package com.badshah222.rani.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.badshah222.rani.ai.core.AgentService
import com.badshah222.rani.ai.ui.AppNav
import com.badshah222.rani.ai.ui.theme.RaniTheme

/**
 * RANI AI GF — native entry point. The app is pure Jetpack Compose:
 * Auth (Supabase + Firebase) -> Home / Chat / Live voice / Settings.
 * NO WebView, NO website dependency — everything runs on-device + Gemini.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentService.start(this)
        setContent {
            RaniTheme {
                AppNav(this)
            }
        }
    }
}
