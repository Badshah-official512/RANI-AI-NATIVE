package com.badshah222.rani.ai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.badshah222.rani.ai.MainActivity
import com.badshah222.rani.ai.R
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * RANI background agent — keeps the assistant "alive" while the app is
 * closed: every 15 minutes it checks the backend (Supabase) for new admin
 * announcements and notifies the user. Silent on any network error.
 */
class AgentService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Agent active hai — naye updates par nazar rakhi hui hai."))
        if (!running) {
            running = true
            schedule()
        }
        return START_STICKY
    }

    private fun schedule() {
        handler.postDelayed({
            if (!running) return@postDelayed
            checkAnnouncements()
            schedule()
        }, CHECK_MS)
    }

    private fun checkAnnouncements() {
        thread {
            try {
                val url = URL(
                    "https://aghlacmqlagmigoznxlm.supabase.co/rest/v1/announcements" +
                        "?select=id,title,body,created_at&active=eq.true&order=created_at.desc&limit=1"
                )
                val c = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("apikey", ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $ANON_KEY")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (c.responseCode == 200) {
                    val arr = JSONArray(c.inputStream.bufferedReader().readText())
                    if (arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        val id = o.optString("id")
                        val prefs = getSharedPreferences("badshah_agent", Context.MODE_PRIVATE)
                        if (prefs.getString("last_ann", null) != id) {
                            prefs.edit().putString("last_ann", id).apply()
                            notifyUser(
                                o.optString("title", "RANI AI"),
                                o.optString("body", "Naya announcement aaya hai.")
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // The agent stays silent on network errors.
            }
        }
    }

    private fun notifyUser(title: String, body: String) {
        val pi = PendingIntent.getActivity(
            this, 7, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RANI AI Agent")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "RANI Agent", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "badshah_agent"
        private const val NOTIF_ID = 77
        private const val CHECK_MS = 15 * 60 * 1000L
        private const val ANON_KEY = "sb_publishable_cxY5POeM8kbv0CyJ4LAJ_Q_ao8vaFKn"

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, AgentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                // Some OEMs restrict foreground-service starts; the app itself still works.
            }
        }
    }
}
