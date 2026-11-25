package com.badshah222.rani.ai.features.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.badshah222.rani.ai.MainActivity
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.ScreenShareService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * MYRA Screen Guardian (myra_guardian.py) — watches the screen the user is
 * ALREADY sharing (Screen Share ON) and warns by voice when something
 * sensitive appears (password / bank / OTP / card). Rules from MYRA:
 * - screenshots only when the screen CHANGES (change detection)
 * - never repeat a password / card / OTP out loud
 * - duplicate suppression: the same warning is not repeated for 5 minutes
 * - interval pref default 45s (clamp 15–300)
 *
 * HONEST LIMIT: without PACKAGE_USAGE_STATS (explicitly NOT requested, spec §5)
 * the guardian cannot know which app/window is open — so it only analyses
 * frames the user explicitly shares via Screen Share, never silently.
 */
class ScreenGuardianService : Service() {
    private var job: Job? = null
    private var lastHash: String? = null
    private var lastWarningAt = 0L
    private var lastWarningText = ""

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, ScreenGuardianService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreenGuardianService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("guardian", "Screen Guardian", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, "guardian")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("RANI AI — Screen Guardian ON hai")
            .setContentText("Watching only your shared screen — warns about password / bank / OTP.")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(44, notif)
        val app = application as RaniApp
        val scope = CoroutineScope(Dispatchers.Default + Job())
        job = scope.launch {
            while (true) {
                val interval = app.container.store.guardianInterval.coerceIn(15, 300) * 1000L
                delay(interval)
                if (!ScreenShareService.running.value) continue
                val bmp = ScreenShareService.frame.value ?: continue
                val hash = frameHash(bmp)
                if (hash == lastHash) continue // screen did not change — MYRA rule
                lastHash = hash
                runCatching { checkFrame(app, bmp) }
            }
        }
    }

    /** Downscaled average hash — cheap change detection (MYRA: only when screen changes). */
    private fun frameHash(bmp: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (small !== bmp) small.recycle()
        val digest = MessageDigest.getInstance("MD5")
        pixels.forEach { p -> digest.update((p shr 16).toByte()); digest.update((p shr 8).toByte()); digest.update(p.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun checkFrame(app: RaniApp, bmp: Bitmap) {
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
        val seen = app.container.ai.describeImage(
            bytes,
            "Is screen par koi sensitive cheez dikh rahi hai? Jaise password field, bank/netbanking, OTP/verification code, credit/debit card, wallet, ya incognito/private browsing? " +
            "Sirf YES ya NO likho, phir ek chhoti line mein kya dikha. Password, card number ya OTP KABHI mat likhna.",
        )
        val lower = seen.lowercase()
        val sensitive = lower.startsWith("yes") && listOf("password", "bank", "otp", "verification", "card", "wallet", "incognito", "private").any { lower.contains(it) }
        if (!sensitive) return
        val now = System.currentTimeMillis()
        if (seen == lastWarningText && now - lastWarningAt < 5 * 60 * 1000L) return // duplicate suppression
        lastWarningText = seen; lastWarningAt = now
        runCatching { app.container.cloud.recordActivity(app.container.auth.current?.uid, "safety_alert", mapOf("reason" to "screen_sensitive")) }
        val warn = "Babu Jaan, screen par sensitive cheez dikh rahi hai — main usay parh kar nahi sunaungi. Agar password ya bank ka kaam hai to pehle screen share band kar lo."
        app.container.tts.speak(warn)
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "guardian")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("RANI AI — Screen Guardian warning")
            .setContentText(warn).setContentIntent(open).setAutoCancel(true).build()
        nm.notify(45, n)
    }

    override fun onDestroy() { job?.cancel(); job = null; super.onDestroy() }
}
