package com.badshah222.rani.ai.core

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.badshah222.rani.ai.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Screen sharing, the honest Android way: the user taps Start, Android shows its
 * own consent dialog (MediaProjection), and only then frames are captured into
 * [frame] for the vision model. A persistent notification is visible the whole
 * time, and Stop (button, notification, or the system) ends capture instantly.
 * Nothing is captured or uploaded without that consent, ever.
 */
class ScreenShareService : Service() {
    companion object {
        val frame = MutableStateFlow<Bitmap?>(null)
        val running = MutableStateFlow(false)
        @Volatile private var lastPost = 0L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenShareService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreenShareService::class.java)) }

        /** v3.0.0 §23.8: the never-watch list is ENFORCED — if the app in
         *  front right now is on the list, screen watch must not start.
         *  Returns the app label when blocked, null when it may start. */
        fun blockedApp(ctx: Context): String? {
            val never = runCatching {
                org.json.JSONArray(SecureStore(ctx).screenWatchNeverJson)
            }.getOrNull() ?: return null
            val current = AwarenessMonitor.current.value ?: return null
            for (i in 0 until never.length()) {
                if (never.optString(i) == current.packageName) return current.appLabel
            }
            return null
        }
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        projection = proj
        // Android 14+ requires a registered callback before createVirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        val metrics = resources.displayMetrics
        val w = minOf(metrics.widthPixels, 1080)
        val h = (metrics.heightPixels.toLong() * w / metrics.widthPixels).toInt().coerceAtLeast(2)
        thread = HandlerThread("screen-share").apply { start() }
        val handler = Handler(thread!!.looper)

        val r = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val now = System.currentTimeMillis()
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (now - lastPost >= 1200) {
                    imageToBitmap(image, w, h)?.let { bmp ->
                        lastPost = now
                        frame.value = bmp
                        // v3.0.0 §23.1 item 5 (dev-finish): feed the frame
                        // into the Live session too — this was missing
                        // (CallEngine.sendScreenFrame had zero callers, so
                        // screen frames never reached RANI in a call).
                        // Small JPEG (~640 wide, quality 70, same as the
                        // Awareness camera frames), only while a call is
                        // live. The camera feed pauses while sharing (the
                        // startVision callback skips frames then), so the
                        // two sources never collide in one video stream.
                        if (LiveState.active.value) {
                            runCatching {
                                val small = if (bmp.width > 640) Bitmap.createScaledBitmap(bmp, 640, (bmp.height.toLong() * 640 / bmp.width).toInt().coerceAtLeast(2), true) else bmp
                                val out = java.io.ByteArrayOutputStream()
                                if (small.compress(Bitmap.CompressFormat.JPEG, 70, out)) {
                                    CallEngineHolder.engine?.sendScreenFrame(android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
                                }
                                if (small !== bmp) small.recycle()
                            }
                        }
                    }
                }
            } catch (e: Exception) { /* a dropped frame is fine */ } finally { image.close() }
        }, handler)

        display = proj.createVirtualDisplay(
            "badshah-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        running.value = true
        return START_STICKY
    }

    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("screen_share", "Screen share", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, "screen_share")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("RANI is watching your screen")
            .setContentText("Tap here to return to RANI and stop watching instantly.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(43, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(43, notif)
        }
    }

    override fun onDestroy() {
        running.value = false
        frame.value = null
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        runCatching { thread?.quitSafely() }; thread = null
        super.onDestroy()
    }
}
