package com.badshah222.rani.ai.core

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.badshah222.rani.ai.data.phone.ContactEntry
import com.badshah222.rani.ai.data.phone.ContactsHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v3.0.0 §23.4 — Contact Mind: RANI's own saved copy of the user's contacts.
 *
 * Opt-in only (Settings "Save my contacts to RANI's mind", default OFF).
 * Encrypted on-device store (EncryptedFile/MasterKey AES-GCM, same pattern
 * as MemoryStore) — names + numbers only, no photos/emails/addresses.
 * Contacts NEVER leave the phone: never uploaded to Supabase, never sent
 * to any server, never put in a memory-sync row. Turning the option OFF
 * deletes the saved copy. Without the allow, RANI keeps resolving names
 * live from the phone's contacts at call time (today's behaviour) — she
 * just cannot do the fuzzy/near-miss reasoning, and says so honestly.
 *
 * ONE resolver, used by voice (RaniLiveBridge) AND chat (PhoneExecutor):
 *   Exact            — one contact, one number: use it, never ask.
 *   ExactMultiNumber — one contact, 2+ numbers: ask which number.
 *   Ambiguous        — 3-4 different contacts share the name: she asks
 *                      "kis Ami ko" with distinguishable options, waits
 *                      for the pick, and REMEMBERS it for that name.
 *   NearMiss         — spoken name not in contacts/WhatsApp, but a close
 *                      match exists: she offers the closest saved match
 *                      and waits for yes. Never invents a number.
 *   NotFound         — nothing close: plain "not found", no number asked.
 * Follow-up picks close the loop (FIX-2): tryPick() understands
 * "Name option N" / "second Name" / "doosri Name" against the pending
 * Ambiguous options (kept on the companion — instances are fresh per
 * call) or the exact-match list, calls rememberPick(), and "doosri"
 * after a remembered pick clears the preference and re-asks.
 * resolveToNumber() is the single number resolver for EVERY action
 * path (Live bridge, Tools card, chat, auto-send, WhatsApp) — it
 * returns a number or a question string ("multiple:…",
 * "multiple_number:…", "nearmiss:…", "notfound", "notfound_nosave"
 * (save-OFF, genuinely absent), "nocontacts"); no
 * caller may launch an intent on a question string. In save-OFF mode
 * (the default) ambiguity is a COUNT of exact matches, never
 * distinct() names — 3 contacts all named "Ami" are 3 options.
 */
class ContactMind(private val ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val file: File = File(File(appCtx.filesDir, "contacts"), "rani_contacts.json").apply {
        parentFile?.mkdirs()
    }
    private val lock = Any()

    companion object {
        /** FIX-2 (v3.0.0 §23.4): the last Ambiguous question, kept in memory
         *  for the follow-up turn. ContactMind instances are created fresh
         *  per call (bridge / chat / tools), so an instance field would be
         *  lost between "which Ami?" and "option 2" — the pending options
         *  must live on the companion (process-lifetime, never persisted,
         *  never leaves the phone). Pair: normalised spoken name -> options
         *  (id = the phone contact id, so a pick resolves in BOTH modes:
         *  save-ON from the store, save-OFF from a live loadAll by id). */
        @Volatile
        private var lastAmbiguous: Pair<String, List<ContactOption>>? = null

        /** FIX-2: the contact a "which number?" question is about. A
         *  contact pick ("Ami option 2") can itself have 2 numbers, so
         *  the follow-up "Ami mobile" must resolve against THAT contact —
         *  not re-derive it from the name (in save-OFF mode the name
         *  alone is 3 contacts again). Set whenever any path returns a
         *  "multiple_number:…" question; cleared once the number is
         *  picked or a fresh non-pick name supersedes the question. */
        @Volatile
        private var lastMultiNumber: PickedContact? = null

        /** FIX-3 F1: the pending name is stored NORMALISED (the doc
         *  comment always said so, but the code stored the raw spoken
         *  name — "Ami" — so the pending branch compared normalize("Ami")
         *  = "ami" against raw "Ami" and rejected every named pick).
         *  Normalising here covers BOTH storePending call sites
         *  (save-ON resolve() and save-OFF resolveToNumber()). */
        fun normalizeName(name: String): String = name.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .removePrefix("mr ").removePrefix("mrs ").removePrefix("miss ").removePrefix("dr ").trim()

        fun storePending(spoken: String, options: List<ContactOption>) {
            lastAmbiguous = normalizeName(spoken) to options
            lastMultiNumber = null
        }

        fun clearPending() { lastAmbiguous = null }

        fun storePendingNumber(contact: PickedContact) { lastMultiNumber = contact }

        fun clearPendingNumber() { lastMultiNumber = null }
    }

    /** Outcome of a follow-up pick ("Ami option 2" / "second Ami" /
     *  "doosri Ami"). Picked carries the chosen contact's real numbers —
     *  the caller still asks which NUMBER if there are several. Reask is
     *  the "doosri" correction of a remembered pick: the preference is
     *  cleared and the options are asked again. */
    data class PickedContact(val id: Long, val name: String, val numbers: List<String>, val labels: List<String>)
    sealed interface PickOutcome {
        data class Picked(val contact: PickedContact) : PickOutcome
        data class Reask(val options: List<ContactOption>) : PickOutcome
        object NoPick : PickOutcome
    }

    data class SavedContact(
        val id: Long,
        val name: String,
        val norm: String,
        val numbers: List<String>,
        val labels: List<String>,
    )
    data class ContactOption(val id: Long, val name: String, val detail: String)

    sealed interface Resolution {
        data class Exact(val contact: SavedContact, val numbers: List<String>) : Resolution
        data class ExactMultiNumber(val contact: SavedContact, val numbers: List<String>) : Resolution
        data class Ambiguous(val options: List<ContactOption>) : Resolution
        data class NearMiss(val match: SavedContact, val inWhatsApp: Boolean) : Resolution
        object NotFound : Resolution
        object NoPermission : Resolution
    }

    /** Known name variants — data, not guesswork (listed in code, §23.4). */
    val variantTable: Map<String, List<String>> = mapOf(
        "ami" to listOf("ammi", "ammu", "amma"),
        "ammi" to listOf("ami", "ammu", "amma"),
        "ammu" to listOf("ami", "ammi", "amma"),
        "abbu" to listOf("abu", "baba", "abba"),
        "abu" to listOf("abbu", "baba", "abba"),
        "bhai" to listOf("bhaiya", "bhaijaan"),
        "bhaiya" to listOf("bhai", "bhaijaan"),
        "api" to listOf("aapi", "baji"),
        "aapi" to listOf("api", "baji"),
        "chacha" to listOf("chachu"),
        "chachu" to listOf("chacha"),
        "dada" to listOf("dadi"),
        "dadi" to listOf("dada"),
        "nana" to listOf("nani"),
        "nani" to listOf("nana"),
    )

    private fun encryptedFile(): EncryptedFile {
        val mk = MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedFile.Builder(appCtx, file, mk, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
    }

    private fun loadState(): JSONObject {
        return try {
            if (!file.exists() || file.length() == 0L) return JSONObject().put("contacts", JSONArray()).put("prefs", JSONObject())
            JSONObject(String(encryptedFile().openFileInput().use { it.readBytes() }, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.d("ContactMind", "load failed: ${e.message}")
            JSONObject().put("contacts", JSONArray()).put("prefs", JSONObject())
        }
    }

    private fun persist(state: JSONObject) {
        try {
            encryptedFile().openFileOutput().use { it.write(state.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) { Log.d("ContactMind", "persist failed: ${e.message}") }
    }

    fun normalize(name: String): String = normalizeName(name)

    private fun contactsFrom(state: JSONObject): List<SavedContact> {
        val arr = state.optJSONArray("contacts") ?: JSONArray()
        val out = ArrayList<SavedContact>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nums = ArrayList<String>()
            val labs = ArrayList<String>()
            val na = o.optJSONArray("numbers") ?: JSONArray()
            val la = o.optJSONArray("labels") ?: JSONArray()
            for (j in 0 until na.length()) { nums.add(na.optString(j)); labs.add(la.optString(j, "mobile")) }
            out.add(SavedContact(o.optLong("id"), o.optString("name"), o.optString("norm"), nums, labs))
        }
        return out
    }

    fun savedCount(): Int = synchronized(lock) { contactsFrom(loadState()).size }

    fun isSaved(): Boolean = file.exists() && file.length() > 0

    /** Imports the phone's contacts into the encrypted store. Returns the
     *  number saved. Best-effort: a failed import leaves the old copy. */
    fun importFromPhone(): Int {
        if (!ContactsHelper.hasPermission(appCtx)) return 0
        val all = runCatching { ContactsHelper.loadAll(appCtx) }.getOrDefault(emptyList())
        val arr = JSONArray()
        for (e in all) {
            if (e.numbers.isEmpty()) continue
            arr.put(JSONObject()
                .put("id", e.id).put("name", e.name).put("norm", normalize(e.name))
                .put("numbers", JSONArray(e.numbers))
                // FIX-2: real labels from ContactsHelper (Phone.TYPE), not
                // fabricated "mobile"/"other" — so ambiguous options can
                // actually be told apart (home vs mobile vs work).
                .put("labels", JSONArray().apply {
                    e.numbers.forEachIndexed { idx, _ -> put(e.labels.getOrNull(idx) ?: "mobile") }
                }))
        }
        val state = JSONObject().put("contacts", arr).put("prefs", loadState().optJSONObject("prefs") ?: JSONObject())
            .put("saved_at", System.currentTimeMillis())
        synchronized(lock) { persist(state) }
        return arr.length()
    }

    /** Deletes the saved copy (Settings OFF). */
    fun deleteSaved() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    /** Best-effort check: does this name exist as a WhatsApp contact?
     *  (RawContacts with the WhatsApp account type.) Never claims a
     *  WhatsApp search it did not do. */
    fun isWhatsAppContact(name: String): Boolean {
        val norm = normalize(name)
        return try {
            val names = HashSet<String>()
            appCtx.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
                ContactsContract.RawContacts.ACCOUNT_TYPE + " = ?",
                arrayOf("com.whatsapp"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { names.add(normalize(it)) }
                }
            }
            names.contains(norm)
        } catch (e: Exception) { false }
    }

    fun rememberPick(spoken: String, contactId: Long) {
        synchronized(lock) {
            val state = loadState()
            val prefs = state.optJSONObject("prefs") ?: JSONObject()
            prefs.put(normalize(spoken), contactId)
            state.put("prefs", prefs)
            persist(state)
        }
    }

    fun clearPreference(spoken: String) {
        synchronized(lock) {
            val state = loadState()
            state.optJSONObject("prefs")?.remove(normalize(spoken))
            persist(state)
        }
    }

    fun preferences(): Map<String, Long> {
        val prefs = loadState().optJSONObject("prefs") ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        val keys = prefs.keys()
        while (keys.hasNext()) { val k = keys.next(); out[k] = prefs.optLong(k) }
        return out
    }

    private fun levenshtein(a: String, b: String): Int {
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

    /** Follow-up pick after an ExactMultiNumber question: "Ami number 2",
     *  "Ami 2", "Ami (mobile)", "Ami mobile" -> the picked number, or null.
     *  Only honoured when the base name is ONE saved contact with several
     *  numbers; anything else returns null and the caller resolves the
     *  full name normally (so a real contact called e.g. "Ali 2" still
     *  resolves by its own name). Shared by the Live bridge and the
     *  Tools skill card — the single place that understands a pick.
     *  FIX-2: works in save-OFF mode too — the old code called resolve(),
     *  which reads only the SAVED store, so with saving off (the default)
     *  every "Ami mobile" fell into NotFound. Now it falls back to a live
     *  loadAll when nothing is saved. */
    fun pickNumber(target: String): String? {
        val t = target.trim()
        // Bare answer to a pending "which number?" question: "mobile" /
        // "number 2" (the page tells the model the user answers this way).
        lastMultiNumber?.let { pending ->
            val labelOnly = Regex("(?i)^(mobile|home|work|other)$").find(t)
            if (labelOnly != null) {
                val idx = pending.labels.indexOfFirst { it.equals(labelOnly.groupValues[1], ignoreCase = true) }
                if (idx in pending.numbers.indices) { clearPendingNumber(); return pending.numbers[idx] }
                return null
            }
            Regex("(?i)^number\\s+(\\d+)$").find(t)?.let { m ->
                val idx = (m.groupValues[1].toIntOrNull() ?: 0) - 1
                if (idx in pending.numbers.indices) { clearPendingNumber(); return pending.numbers[idx] }
                return null
            }
        }
        val (base, pick) = parsePick(t) ?: return null
        // The pending number-question's contact wins over re-deriving
        // from the name (in save-OFF mode the name alone is ambiguous
        // again — e.g. 3 Amis, option 2 picked, now "Ami mobile").
        val pending = lastMultiNumber
        val c = if (pending != null && normalize(base) == normalize(pending.name)) pending
            else multiNumberContact(base) ?: return null
        val n = pick.toIntOrNull()
        val idx = if (n != null) n - 1
            else c.labels.indexOfFirst { it.equals(pick, ignoreCase = true) }
        val num = if (idx in c.numbers.indices) c.numbers[idx] else null
        if (num != null) clearPendingNumber()
        return num
    }

    /** The one contact with several numbers for a base name — saved store
     *  first, live phone contacts when nothing is saved (save-OFF). Null
     *  unless exactly one contact matches and it has 2+ numbers. */
    private fun multiNumberContact(base: String): SavedContact? {
        val r = resolve(base)
        if (r is Resolution.ExactMultiNumber) return r.contact
        val saved = synchronized(lock) { contactsFrom(loadState()) }
        if (saved.isNotEmpty()) return null
        if (!ContactsHelper.hasPermission(appCtx)) return null
        val exact = ContactsHelper.loadAll(appCtx).filter { normalize(it.name) == normalize(base) }
        if (exact.size != 1 || exact[0].numbers.size < 2) return null
        val e = exact[0]
        return SavedContact(e.id, e.name, normalize(e.name), e.numbers, e.labels)
    }

    private fun parsePick(target: String): Pair<String, String>? {
        // "Ami number 2" / "Ami 2" -> ("Ami", "2")
        Regex("(?i)\\s+(?:number\\s+)?(\\d+)\\s*$").find(target)?.let { m ->
            val base = target.substring(0, m.range.first).trim()
            if (base.isNotEmpty()) return base to m.groupValues[1]
        }
        // "Ami (mobile)" / "Ami mobile" -> ("Ami", "mobile")
        Regex("(?i)\\s+\\(?((mobile|home|work|other))\\)?\\s*$").find(target)?.let { m ->
            val base = target.substring(0, m.range.first).trim()
            if (base.isNotEmpty()) return base to m.groupValues[1].lowercase()
        }
        return null
    }

    /** Distinguishing detail for one ambiguous option — the contact's real
     *  first-number label (home/mobile/work from Phone.TYPE, never
     *  invented) + its position, and the number count when it has several
     *  ("Ami (home — option 1)" vs "Ami (mobile, 2 numbers — option 2)").
     *  Digits are NEVER in a detail (§23.2 Fix 5). */
    fun optionDetail(c: SavedContact, idx: Int): String {
        val label = c.labels.firstOrNull() ?: "mobile"
        val count = if (c.numbers.size > 1) ", ${c.numbers.size} numbers" else ""
        return "$label$count — option ${idx + 1}"
    }

    fun optionDetail(entry: ContactEntry, idx: Int): String {
        val label = entry.labels.firstOrNull() ?: "mobile"
        val count = if (entry.numbers.size > 1) ", ${entry.numbers.size} numbers" else ""
        return "$label$count — option ${idx + 1}"
    }

    fun optionDetail(c: PickedContact, idx: Int): String {
        val label = c.labels.firstOrNull() ?: "mobile"
        val count = if (c.numbers.size > 1) ", ${c.numbers.size} numbers" else ""
        return "$label$count — option ${idx + 1}"
    }

    private val ordinalWords = mapOf(
        "first" to 1, "1st" to 1, "pehli" to 1,
        "second" to 2, "2nd" to 2, "doosri" to 2, "dusri" to 2,
        "third" to 3, "3rd" to 3, "teesri" to 3, "tisri" to 3,
        "fourth" to 4, "4th" to 4, "chauthi" to 4, "chothi" to 4,
    )

    /** Parses a follow-up pick into (base name, 1-based option index) or
     *  ("", -1) for a bare "doosri"/"the other one" (pending name applies).
     *  Accepted: "Ami option 2", "option 2", "Ami 2", "second Ami",
     *  "doosri Ami", "Ami doosri", "second one", "the other Ami".
     *  Deliberately NOT "Ami number 2" — that is pickNumber's convention
     *  (which NUMBER of one contact), not which CONTACT. Returns null when
     *  the text is not a pick at all. */
    private fun parsePickContact(target: String): Pair<String, Int>? {
        val t = target.trim().lowercase()
        if (t.isEmpty()) return null
        // "Ami option 2" / "option 2"
        Regex("(?i)^(?:(.+?)\\s+)?option\\s+(\\d+)$").find(target.trim())?.let { m ->
            return (m.groupValues[1].ifEmpty { "" }) to (m.groupValues[2].toIntOrNull() ?: return null)
        }
        // "second Ami" / "doosri Ami" / "second one" / "doosri"
        Regex("(?i)^(first|second|third|fourth|1st|2nd|3rd|4th|pehli|doosri|dusri|teesri|tisri|chauthi|chothi)(?:\\s+(.+))?$")
            .find(target.trim())?.let { m ->
                val idx = ordinalWords[m.groupValues[1].lowercase()] ?: return null
                val base = m.groupValues[2].trim()
                if (base.isEmpty() || base == "one") return "" to idx
                return base to idx
            }
        // "the other Ami" / "the other one" / "the other"
        Regex("(?i)^the other(?:\\s+(.+))?$").find(target.trim())?.let { m ->
            val base = m.groupValues[1].trim()
            if (base.isEmpty() || base == "one") return "" to 2
            return base to -2 // -2 = "the other one" (see tryPick)
        }
        // "Ami doosri" (ordinal after the name)
        Regex("(?i)^(.+?)\\s+(pehli|doosri|dusri|teesri|tisri|chauthi|chothi)$").find(target.trim())?.let { m ->
            return m.groupValues[1].trim() to (ordinalWords[m.groupValues[2].lowercase()] ?: return null)
        }
        // "Ami 2" (bare trailing number — only a contact pick when pending
        // options exist for that name; otherwise pickNumber owns it)
        Regex("(?i)^(.+?)\\s+(\\d+)$").find(target.trim())?.let { m ->
            return m.groupValues[1].trim() to (m.groupValues[2].toIntOrNull() ?: return null)
        }
        return null
    }

    /** Follow-up pick after an Ambiguous question — FIX-2 (v3.0.0 §23.4).
     *  Before this, ContactOption carried an id nobody ever used and
     *  rememberPick() had ZERO callers: every "option 2" / "second Ami" /
     *  "doosri Ami" fell into NotFound and looped forever. Now:
     *   - a pick against the pending options resolves via the stored id,
     *     calls rememberPick() (next time the name resolves directly),
     *     and returns the contact's real numbers;
     *   - "doosri Ami" / "the other Ami" AFTER a remembered pick (no
     *     pending question) clears the preference and re-asks;
     *   - "doosri" with a pending question means the second option.
     *  Anything that is not a pick returns NoPick and the caller resolves
     *  the full name normally — so a real contact called e.g. "Ali 2"
     *  still resolves by its own name. */
    fun tryPick(target: String): PickOutcome {
        val parsed = parsePickContact(target) ?: return PickOutcome.NoPick
        val (base, rawIdx) = parsed
        val baseNorm = normalize(base)
        val low = target.trim().lowercase()
        // Only "doosri / dusri / the other" ever clears a remembered pick —
        // "second Ami" / "Ami 2" / "option 2" are plain picks.
        val isOtherWord = rawIdx == -2 || low.contains("doosri") || low.contains("dusri") || low.startsWith("the other")
        val pending = lastAmbiguous
        if (pending != null) {
            val (pendingName, options) = pending
            // FIX-3 F1 (belt-and-braces): compare normalised on BOTH
            // sides — the pending name is normalised at store time now,
            // but a stale/companion-set raw name must never reject a
            // named pick again ("Ami option 2" / "second Ami" /
            // "doosri Ami" all compare "ami" == "ami").
            val nameNorm = if (baseNorm.isEmpty()) normalize(pendingName) else baseNorm
            if (nameNorm != normalize(pendingName)) return PickOutcome.NoPick
            val idx = if (rawIdx == -2) 2 else rawIdx
            if (idx < 1 || idx > options.size) return PickOutcome.NoPick
            val opt = options[idx - 1]
            val contact = contactById(opt.id) ?: return PickOutcome.NoPick
            if (contact.numbers.isEmpty()) return PickOutcome.NoPick
            rememberPick(nameNorm, opt.id)
            clearPending()
            return PickOutcome.Picked(contact)
        }
        // No pending question: an ordinal/number pick still resolves when
        // the name itself is ambiguous (3 Amis, no ask yet).
        if (baseNorm.isEmpty()) return PickOutcome.NoPick
        val exact = exactContacts(baseNorm)
        if (exact.size <= 1) {
            // Single contact: only the preference-clear applies — the
            // caller then re-resolves the name without the old pick.
            if (isOtherWord && preferences().containsKey(baseNorm)) clearPreference(base)
            return PickOutcome.NoPick
        }
        if (isOtherWord && preferences().containsKey(baseNorm)) {
            // "doosri Ami" after a remembered pick: clear it, ask again.
            clearPreference(base)
            return reaskFor(base) ?: PickOutcome.NoPick
        }
        val idx = if (rawIdx == -2) 2 else rawIdx
        if (idx < 1 || idx > exact.size) return PickOutcome.NoPick
        val contact = exact[idx - 1]
        rememberPick(baseNorm, contact.id)
        return PickOutcome.Picked(contact)
    }

    /** Exact-name matches for a normalised name — saved store first,
     *  live phone contacts when nothing is saved (save-OFF mode). */
    private fun exactContacts(norm: String): List<PickedContact> {
        val saved = synchronized(lock) { contactsFrom(loadState()) }.filter { it.norm == norm }
        if (saved.isNotEmpty()) return saved.map { PickedContact(it.id, it.name, it.numbers, it.labels) }
        if (!ContactsHelper.hasPermission(appCtx)) return emptyList()
        return ContactsHelper.loadAll(appCtx).filter { normalize(it.name) == norm }
            .map { PickedContact(it.id, it.name, it.numbers, it.labels) }
    }

    /** Rebuilds the Ambiguous options for a name (after a "doosri" clear),
     *  or null when the name is no longer ambiguous. FIX-4 BLOCKING-2:
     *  resolve() reads ONLY the saved store, so in save-OFF mode (the
     *  default — nothing saved) it returns NotFound even when 3 live
     *  contacts share the name, and the F2 guard then turned the
     *  pick-shaped "doosri Ami" into "notfound" instead of re-asking.
     *  The save-ON path is unchanged (resolve() returns Ambiguous);
     *  the save-OFF path rebuilds the options from exactContacts()
     *  (saved store first, live loadAll when nothing is saved — the
     *  same source the save-OFF branch of resolveToNumber() uses),
     *  stores them as the pending question via storePending(), and
     *  returns them so the caller asks the options. The preference
     *  was already cleared by tryPick() before this runs. */
    private fun reaskFor(spoken: String): PickOutcome.Reask? {
        val r = resolve(spoken)
        if (r is Resolution.Ambiguous) return PickOutcome.Reask(r.options)
        val exact = exactContacts(normalize(spoken))
        if (exact.size <= 1) return null
        val options = exact.take(4).mapIndexed { idx, c ->
            ContactOption(c.id, c.name, optionDetail(c, idx))
        }
        storePending(spoken, options)
        return PickOutcome.Reask(options)
    }

    /** The chosen contact's real numbers, from the saved store when
     *  present, else from a live loadAll by contact id (save-OFF mode). */
    private fun contactById(id: Long): PickedContact? {
        val state = synchronized(lock) { loadState() }
        contactsFrom(state).firstOrNull { it.id == id }?.let {
            return PickedContact(it.id, it.name, it.numbers, it.labels)
        }
        if (!ContactsHelper.hasPermission(appCtx)) return null
        return ContactsHelper.loadAll(appCtx).firstOrNull { it.id == id }?.let {
            PickedContact(it.id, it.name, it.numbers, it.labels)
        }
    }

    /** THE single number resolver for every action path (FIX-1/FIX-3,
     *  v3.0.0 §23.4): Live bridge, Tools card, chat (PhoneExecutor) and
     *  auto-send (MessageSender) all call this — one rule, everywhere.
     *  Returns a diallable number, or a question string the caller MUST
     *  return without launching any intent:
     *   "multiple:<Name> (<detail>), …"   — several contacts, same name
     *   "multiple_number:1 (mobile), …"   — one contact, several numbers
     *   "nearmiss:<Name>[|whatsapp]"      — close match, waits for yes
     *   "notfound" / "notfound_nosave" / "nocontacts" / "fail"
     *   ("notfound_nosave": save-OFF only — the name is not in the phone
     *   contacts AND nothing is saved, so she says so honestly and how
     *   to turn the save on; every caller must treat it as a non-number)
     *  Order matters: a follow-up pick first (pending options), then a
     *  number pick ("Ami mobile"), then the main resolution. In save-OFF
     *  (the default) the ambiguity check is a COUNT of exact matches —
     *  the old code collapsed distinct() names first, so 3 contacts all
     *  named "Ami" looked like one and silently dialled the first. */
    fun resolveToNumber(target: String): String {
        val t = target.trim()
        if (t.isEmpty()) return "fail"
        if (t.matches(Regex("^[+]?[0-9][0-9 \\-]{5,}$"))) return t.replace(" ", "").replace("-", "")
        when (val p = tryPick(t)) {
            is PickOutcome.Picked ->
                return if (p.contact.numbers.size == 1) p.contact.numbers.first()
                else {
                    // The number question is about THIS contact — remember
                    // it so "Ami mobile" resolves against it, not the name.
                    storePendingNumber(p.contact)
                    "multiple_number:" + p.contact.numbers.indices.joinToString(", ") {
                        "${it + 1} (${p.contact.labels.getOrNull(it) ?: "mobile"})"
                    }
                }
            is PickOutcome.Reask ->
                return "multiple:" + p.options.joinToString(", ") { "${it.name} (${it.detail})" }
            PickOutcome.NoPick -> { /* fall through */ }
        }
        pickNumber(t)?.let { return it }
        // A fresh name that is not a pick supersedes any pending number
        // question (pickNumber above already consumed real answers).
        if (parsePick(t) == null && !Regex("(?i)^(mobile|home|work|other)$").matches(t) &&
            !Regex("(?i)^number\\s+\\d+$").matches(t)) clearPendingNumber()
        if (SecureStore(appCtx).contactsSaveEnabled) {
            return when (val r = resolve(t)) {
                is Resolution.Exact -> r.numbers.firstOrNull() ?: "notfound"
                is Resolution.ExactMultiNumber -> {
                    storePendingNumber(PickedContact(r.contact.id, r.contact.name, r.numbers, r.contact.labels))
                    "multiple_number:" + r.numbers.indices.joinToString(", ") { "${it + 1} (${r.contact.labels.getOrNull(it) ?: "mobile"})" }
                }
                is Resolution.Ambiguous -> "multiple:" + r.options.joinToString(", ") { "${it.name} (${it.detail})" }
                is Resolution.NearMiss -> "nearmiss:" + r.match.name + if (r.inWhatsApp) "|whatsapp" else ""
                Resolution.NotFound -> "notfound"
                Resolution.NoPermission -> "nocontacts"
            }
        }
        if (!ContactsHelper.hasPermission(appCtx)) return "nocontacts"
        val all = runCatching { ContactsHelper.loadAll(appCtx) }.getOrDefault(emptyList())
        val exact = all.filter { it.name.equals(t, ignoreCase = true) }
        // FIX-3 F3: consult the remembered pick in save-OFF too — tryPick
        // writes preferences in BOTH modes, but this branch never read
        // them, so every "call Ami" re-asked forever in the default mode.
        // The stored id resolves against a LIVE loadAll (contactById);
        // a deleted/renamed contact falls through to the normal path.
        // The "doosri" clear path still works: tryPick clears the
        // preference and returns Reask before this branch is reached.
        preferences()[normalize(t)]?.let { prefId ->
            contactById(prefId)?.takeIf { normalize(it.name) == normalize(t) }?.let { picked ->
                return if (picked.numbers.size == 1) picked.numbers.first()
                else {
                    storePendingNumber(picked)
                    "multiple_number:" + picked.numbers.indices.joinToString(", ") {
                        "${it + 1} (${picked.labels.getOrNull(it) ?: "mobile"})"
                    }
                }
            }
        }
        if (exact.size > 1) {
            // FIX-1: COUNT, not distinct names — 3 contacts all named
            // "Ami" are 3 options, never one silent dial.
            val options = exact.take(4).mapIndexed { idx, e ->
                ContactOption(e.id, e.name, optionDetail(e, idx))
            }
            storePending(t, options)
            return "multiple:" + options.joinToString(", ") { "${it.name} (${it.detail})" }
        }
        // FIX-3 F2(b): a failed pick must NEVER become a fuzzy dial.
        // "Ami 2" after a tryPick NoPick used to fall into findBest, whose
        // score-30 word fallback matched EVERY Ami and dialled the first
        // in loadAll order — the silent-first-dial failure FIX-1 killed,
        // reintroduced on the pick path. Re-ask ("notfound") instead.
        // Exception: a contact literally named e.g. "Ali 2" (exact
        // full-name match) still resolves by its own name — that is an
        // exact dial, not a fuzzy one.
        if (parsePickContact(t) != null && all.none { normalize(it.name) == normalize(t) }) return "notfound"
        // FIX-3 item 4: without the save allow, a name that is genuinely
        // not found gets the honest code — she says she has not saved
        // contacts and how to turn the save on (§23.4 item 1) — instead
        // of the plain "could not find" line. Callers MUST treat
        // "notfound_nosave" as a non-number, like "notfound".
        val entry = runCatching { ContactsHelper.findBest(appCtx, t) }.getOrNull() ?: return "notfound_nosave"
        if (entry.numbers.size > 1) {
            storePendingNumber(PickedContact(entry.id, entry.name, entry.numbers, entry.labels))
            return "multiple_number:" + entry.numbers.indices.joinToString(", ") {
                "${it + 1} (${entry.labels.getOrNull(it) ?: "mobile"})"
            }
        }
        return runCatching { ContactsHelper.bestNumber(entry) }.getOrNull() ?: "notfound_nosave"
    }

    /** The single resolver for voice AND chat (§23.4). */
    fun resolve(spoken: String): Resolution {
        if (!ContactsHelper.hasPermission(appCtx)) return Resolution.NoPermission
        val norm = normalize(spoken)
        if (norm.isEmpty()) return Resolution.NotFound
        val state = synchronized(lock) { loadState() }
        val contacts = contactsFrom(state)
        if (contacts.isEmpty()) return Resolution.NotFound
        val prefs = state.optJSONObject("prefs") ?: JSONObject()
        // A remembered pick wins — unless he says "doosri" (the other one),
        // which tryPick() handles before resolve() is ever called: it
        // clears the preference and re-asks (FIX-2).
        if (prefs.has(norm)) {
            val picked = contacts.firstOrNull { it.id == prefs.optLong(norm) }
            if (picked != null) {
                return if (picked.numbers.size == 1) Resolution.Exact(picked, picked.numbers)
                else Resolution.ExactMultiNumber(picked, picked.numbers)
            }
        }
        val exact = contacts.filter { it.norm == norm }
        if (exact.size == 1) {
            val c = exact[0]
            return if (c.numbers.size == 1) Resolution.Exact(c, c.numbers)
            else Resolution.ExactMultiNumber(c, c.numbers)
        }
        if (exact.size > 1) {
            // Ambiguous — distinguish by real number label + order, never
            // by speaking/printing digits (§23.2 Fix 5). FIX-2: the
            // options are kept as the pending question so the follow-up
            // pick ("option 2" / "second Ami" / "doosri Ami") can resolve
            // via the stored id — and rememberPick() is actually called.
            val options = exact.take(4).mapIndexed { idx, c ->
                ContactOption(c.id, c.name, optionDetail(c, idx))
            }
            storePending(spoken, options)
            return Resolution.Ambiguous(options)
        }
        // Near-miss: variant table first, then similarity.
        val variants = variantTable[norm].orEmpty()
        val variantHit = contacts.firstOrNull { it.norm in variants }
        if (variantHit != null) return Resolution.NearMiss(variantHit, isWhatsAppContact(spoken))
        val scored = contacts.map { it to levenshtein(norm, it.norm) }
            .filter { (c, d) -> d <= 2 || (c.norm.startsWith(norm.take(4)) && norm.length >= 4) }
            .sortedBy { it.second }
        val best = scored.firstOrNull()
        if (best != null) return Resolution.NearMiss(best.first, isWhatsAppContact(spoken))
        return Resolution.NotFound
    }
}
