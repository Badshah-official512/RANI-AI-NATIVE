package com.badshah222.rani.ai.domain

/**
 * Turns a spoken/typed command into a [PhoneAction], or null when it is an ordinary
 * question for the AI. Understands English plus common Roman-Urdu/Hindi phrasing
 * ("youtube kholo", "ali ko call karo", "5 minute ka timer").
 */
object CommandParser {
    private val OPEN_VERBS = "open|launch|start|run|kholo|khol do|khol|chalao|chala do"
    private val digitsOnly = Regex("^[+]?[0-9][0-9 \\-]{5,}$")

    fun parse(raw: String): PhoneAction? {
        val t = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        if (t.isEmpty()) return null
        val l = t.lowercase()

        // ---- timer / alarm (checked first: they contain numbers) ----
        parseTimer(l)?.let { return it }
        parseAlarm(l)?.let { return it }

        // ---- flashlight / torch ----
        if (Regex("\\b(flash ?light|torch)\\b").containsMatchIn(l)) {
            val off = Regex("\\b(off|band|close|stop|bujha)\\b").containsMatchIn(l)
            return PhoneAction.Flashlight(!off)
        }

        // ---- messages: type + send (spec §14.1 — "message likho aur bhejo") ----
        // Auto-send: RANI types the message and taps Send (accessibility).
        // Plain "bhejo" stays a draft — the composer opens and YOU press send.
        Regex("^(.+?) ko (?:whatsapp|whats app) (?:par )?(?:message )?likh(?:o| kar) (?:aur |or )?bhej(?:o| do)(?:[: ])?(.*)$").find(l)?.let {
            return PhoneAction.AutoSendMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]), "whatsapp")
        }
        Regex("^(.+?) ko (?:sms|message|msg) likh(?:o| kar) (?:aur |or )?bhej(?:o| do)(?:[: ])?(.*)$").find(l)?.let {
            return PhoneAction.AutoSendMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]), "sms")
        }
        Regex("^(?:message|msg) likho aur bhejo (.+?) ko[: ]?(.*)$").find(l)?.let {
            return PhoneAction.AutoSendMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]), "whatsapp")
        }
        Regex("^(?:type|write) and send (?:a )?(?:whatsapp|whats app) (?:message |msg )?(?:to )?(.+?)(?: saying|:| -)? ?(.+)$").find(l)?.let {
            return PhoneAction.AutoSendMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]), "whatsapp")
        }
        Regex("^(?:type|write) and send (?:a )?(?:sms|text|message|msg)(?: to )?(.+?)(?: saying|:| -)? ?(.+)$").find(l)?.let {
            return PhoneAction.AutoSendMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]), "sms")
        }
        // ---- messages: whatsapp / sms (draft — composer opens, you press send) ----
        Regex("^(?:send (?:a |an )?)?(?:whatsapp|whats app)(?: message| msg)?(?: to)? (.+?)(?: (?:saying|that says|that|message|msg)|:) (.+)$").find(l)?.let {
            return PhoneAction.WhatsAppMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]))
        }
        Regex("^send (?:a |an )?(?:sms|text|text message|message)(?: to)? (.+?)(?: (?:saying|that says|that|with message)|:) (.+)$").find(l)?.let {
            return PhoneAction.Sms(cleanName(it.groupValues[1]), original(t, it.groupValues[2]))
        }
        Regex("^(.+?) ko (?:whatsapp|whats app) (?:karo|kar do|bhejo)(?: (?:ke|k|ki) )?(.*)$").find(l)?.let {
            return PhoneAction.WhatsAppMessage(cleanName(it.groupValues[1]), original(t, it.groupValues[2]))
        }
        Regex("^(.+?) ko (?:sms|message|msg) (?:karo|kar do|bhejo)(?: (?:ke|k|ki) )?(.*)$").find(l)?.let {
            return PhoneAction.Sms(cleanName(it.groupValues[1]), original(t, it.groupValues[2]))
        }

        // ---- reminders (F4: same tool as Live set_reminder) ----
        Regex("^remind me to (.+?)(?: (?:at|on|in|tomorrow|today) ?(.*))?$").find(l)?.let {
            return PhoneAction.Reminder(original(t, it.groupValues[1]), original(t, it.groupValues[2]))
        }
        Regex("^mujhe (.+?) (?:ki )?yaad dila(?:o| do| dena)$").find(l)?.let {
            return PhoneAction.Reminder(original(t, it.groupValues[1]), "")
        }
        Regex("^(?:set )?(?:a )?reminder (?:to |for )?(.+)$").find(l)?.let {
            return PhoneAction.Reminder(original(t, it.groupValues[1]), "")
        }

        // ---- calls ----
        Regex("^(?:please )?(?:call|dial|phone|ring)(?: up)? (.+)$").find(l)?.let { return PhoneAction.Call(cleanName(it.groupValues[1])) }
        Regex("^(.+?) ko (?:call|phone|dial) (?:karo|kar do|lagao|laga do)$").find(l)?.let { return PhoneAction.Call(cleanName(it.groupValues[1])) }

        // ---- youtube search (spec §14.2) — before generic web search ----
        Regex("^(?:search youtube for|youtube search|youtube par search karo) (.+)$").find(l)?.let { return PhoneAction.YouTubeSearch(original(t, it.groupValues[1])) }
        Regex("^play (.+?) on youtube$").find(l)?.let { return PhoneAction.YouTubeSearch(original(t, it.groupValues[1])) }
        Regex("^(.+?) youtube (?:par|pe|mein) (?:chalao|chala do|play karo|search karo|dhoondo|dhoondho|dekhao|khol do)$").find(l)?.let { return PhoneAction.YouTubeSearch(original(t, it.groupValues[1])) }
        Regex("^youtube (?:par|pe|mein|on) (.+)$").find(l)?.let {
            val q = it.groupValues[1].trim()
            if (q.isNotEmpty() && q !in listOf("kholo", "khol do", "chalao", "open")) return PhoneAction.YouTubeSearch(original(t, q))
        }

        // ---- contacts, search ----
        if (Regex("^(?:pick|select|choose|show|open) (?:a |my )?contacts?$").matches(l)) return PhoneAction.PickContact
        if (Regex("^(?:show |open |dikhao |kholo |saare |all |mere )?(contacts?|rabte)(?: kholo| dikhao| show karo| khol do| dekhao)?$").matches(l)) return PhoneAction.OpenContacts
        Regex("^(?:search|google|look up)(?: the web)?(?: for)? (.+)$").find(l)?.let { return PhoneAction.WebSearch(original(t, it.groupValues[1])) }

        // ---- gallery videos / files (before generic open: they contain "video"/"file") ----
        if (Regex("\\b(videos?|movies?)\\b").containsMatchIn(l) && Regex("\\b(open|kholo|khol|chalao|lagao|dikhao|play|watch)\\b").containsMatchIn(l)) return PhoneAction.OpenVideos
        if (Regex("\\b(files?|downloads?|file manager|documents?)\\b").containsMatchIn(l) && Regex("\\b(open|kholo|khol|chalao|dikhao|show)\\b").containsMatchIn(l)) return PhoneAction.OpenFiles

        // ---- play music (F4: same tool as Live play_music) ----
        Regex("^play (.+?) on spotify$").find(l)?.let { return PhoneAction.PlayMusic(original(t, it.groupValues[1]), "spotify") }
        Regex("^play (.+?) on (?:youtube music|yt music)$").find(l)?.let { return PhoneAction.PlayMusic(original(t, it.groupValues[1]), "youtube music") }
        Regex("^(?:play|chalao|chala do|sunao) (.+?) (?:on spotify|spotify (?:par|pe|mein))$").find(l)?.let { return PhoneAction.PlayMusic(original(t, it.groupValues[1]), "spotify") }
        Regex("^(.+?) (?:spotify par|spotify pe|spotify mein) (?:chalao|chala do|play karo|sunao)$").find(l)?.let { return PhoneAction.PlayMusic(original(t, it.groupValues[1]), "spotify") }
        if (Regex("^(?:play )?(?:some )?music$").matches(l) || Regex("^(music|gana|gaana|song)(?: chalao| chala do| play karo| sunao)?$").matches(l)) return PhoneAction.PlayMusic("", "")
        Regex("^play (.+)$").find(l)?.let {
            val q = it.groupValues[1].trim()
            if (q !in listOf("videos", "video", "movies", "movie")) return PhoneAction.PlayMusic(original(t, q), "")
        }

        // ---- take a photo (F4: same tool as Live take_photo) ----
        if (Regex("\\b(photo|selfie|tasveer|picture)\\b").containsMatchIn(l) &&
            Regex("\\b(take|lo|le|khincho|kheencho|capture|click|banao)\\b").containsMatchIn(l)) return PhoneAction.TakePhoto

        // ---- open a settings page (F4: same tool as Live open_settings) ----
        if (l.contains("settings") || l.contains("setting")) {
            val section = when {
                l.contains("wifi") || l.contains("wi-fi") || l.contains("wireless") -> "wifi"
                l.contains("bluetooth") -> "bluetooth"
                l.contains("display") || l.contains("brightness") || l.contains("screen") -> "display"
                l.contains("sound") || l.contains("volume") || l.contains("audio") -> "sound"
                l.contains("battery") -> "battery"
                l.contains("location") -> "location"
                l.contains("apps") || l.contains("applications") -> "apps"
                l.contains("notification") -> "notifications"
                l.contains("accessibility") -> "accessibility"
                else -> ""
            }
            return PhoneAction.OpenSettings(section)
        }

        // ---- device info + volume (MYRA-style system control) ----
        if (l.contains("battery")) return PhoneAction.DeviceInfo("battery")
        if (l.contains("storage") || l.contains("space kitna") || l.contains("space kitni")) return PhoneAction.DeviceInfo("storage")
        if (l.contains("time kya") || l.contains("waqt kya") || l.contains("kitne baje") || l.contains("time batao")) return PhoneAction.DeviceInfo("time")
        if (l.contains("date kya") || l.contains("tareekh") || l.contains("tarikh") || l.contains("aaj ki date")) return PhoneAction.DeviceInfo("date")
        if (l.contains("mute") && (l.contains("kar") || l.contains("volume") || l.contains("awaz") || l.contains("aawaz"))) return PhoneAction.Volume("mute")
        if ((l.contains("volume") || l.contains("awaz") || l.contains("aawaz")) && (l.contains("barha") || l.contains("tez") || l.contains("zyada") || l.contains("increase"))) return PhoneAction.Volume("up")
        if ((l.contains("volume") || l.contains("awaz") || l.contains("aawaz")) && (l.contains("kam") || l.contains("halki") || l.contains("down") || l.contains("low"))) return PhoneAction.Volume("down")

        // ---- screen share: "meri screen dekho / screen share karo" ----
        if (l.contains("screen") && (l.contains("share") || l.contains("dekho") || l.contains("dikhao") || l.contains("dekh") || l.contains("show"))) return PhoneAction.OpenScreen

        // ---- the assistant's own browser: anything mentioning "browser" opens it in-app ----
        if (l.contains("browser")) {
            val q = l.replace(Regex("\\b(browser|kholo|khol|open|mein|par|pe|search|karo|kar|do|chalao|the|app|mera|meri|apna)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            return PhoneAction.OpenBrowser(q)
        }

        // ---- open things ----
        val opened = Regex("^(?:$OPEN_VERBS) (?:the )?(.+?)(?: app)?$").find(l)?.groupValues?.get(1)
            ?: Regex("^(.+?)(?: app)? (?:open karo|open kar do|kholo|khol do|chalao|chala do|open)$").find(l)?.groupValues?.get(1)
        if (opened != null) return openTarget(opened.trim())
        return null
    }

    private fun openTarget(name: String): PhoneAction = when (name) {
        "youtube", "you tube", "yt" -> PhoneAction.OpenYouTube
        "whatsapp", "whats app", "whatsapp messenger" -> PhoneAction.OpenWhatsApp
        "chrome", "google chrome" -> PhoneAction.OpenChrome
        "browser" -> PhoneAction.OpenBrowser("")
        "camera", "the camera" -> PhoneAction.OpenCamera
        "gallery", "photos", "my photos", "photo gallery", "google photos" -> PhoneAction.OpenGallery
        "videos", "my videos", "gallery videos", "movies" -> PhoneAction.OpenVideos
        "files", "file manager", "downloads", "my files", "documents" -> PhoneAction.OpenFiles
        else -> PhoneAction.OpenApp(name)
    }

    private fun parseTimer(l: String): PhoneAction? {
        val unit = "(second|seconds|sec|secs|minute|minutes|min|mins|hour|hours|hr|hrs|ghanta|ghante|minat|minute ka)"
        val m = Regex("(?:timer|countdown).*?(\\d+(?:\\.\\d+)?) ?$unit").find(l)
            ?: Regex("(\\d+(?:\\.\\d+)?) ?$unit(?: ka| ke)? (?:timer|countdown)").find(l) ?: return null
        val n = m.groupValues[1].toDouble(); val u = m.groupValues[2]
        val secs = when {
            u.startsWith("sec") -> n
            u.startsWith("min") -> n * 60
            else -> n * 3600
        }.toInt()
        return if (secs in 1..86_400) PhoneAction.StartTimer(secs) else null
    }

    private fun parseAlarm(l: String): PhoneAction? {
        if (!Regex("\\b(alarm|jaga|uthao)\\b").containsMatchIn(l)) return null
        val m = Regex("(\\d{1,2})(?:[: .](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)?").findAll(l).lastOrNull { it.groupValues[1].isNotEmpty() } ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].replace(".", "")
        if (ap == "pm" && h in 1..11) h += 12
        if (ap == "am" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) PhoneAction.SetAlarm(h, min) else null
    }

    private fun cleanName(s: String) = s.trim().removePrefix("to ").removePrefix("my ").trim().let {
        if (digitsOnly.matches(it)) it.replace(" ", "").replace("-", "") else it.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
    }

    /** Keep the user's original capitalisation for message bodies. */
    private fun original(full: String, lowerTail: String): String {
        if (lowerTail.isEmpty()) return ""
        val i = full.lowercase().lastIndexOf(lowerTail)
        return if (i >= 0) full.substring(i, i + lowerTail.length) else lowerTail
    }
}
