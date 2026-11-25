package com.badshah222.rani.ai

import android.app.Application
import android.app.NotificationManager
import com.badshah222.rani.ai.core.AwarenessMonitor
import com.badshah222.rani.ai.core.RaniRemoteConfig
import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.data.ai.AiRepository
import com.badshah222.rani.ai.data.auth.AuthRepository
import com.badshah222.rani.ai.data.ensureNotificationChannel
import com.badshah222.rani.ai.data.identity.FacePrint
import com.badshah222.rani.ai.data.identity.VoicePrint
import com.badshah222.rani.ai.data.local.ChatStore
import com.badshah222.rani.ai.data.phone.PhoneExecutor
import com.badshah222.rani.ai.data.supabase.SupabaseApi
import com.badshah222.rani.ai.data.voice.LiveSession
import com.badshah222.rani.ai.data.voice.SpeechInput
import com.badshah222.rani.ai.data.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container (keeps the project free of DI-plugin build risk). */
class Container(app: Application) {
    val store = SecureStore(app)
    val auth = AuthRepository(app.getString(R.string.default_web_client_id)) // generated from google-services.json
    val cloud = SupabaseApi { auth.supabaseToken() }
    // Remote config comes before the AI repo: the panel's Gemini model is the
    // fallback when the user has not picked a model yet.
    val remoteConfig = RaniRemoteConfig(store, cloud)
    val ai = AiRepository(store, cloud, remoteConfig)
    val tts = TtsManager(app, store, cloud)
    val speech = SpeechInput(app)
    val live = LiveSession(app, store)
    val phone = PhoneExecutor(app)
    val chats = ChatStore(app)
    // Stonic extras: Awareness, identity (voice-print + face-print).
    val voicePrint = VoicePrint(store)
    val facePrint = FacePrint(store)
    val awareness = AwarenessMonitor
}

class RaniApp : Application() {
    lateinit var container: Container
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        ensureNotificationChannel(getSystemService(NotificationManager::class.java))
        container.tts.init()
        // Awareness follows the on-device toggle; the admin panel can override it via remote config.
        AwarenessMonitor.enabled = container.store.awarenessEnabled
        // Best-effort remote config load (fail-soft: defaults apply when the table is not there yet).
        container.remoteConfig.refreshAsync(appScope)
    }
}
