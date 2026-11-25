package com.badshah222.rani.ai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.badshah222.rani.ai.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recordings (a RANI extra beyond Stonic — Stonic only takes
 * screenshots). What RANI sees, she can save as a screen record:
 * MediaProjection + MediaRecorder, saved as MP4 in the app's own Movies
 * folder. Recordings stay on this phone only — they are never uploaded,
 * never sent anywhere. The user views and deletes them on the Recordings
 * screen. A persistent notification is visible the whole time, and Android's
 * own MediaProjection consent dialog is shown before any recording starts.
 */
object ScreenRecordings {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RANI").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension.lowercase() == "mp4" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun delete(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    fun fileName(): String =
        "rani_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
}

class ScreenRecordService : Service() {
    companion object {
        val recording = MutableStateFlow(false)
        val currentFile = MutableStateFlow<File?>(null)
        val startedAt = MutableStateFlow(0L)

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenRecordService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenRecordService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        val code = intent?.getIntExtra("code", android.app.Activity.RESULT_CANCELED)
            ?: android.app.Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        if (code != android.app.Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        val metrics = resources.displayMetrics
        val w = minOf(metrics.widthPixels, 1080)
        val h = (metrics.heightPixels.toLong() * w / metrics.widthPixels).toInt().coerceAtLeast(2)
        file = File(ScreenRecordings.dir(this), ScreenRecordings.fileName())
        val rec: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
        recorder = rec
        try {
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(5_000_000)
            rec.setOutputFile(file!!.absolutePath)
            rec.prepare()
        } catch (e: Exception) {
            runCatching { file?.delete() }
            stopSelf()
            return START_NOT_STICKY
        }
        display = proj.createVirtualDisplay(
            "rani-record", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
        )
        try {
            rec.start()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        recording.value = true
        currentFile.value = file
        startedAt.value = System.currentTimeMillis()
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("screen_record", "Screen recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, "screen_record")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("RANI AI — screen recording")
            .setContentText("Recording is saved on this phone only. Stop it from the app.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(44, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(44, notif)
        }
    }

    override fun onDestroy() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        file = null
        recording.value = false
        currentFile.value = null
        super.onDestroy()
    }
}
