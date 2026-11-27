package com.badshah222.rani.ai

import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.badshah222.rani.ai.core.AppResolver
import com.badshah222.rani.ai.core.AwarenessMonitor
import com.badshah222.rani.ai.core.MemoryStore
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
import com.badshah222.rani.ai.data.voice.HttpLiveSession
import com.badshah222.rani.ai.data.voice.LiveSession
import com.badshah222.rani.ai.data.voice.SpeechInput
import com.badshah222.rani.ai.data.voice.TtsManager
import com.badshah222.rani.ai.data.worker.WorkerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container (keeps the project free of DI-plugin build risk). */
class Container(app: Application) {
    val store = SecureStore(app)
    val auth = AuthRepository(app.getString(R.string.default_web_client_id)) // generated from google-services.json
    val cloud = SupabaseApi { auth.supabaseToken() }
    // BADSHAH pooled API system (Cloudflare Worker): Groq chat, pooled image/video,
    // and the pooled Live key — the same system the BADSHAH web app uses.
    val worker = WorkerApi { auth.idToken() }
    // Remote config comes before the AI repo: the panel's Gemini model is the
    // fallback when the user has not picked a model yet.
    val remoteConfig = RaniRemoteConfig(store, cloud)
    // v1.9.0 §21.2 — ONE long-term memory system (on-device encrypted
    // store + best-effort Supabase sync). Created before the AI repo so
    // the chat system prompt can carry the recall block.
    val memory = MemoryStore(app, store, cloud) { auth.current?.uid }
    val ai = AiRepository(store, cloud, remoteConfig, worker, memory)
    val tts = TtsManager(app, store, cloud)
    val speech = SpeechInput(app)
    val live = LiveSession(app, store, worker)
    // v1.5.0 — the PRIMARY call engine: Gemini Live over HTTP streaming
    // (HttpLiveSession). The phone's native WebSockets are PROVEN blocked
    // (OkHttp v1.1.0–v1.2.2 AND Java-WebSocket v1.4.0 both time out at 15 s),
    // so the phone talks HTTP to the Worker and the Worker holds the Google
    // Live socket. `live` (Java-WebSocket) stays only as the Settings-test engine.
    val httpLive = HttpLiveSession(app, store, worker)
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
        // v1.9.0 §21.1 — the AppResolver's installed-app cache is
        // invalidated when an app is installed / removed / updated.
        val pkgReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { AppResolver.invalidate() }
        }
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, pkgReceiver, pkgFilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Best-effort remote config load (fail-soft: defaults apply when the table is not there yet).
        container.remoteConfig.refreshAsync(appScope)
    }
}
