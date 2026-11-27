package com.badshah222.rani.ai.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * v1.9.0 §21.1 — ONE shared app-name resolver. Every open path (the Live
 * bridge dispatcher, the direct bridge openApp(), the accessibility
 * service, the chat path) resolves through here, so the paths cannot drift
 * again. Pure Kotlin, no UI.
 *
 * Resolution ladder, first hit wins:
 *  1. exact package name (getLaunchIntentForPackage)
 *  2. exact label (case-insensitive)
 *  3. normalised label (lowercase, spaces/punctuation/underscores stripped)
 *  4. launcher query (queryIntentActivities MAIN + LAUNCHER — catches apps
 *     for which getLaunchIntentForPackage returns null)
 *  5. fuzzy: token containment + edit distance <= 2 on the normalised label
 *  6. well-known package table (only when 1-5 miss)
 *  7. ambiguous: two or more candidates within a hair -> "multiple:…"
 *
 * Performance (boss's phone-load rule): the installed-app label list is
 * read ONCE per process and cached; it is invalidated on package
 * add/remove (RaniApp registers the broadcast).
 */
object AppResolver {

    sealed class Result {
        data class Hit(val packageName: String, val label: String) : Result()
        object NotFound : Result()
        data class Multiple(val names: List<String>) : Result()
    }

    private data class Entry(val label: String, val packageName: String, val launchIntent: Intent?)

    /** Well-known packages — fallback ONLY, never the first step. */
    private val WELL_KNOWN = mapOf(
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "whatsapp" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "settings" to "com.android.settings",
        "play store" to "com.android.vending",
        "facebook" to "com.facebook.katana",
        "instagram" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "messenger" to "com.facebook.orca",
        "spotify" to "com.spotify.music",
    )

    @Volatile
    private var cached: List<Entry>? = null

    fun invalidate() {
        cached = null
    }

    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s\\p{Punct}_]+"), "")

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun loadEntries(ctx: Context): List<Entry> {
        cached?.let { return it }
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, Entry>()
        // Launcher query first — it only lists launchable apps.
        runCatching {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val infos = pm.queryIntentActivities(launcher, 0)
            for (ri in infos) {
                val pkg = ri.activityInfo.packageName
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: pkg
                val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                    ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pkg, ri.activityInfo.name)
                out[pkg] = Entry(label, pkg, intent)
            }
        }
        // Installed apps fill in labels for anything the launcher query missed.
        runCatching {
            for (app in pm.getInstalledApplications(0)) {
                if (out.containsKey(app.packageName)) continue
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue
                val intent = runCatching { pm.getLaunchIntentForPackage(app.packageName) }.getOrNull()
                out[app.packageName] = Entry(label, app.packageName, intent)
            }
        }
        val list = out.values.toList()
        cached = list
        return list
    }

    fun resolve(ctx: Context, query: String): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result.NotFound
        val pm = ctx.packageManager
        // 1. Exact package name.
        runCatching { pm.getLaunchIntentForPackage(q) }.getOrNull()?.let {
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(q, 0)).toString()
            }.getOrNull() ?: q
            return Result.Hit(q, label)
        }
        val entries = loadEntries(ctx)
        val qLower = q.lowercase()
        val qNorm = normalize(q)
        // 2. Exact label.
        entries.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let {
            return Result.Hit(it.packageName, it.label)
        }
        // 3. Normalised label.
        entries.firstOrNull { normalize(it.label) == qNorm }?.let {
            return Result.Hit(it.packageName, it.label)
        }
        // 4 + 5. Launcher list + fuzzy scoring.
        val qTokens = qLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Entry, Int>>()
        for (e in entries) {
            val labelLower = e.label.lowercase()
            val labelNorm = normalize(e.label)
            val score = when {
                labelLower.startsWith(qLower) -> 90
                qTokens.isNotEmpty() && qTokens.all { labelLower.contains(it) } -> 80
                labelLower.contains(qLower) -> 75
                else -> {
                    val d = editDistance(qNorm, labelNorm)
                    val wordBest = labelLower.split(Regex("\\s+")).minOfOrNull { editDistance(qNorm, normalize(it)) } ?: 99
                    val best = minOf(d, wordBest)
                    if (best <= 2) 70 - best else -1
                }
            }
            if (score >= 0) scored.add(e to score)
        }
        scored.sortByDescending { it.second }
        if (scored.isNotEmpty()) {
            val top = scored[0]
            // 7. Ambiguous: two or more candidates within 2 points of the top.
            val close = scored.filter { it.second >= top.second - 2 }.map { it.first.label }.distinct()
            if (close.size > 1 && top.second < 100) {
                return Result.Multiple(close.take(3))
            }
            return Result.Hit(top.first.packageName, top.first.label)
        }
        // 6. Well-known package table.
        val known = WELL_KNOWN[qLower] ?: WELL_KNOWN[qNorm]
        if (known != null) {
            val intent = runCatching { pm.getLaunchIntentForPackage(known) }.getOrNull()
            if (intent != null) {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(known, 0)).toString()
                }.getOrNull() ?: known
                return Result.Hit(known, label)
            }
        }
        return Result.NotFound
    }

    /**
     * Resolve + launch. Returns the bridge status strings: "ok" /
     * "notfound" / "fail" / "multiple:Name1, Name2". Opening an app needs
     * no accessibility API — this never returns "noaccessibility".
     */
    fun launch(ctx: Context, query: String): String {
        return try {
            when (val r = resolve(ctx, query)) {
                is Result.Hit -> {
                    val pm = ctx.packageManager
                    val intent = runCatching { pm.getLaunchIntentForPackage(r.packageName) }.getOrNull()
                        ?: loadEntries(ctx).firstOrNull { it.packageName == r.packageName }?.launchIntent
                        ?: return "notfound"
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "ok"
                }
                is Result.Multiple -> "multiple:" + r.names.joinToString(", ")
                Result.NotFound -> "notfound"
            }
        } catch (e: Exception) {
            "fail"
        }
    }
}
