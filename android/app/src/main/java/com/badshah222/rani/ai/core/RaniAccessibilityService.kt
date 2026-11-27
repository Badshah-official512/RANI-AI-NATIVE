package com.badshah222.rani.ai.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.badshah222.rani.ai.data.phone.ContactsHelper
import java.net.URLEncoder

/**
 * v7.2 Accessibility Control (user decision 2026-11-21): user apni marzi se
 * Android Settings mein isay ON kare to RANI us ke kehne par doosri apps
 * mein type, tap, scroll, back/home kar sakta hai aur screen parh sakta hai.
 * Koi data phone se bahar nahi jata — actions sirf usi waqt chalte hain jab
 * web layer bridge command bheje (call/chat mein user ki command par).
 */
class RaniAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: RaniAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val me = ComponentName(ctx, RaniAccessibilityService::class.java)
                flat.split(':').any { ComponentName.unflattenFromString(it) == me }
            } catch (e: Exception) { false }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stonic-style Awareness: notice which app/window comes to the front.
        // Only the package name + app label are recorded, on-device only.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return // our own app is not "another app"
            val label = runCatching {
                val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
                }
                packageManager.getApplicationLabel(info).toString()
            }.getOrNull() ?: pkg
            AwarenessMonitor.onWindow(pkg, label)
        }
    }
    override fun onInterrupt() { }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun root(): AccessibilityNodeInfo? = try { rootInActiveWindow } catch (e: Exception) { null }

    fun typeText(text: String): String {
        val r = root() ?: return "fail"
        return try {
            var node = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node == null) node = findEditable(r)
            if (node == null) return "noinput"
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                "ok"
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("badshah", text))
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "ok" else "fail"
            }
        } catch (e: Exception) { "fail" }
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = findEditable(n.getChild(i))
            if (c != null) return c
        }
        return null
    }

    fun tapText(text: String): String {
        val r = root() ?: return "fail"
        return try {
            val q = text.lowercase().trim()
            if (q.isEmpty()) return "fail"
            val hits = ArrayList<AccessibilityNodeInfo>()
            collectByText(r, q, hits)
            val node = hits.firstOrNull() ?: return "notfound"
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable) {
                    return if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "ok" else "fail"
                }
                cur = cur.parent
            }
            "notfound"
        } catch (e: Exception) { "fail" }
    }

    private fun collectByText(n: AccessibilityNodeInfo?, q: String, out: ArrayList<AccessibilityNodeInfo>) {
        if (n == null) return
        val t = (n.text?.toString() ?: "").lowercase()
        val d = (n.contentDescription?.toString() ?: "").lowercase()
        if (t.contains(q) || d.contains(q)) out.add(n)
        for (i in 0 until n.childCount) collectByText(n.getChild(i), q, out)
    }

    fun scroll(dir: String): String {
        val r = root() ?: return "fail"
        return try {
            val node = findScrollable(r) ?: r
            val act = if (dir == "up" || dir == "back") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (node.performAction(act)) "ok" else "fail"
        } catch (e: Exception) { "fail" }
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = findScrollable(n.getChild(i))
            if (c != null) return c
        }
        return null
    }

    fun goBack(): String = try {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) "ok" else "fail"
    } catch (e: Exception) { "fail" }

    fun goHome(): String = try {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) "ok" else "fail"
    } catch (e: Exception) { "fail" }

    /** Is there a text field on screen right now (e.g. a chat message box)? */
    fun hasInputField(): Boolean {
        val r = root() ?: return false
        return try { findEditable(r) != null } catch (e: Exception) { false }
    }

    /**
     * Taps the Send button of a chat app (WhatsApp / SMS): the clickable node
     * whose content-description is exactly "Send". Prefers the last match in
     * traversal order (the send button sits after the message box).
     */
    fun tapSendButton(): String {
        val r = root() ?: return "fail"
        return try {
            val hits = ArrayList<AccessibilityNodeInfo>()
            collectSendButtons(r, hits)
            val node = hits.lastOrNull() ?: return "notfound"
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable) {
                    return if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "ok" else "fail"
                }
                cur = cur.parent
            }
            "notfound"
        } catch (e: Exception) { "fail" }
    }

    private fun collectSendButtons(n: AccessibilityNodeInfo?, out: ArrayList<AccessibilityNodeInfo>) {
        if (n == null) return
        val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
        val t = (n.text?.toString() ?: "").trim().lowercase()
        if (d == "send" || d == "send message" || t == "send") out.add(n)
        for (i in 0 until n.childCount) collectSendButtons(n.getChild(i), out)
    }

    fun readScreen(): String {
        val r = root() ?: return ""
        val out = ArrayList<String>()
        try { collectTexts(r, out) } catch (e: Exception) { }
        val joined = out.distinct().joinToString(" | ")
        return if (joined.length > 1500) joined.substring(0, 1500) else joined
    }

    // ---- v1.7.0 (§19.4) — app control, only when the user asks RANI to ----
    // Every method is fail-soft and returns a status string ("ok" /
    // "notfound" / "fail"). Nothing is uploaded; contacts are resolved
    // on-device only. sendWhatsApp opens the chat with the message drafted —
    // the user presses Send himself (no silent auto-send from the call page).

    /** Open an app by package name, or by display-name match as a fallback.
     *  v1.9.0 §21.1: delegates to the ONE shared AppResolver — the same
     *  ladder the bridge and the chat path use. */
    fun openApp(packageName: String): String = AppResolver.launch(this, packageName)

    /** Open YouTube with a search, in the YouTube app when installed. */
    fun searchYouTube(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return openApp("com.google.android.youtube")
        return try {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(i); return "ok" }
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(fallback)
            "ok"
        } catch (e: Exception) { "fail" }
    }

    /** Open Chrome with a Google search (the browser the user asked for). */
    fun searchChrome(query: String): String {
        val q = query.trim()
        return try {
            val url = if (q.isEmpty()) "https://www.google.com"
                else "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(i); return "ok" }
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(fallback)
            "ok"
        } catch (e: Exception) { "fail" }
    }

    /** Open WhatsApp (main app, then Business as a fallback). */
    fun openWhatsApp(): String = openApp("com.whatsapp").let {
        if (it == "ok") "ok" else openApp("com.whatsapp.w4b")
    }

    /**
     * Open the WhatsApp chat for a contact name or number with the message
     * drafted (wa.me link). The user presses Send himself — this never taps
     * Send on its own. Contacts stay on this phone, never uploaded.
     */
    fun sendWhatsApp(contactOrNumber: String, message: String): String {
        val target = contactOrNumber.trim()
        if (target.isEmpty()) return "fail"
        return try {
            val digitsOnly = target.matches(Regex("^[+]?[0-9][0-9 \\-]{5,}$"))
            val number = if (digitsOnly) target.replace(" ", "").replace("-", "")
            else {
                val entry = runCatching { ContactsHelper.findBest(this, target) }.getOrNull()
                    ?: return "notfound"
                runCatching { ContactsHelper.bestNumber(entry) }.getOrNull() ?: return "notfound"
            }
            val e164 = runCatching { ContactsHelper.toE164(this, number) }.getOrNull() ?: number
            var url = "https://wa.me/" + e164.removePrefix("+")
            if (message.isNotBlank()) url += "?text=" + URLEncoder.encode(message, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            "ok"
        } catch (e: Exception) { "fail" }
    }

    private fun collectTexts(n: AccessibilityNodeInfo?, out: ArrayList<String>) {
        if (n == null) return
        if (n.isVisibleToUser) {
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t) else if (!d.isNullOrEmpty()) out.add(d)
        }
        for (i in 0 until n.childCount) collectTexts(n.getChild(i), out)
    }
}
