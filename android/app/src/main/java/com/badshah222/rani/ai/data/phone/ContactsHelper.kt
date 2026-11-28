package com.badshah222.rani.ai.data.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Reads ALL phone contacts — on-device only, never uploaded, never leaves
 * the phone (owner rule, spec §14). Used so RANI can call / message anyone
 * by name, and for the Contacts screen. Nothing here sends data anywhere.
 */
data class ContactEntry(val id: Long, val name: String, val numbers: List<String>, val labels: List<String> = emptyList())

object ContactsHelper {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** All contacts with at least one number, sorted by name. Cap 5000 (safety).
     *  FIX-2 (v3.0.0 §23.4): also captures the REAL per-number label from
     *  Phone.TYPE (mobile/home/work/…) — the old code read no type at all,
     *  so ContactMind's import had to fabricate "mobile" for every first
     *  number and ambiguous options were indistinguishable. */
    fun loadAll(ctx: Context): List<ContactEntry> {
        if (!hasPermission(ctx)) return emptyList()
        val map = LinkedHashMap<Long, MutableList<String>>()
        val labMap = LinkedHashMap<Long, MutableList<String>>()
        val names = HashMap<Long, String>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                val idI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (c.moveToNext() && map.size < 5000) {
                    val id = c.getLong(idI)
                    val name = c.getString(nameI)?.trim().orEmpty()
                    val num = c.getString(numI)?.trim().orEmpty()
                    if (name.isEmpty() || num.isEmpty()) continue
                    names[id] = name
                    val list = map.getOrPut(id) { mutableListOf() }
                    val labs = labMap.getOrPut(id) { mutableListOf() }
                    if (!list.contains(num)) {
                        list.add(num)
                        labs.add(labelFor(typeI, labelI, c))
                    }
                }
            }
        }
        return map.entries.map { (id, nums) -> ContactEntry(id, names[id] ?: "", nums, labMap[id] ?: emptyList()) }
            .filter { it.name.isNotEmpty() }
    }

    /** Real label for one phone row: the stored TYPE (mobile/home/work/…),
     *  or the custom label for TYPE_CUSTOM, lowercased. Never invented. */
    private fun labelFor(typeI: Int, labelI: Int, c: android.database.Cursor): String {
        if (typeI < 0) return "mobile"
        return when (c.getInt(typeI)) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "work mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
            ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> "company"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM ->
                c.getString(labelI)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "other"
            else -> "other"
        }
    }

    /** Best match for a spoken name: exact > starts-with > contains > word-prefix. */
    fun findBest(ctx: Context, query: String): ContactEntry? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        var best: ContactEntry? = null
        var bestScore = -1
        for (c in loadAll(ctx)) {
            val n = c.name.lowercase()
            val score = when {
                n == q -> 100
                n.startsWith(q) -> 80
                n.contains(q) -> 60
                n.split(" ").any { it.startsWith(q) } -> 40
                q.split(" ").all { w -> w.length < 2 || n.contains(w) } -> 30
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = c }
            if (score == 100) break
        }
        return best
    }

    fun bestNumber(entry: ContactEntry): String? = entry.numbers.firstOrNull()

    fun toE164(ctx: Context, raw: String): String? {
        val digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+")) return digits
        val iso = (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso?.uppercase().orEmpty()
        return if (iso.isNotEmpty()) PhoneNumberUtils.formatNumberToE164(digits, iso) else null
    }
}
