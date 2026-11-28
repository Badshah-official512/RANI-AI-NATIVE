package com.badshah222.rani.ai.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.badshah222.rani.ai.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * v3.0.0 §23.6 — ProactiveEngine: she acts herself when the phone needs
 * something, on her own initiative. Bounded by the boss's own sentence:
 * safe/permitted actions only, on her own; sensitive operations need his
 * confirmation first.
 *
 * Two lists — a new action cannot be added to SAFE_ALONE without a spec
 * line saying so; dev does not improvise the list.
 *   SAFE_ALONE:    clear RANI's own cache, set a reminder, lower brightness
 *                  at night (only with the Modify-settings grant, and only
 *                  21:00-06:00).
 *   NEEDS_CONFIRM: everything else — anything that deletes, sends, pays,
 *                  shares, changes a password, or contacts another person.
 *
 * HONEST LIMIT (Android-restricted, §23.12): turning Battery Saver ON
 * needs WRITE_SECURE_SETTINGS (signature permission) — a normal app cannot
 * do it. So battery-saver is NEVER done alone: she suggests it and opens
 * the battery-saver page. The item says so plainly.
 *
 * Checks run 6-hourly (AlarmManager) + on cold start (HomeViewModel).
 * At most ONE proactive item per day per type, max 3 notifications/day.
 * Every action she takes herself is announced in the next conversation
 * (recall block, §23.7) + listed in Settings → Proactive with undo where
 * reversible. Master switch OFF stops all of it.
 */
class ProactiveEngine(private val ctx: Context, private val store: SecureStore) {
    private val appCtx = ctx.applicationContext

    companion object {
        val SAFE_ALONE = listOf("clear_own_cache", "set_reminder", "lower_brightness_night")
        val NEEDS_CONFIRM = listOf(
            "delete_file", "send_message", "make_payment", "share_data",
            "change_password", "contact_person", "battery_saver_on", "factory_reset",
        )
        fun needsConfirm(action: String): Boolean = action !in SAFE_ALONE

        private const val REQ_PROACTIVE = 9101
        fun schedule(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    ctx, REQ_PROACTIVE, Intent(ctx, ProactiveReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 6 * 60 * 60 * 1000L,
                    6 * 60 * 60 * 1000L, pi,
                )
            } catch (e: Exception) { }
        }
    }

    private fun history(): JSONArray =
        runCatching { JSONArray(store.proactiveHistoryJson) }.getOrNull() ?: JSONArray()

    private fun saveHistory(arr: JSONArray) {
        while (arr.length() > 20) arr.remove(0)
        store.proactiveHistoryJson = arr.toString()
    }

    private fun record(action: String, text: String, prev: Int? = null) {
        val arr = history()
        val o = JSONObject().put("at", System.currentTimeMillis()).put("action", action).put("text", text)
        if (prev != null) o.put("prev", prev)
        arr.put(o)
        saveHistory(arr)
    }

    private fun alreadyToday(type: String): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date())
        val arr = history()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date(o.optLong("at")))
            if (at == today && o.optString("action") == type) return true
        }
        return false
    }

    private fun notifCountToday(): Int {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date())
        var n = 0
        val arr = history()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).format(java.util.Date(o.optLong("at")))
            if (at == today) n++
        }
        return n
    }

    fun historyItems(): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        val arr = history()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Triple(o.optLong("at"), o.optString("action"), o.optString("text")))
        }
        return out.reversed()
    }

    /** Undo a reversible proactive action (brightness only — cache clearing
     *  cannot be undone). Returns true when something was undone. */
    fun undo(indexFromLatest: Int): Boolean {
        val items = historyItems()
        if (indexFromLatest !in items.indices) return false
        val arr = history()
        val o = arr.optJSONObject(arr.length() - 1 - indexFromLatest) ?: return false
        if (o.optString("action") != "lower_brightness_night") return false
        if (!Settings.System.canWrite(appCtx)) return false
        return try {
            Settings.System.putInt(appCtx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, o.optInt("prev", 128))
            record("undo_brightness", "Brightness restored to how it was.")
            true
        } catch (e: Exception) { false }
    }

    /** One check pass. Returns the items (max 3) for the recall block. */
    fun checkNow(): List<String> {
        if (!store.proactiveEnabled) return emptyList()
        val items = ArrayList<String>()
        // (a) Battery < 20% and not charging — suggest only (restricted).
        if (store.proactiveBattery && !alreadyToday("battery_low")) {
            try {
                val bm = appCtx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct in 1..19 && !bm.isCharging) {
                    items.add("Your battery is at $pct%. Shall I open battery saver for you?")
                    record("battery_low", "Battery was at $pct% — suggested battery saver (opening it needs your tap).")
                }
            } catch (e: Exception) { }
        }
        // (b) Storage < 1 GB free — clear RANI's own cache (safe, reversible-ish).
        if (store.proactiveStorage && !alreadyToday("clear_own_cache")) {
            try {
                val stat = StatFs(appCtx.filesDir.path)
                val freeBytes = stat.availableBytes
                if (freeBytes < 1024L * 1024L * 1024L) {
                    var cleared = 0L
                    fun clearDir(dir: java.io.File?) {
                        dir?.listFiles()?.forEach { f ->
                            if (f.isDirectory) clearDir(f) else { cleared += f.length(); f.delete() }
                        }
                    }
                    clearDir(appCtx.cacheDir)
                    items.add("Storage is low — I cleared RANI's own cache to free some space.")
                    record("clear_own_cache", "Storage was low — cleared RANI's own cache (${cleared / 1024} KB).")
                }
            } catch (e: Exception) { }
        }
        // (c) A reminder is due within 1 hour.
        if (store.proactiveReminders && !alreadyToday("reminder_due")) {
            try {
                val arr = runCatching { JSONArray(store.tasksJson) }.getOrNull() ?: JSONArray()
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("status") != "pending") continue
                    val due = o.optLong("dueEpoch", 0L).takeIf { it > 0 }
                        ?: ReminderTime.parseDue(o.optString("due"), now)
                    if (due != null && due in now..now + 60 * 60 * 1000L) {
                        items.add("Reminder coming up: ${o.optString("title")}.")
                        record("reminder_due", "Reminded about '${o.optString("title")}' (due soon).")
                        break
                    }
                }
            } catch (e: Exception) { }
        }
        // (f) A special day is tomorrow.
        if (store.proactiveSpecialDays && !alreadyToday("special_day")) {
            try {
                val arr = runCatching { JSONArray(store.specialDaysJson) }.getOrNull() ?: JSONArray()
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("month") == m && o.optInt("day") == d) {
                        items.add("Tomorrow is ${o.optString("name")} — shall I help you get ready?")
                        record("special_day", "Tomorrow is ${o.optString("name")}.")
                        break
                    }
                }
            } catch (e: Exception) { }
        }
        // (g) Night brightness: 21:00-06:00, only with the grant.
        if (!alreadyToday("lower_brightness_night")) {
            try {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if ((hour >= 21 || hour < 6) && Settings.System.canWrite(appCtx)) {
                    val cur = Settings.System.getInt(appCtx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                    if (cur > 90) {
                        Settings.System.putInt(appCtx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 70)
                        items.add("It is late — I dimmed the screen a little so it is easier on your eyes.")
                        record("lower_brightness_night", "Dimmed the screen for the night.", prev = cur)
                    }
                }
            } catch (e: Exception) { }
        }
        val kept = items.take(3)
        store.proactiveItemsJson = JSONArray().apply { kept.forEach { put(it) } }.toString()
        if (kept.isNotEmpty() && notifCountToday() < 3) {
            notify("RANI noticed something", kept.first())
        }
        return kept
    }

    private fun notify(title: String, text: String) {
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("rani_proactive", "RANI proactive", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                appCtx, 0, Intent(appCtx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(appCtx, "rani_proactive")
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(9101, notif)
        } catch (e: Exception) { }
    }
}

/** Parses reminder due-text ("at 5 pm", "tomorrow morning", "in 30 minutes")
 *  into epoch millis. Returns null when it cannot be understood — the
 *  reminder then stays a saved list item with no firing time. */
object ReminderTime {
    fun parseDue(raw: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        Regex("in (\\d+)\\s*(?:min|mins|minute|minutes)").find(s)?.let {
            return nowMs + (it.groupValues[1].toLongOrNull() ?: return null) * 60_000L
        }
        Regex("in (\\d+)\\s*(?:h|hour|hours)").find(s)?.let {
            return nowMs + (it.groupValues[1].toLongOrNull() ?: return null) * 3_600_000L
        }
        var dayOffset = 0
        if (s.contains("tomorrow")) dayOffset = 1
        if (s.contains("tonight") || s.contains("tonite")) {
            cal.set(Calendar.HOUR_OF_DAY, 20); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            if (dayOffset == 0 && cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        if (s.contains("morning")) {
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(Calendar.HOUR_OF_DAY, 8); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(s) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (dayOffset == 0 && cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}

/** v3.0.0 §23.1 item 21: reminders that FIRE. Schedules an exact alarm;
 *  ReminderReceiver posts the notification at the due time and marks the
 *  task done. SCHEDULE_EXACT_ALARM is declared in the manifest. */
object ReminderScheduler {
    fun schedule(ctx: Context, id: String, epochMs: Long, title: String) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(ctx, ReminderReceiver::class.java)
                .putExtra("id", id).putExtra("title", title)
            val pi = PendingIntent.getBroadcast(
                ctx, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pi)
            }
        } catch (e: Exception) { }
    }

    fun cancel(ctx: Context, id: String) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, id.hashCode(), Intent(ctx, ReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(pi)
        } catch (e: Exception) { }
    }
}

class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ProactiveEngine(ctx, SecureStore(ctx)).checkNow() }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val id = intent?.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: "Reminder"
        try {
            val store = SecureStore(ctx)
            val arr = runCatching { JSONArray(store.tasksJson) }.getOrNull() ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == id) o.put("status", "done")
            }
            store.tasksJson = arr.toString()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("rani_reminders", "RANI reminders", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(ctx, "rani_reminders")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Reminder").setContentText(title)
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(id.hashCode(), notif)
        } catch (e: Exception) { }
    }
}
