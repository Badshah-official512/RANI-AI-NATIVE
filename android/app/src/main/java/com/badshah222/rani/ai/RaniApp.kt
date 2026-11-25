package com.badshah222.rani.ai

import android.app.Application
import android.app.NotificationManager
import com.badshah222.rani.ai.core.SecureStore
import com.badshah222.rani.ai.data.ai.AiRepository
import com.badshah222.rani.ai.data.auth.AuthRepository
import com.badshah222.rani.ai.data.ensureNotificationChannel
import com.badshah222.rani.ai.data.local.ChatStore
import com.badshah222.rani.ai.data.phone.PhoneExecutor
import com.badshah222.rani.ai.data.supabase.SupabaseApi
import com.badshah222.rani.ai.data.voice.LiveSession
import com.badshah222.rani.ai.data.voice.SpeechInput
import com.badshah222.rani.ai.data.voice.TtsManager

/** Simple manual dependency container (keeps the project free of DI-plugin build risk). */
class Container(app: Application) {
    val store = SecureStore(app)
    val auth = AuthRepository(app.getString(R.string.default_web_client_id)) // generated from google-services.json
    val cloud = SupabaseApi { auth.supabaseToken() }
    val ai = AiRepository(store, cloud)
    val tts = TtsManager(app, store, cloud)
    val speech = SpeechInput(app)
    val live = LiveSession(app, store)
    val phone = PhoneExecutor(app)
    val chats = ChatStore(app)
}

class RaniApp : Application() {
    lateinit var container: Container
    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        ensureNotificationChannel(getSystemService(NotificationManager::class.java))
        container.tts.init()
    }
}
