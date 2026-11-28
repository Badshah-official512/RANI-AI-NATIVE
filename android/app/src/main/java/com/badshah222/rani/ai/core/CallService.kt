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
 * Live-voice keep-alive: while a Live voice session is running,
 * this microphone-type foreground service keeps the app process (and therefore
 * the Live stream, its mic capture and the audio playback) alive when the
 * user minimizes the app or switches to another app. A visible notification shows
 * the whole time; ending the Live session stops the service. Started only from a user
 * tap inside the app (micTakeover), never in the background on its own.
 * v3.0.0 §23.2 Fix 2: the notification is de-called — channel "rani_live" /
 * "RANI Live voice", neutral mic icon, title "RANI is listening". The service
 * itself stays: Android 14 requires a foreground notification for
 * FOREGROUND_SERVICE_TYPE_MICROPHONE; removing it would kill the call.
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
            nm.createNotificationChannel(NotificationChannel("rani_live", "RANI Live voice", NotificationManager.IMPORTANCE_LOW))
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, "rani_live")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("RANI is listening")
            .setContentText("Tap to return to RANI")
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
