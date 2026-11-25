package com.badshah222.rani.ai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.badshah222.rani.ai.MainActivity

/**
 * Call keep-alive, the way real VoIP apps do it: while a voice call is running,
 * this microphone-type foreground service keeps the app process (and therefore
 * the WebView, its mic capture and the Gemini Live socket) alive when the user
 * minimizes the app or switches to another app. A visible notification shows
 * the whole time; ending the call stops the service. Started only from a user
 * tap inside the app (micTakeover), never in the background on its own.
 */
class CallService : Service() {
    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, CallService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CallService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("bh_call", "Voice call", NotificationManager.IMPORTANCE_LOW))
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, "bh_call")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("RANI AI — call chal rahi hai")
            .setContentText("App minimize karne par bhi baat jaari rahegi — wapas aane ke liye tap karo")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(44, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(44, notif)
        }
        return START_STICKY
    }
}
