package com.badshah222.rani.ai.data.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.badshah222.rani.ai.core.RaniAccessibilityService
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * MESSAGE TYPE + SEND (spec §14.1, §3.11) — RANI writes the message and sends
 * it with the accessibility full-control service: open the chat, type the text
 * into the message box, tap Send. Only runs after the user confirmed the
 * message in the app, and only when the user enabled Accessibility himself.
 * Contacts are resolved on-device only (ContactsHelper) — never uploaded.
 */
sealed class SendOutcome {
    data class Sent(val contactName: String, val channel: String) : SendOutcome()
    object NeedsAccessibility : SendOutcome()
    object NeedsContactsPermission : SendOutcome()
    data class Failed(val reason: String) : SendOutcome()
}

object MessageSender {
    private const val TAG = "MessageSender"

    suspend fun send(ctx: Context, channel: String, target: String, message: String): SendOutcome {
        val svc = RaniAccessibilityService.instance
            ?: return if (RaniAccessibilityService.isEnabled(ctx)) SendOutcome.Failed("Accessibility service abhi start nahi hui — ek dafa Settings se dobara ON karo, Babu Jaan.")
            else SendOutcome.NeedsAccessibility
        // ---- resolve the number (on-device only) ----
        val digitsOnly = target.matches(Regex("^[+]?[0-9][0-9 \\-]{5,}$"))
        val entry = if (digitsOnly) null else ContactsHelper.findBest(ctx, target)
        if (!digitsOnly && entry == null) {
            return if (!ContactsHelper.hasPermission(ctx)) SendOutcome.NeedsContactsPermission
            else SendOutcome.Failed("\"$target\" contacts mein nahi mila, Babu Jaan — naam dobara bolo ya number batao.")
        }
        val rawNumber = if (digitsOnly) target.replace(" ", "").replace("-", "") else ContactsHelper.bestNumber(entry!!)!!
        val name = entry?.name ?: rawNumber
        // ---- open the chat (no pre-filled text — RANI types it herself) ----
        val opened = when (channel.lowercase()) {
            "whatsapp" -> {
                val e164 = ContactsHelper.toE164(ctx, rawNumber)
                    ?: return SendOutcome.Failed("WhatsApp ke liye number country code ke saath chahiye (jaise +92...), Babu Jaan.")
                openUrl(ctx, "https://wa.me/${e164.removePrefix("+")}")
            }
            else -> openSms(ctx, rawNumber)
        }
        if (!opened) return SendOutcome.Failed("Chat nahi khul saki, Babu Jaan — app installed hai na check karo.")
        // ---- wait for the message box (max ~8s) ----
        var waited = 0
        while (waited < 8000) {
            if (svc.hasInputField()) break
            delay(500); waited += 500
        }
        if (!svc.hasInputField()) {
            // Fallback: reopen with the text pre-filled, then only tap Send.
            reopenWithText(ctx, channel, rawNumber, message)
            delay(1200)
            return if (tapSend(svc)) SendOutcome.Sent(name, channel)
            else SendOutcome.Failed("Message likh diya lekin Send button nahi mila, Babu Jaan — aap khud Send dabao.")
        }
        // ---- type the message ----
        var typed = svc.typeText(message)
        if (typed != "ok") {
            delay(700)
            typed = svc.typeText(message)
        }
        if (typed != "ok") {
            reopenWithText(ctx, channel, rawNumber, message)
            delay(1200)
            return if (tapSend(svc)) SendOutcome.Sent(name, channel)
            else SendOutcome.Failed("Message type nahi ho saka, Babu Jaan — chat khula hai, aap khud likh kar bhej dein.")
        }
        delay(400)
        return if (tapSend(svc)) SendOutcome.Sent(name, channel)
        else SendOutcome.Failed("Message likh diya hai, Babu Jaan — Send button nahi mila, aap khud Send dabao.")
    }

    private fun tapSend(svc: RaniAccessibilityService): Boolean {
        if (svc.tapSendButton() == "ok") return true
        delay(500)
        return svc.tapText("send") == "ok"
    }

    private fun openUrl(ctx: Context, url: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    } catch (e: Exception) { false }

    private fun openSms(ctx: Context, number: String): Boolean = try {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    } catch (e: Exception) { false }

    private fun reopenWithText(ctx: Context, channel: String, number: String, message: String) {
        try {
            if (channel.lowercase() == "whatsapp") {
                val e164 = ContactsHelper.toE164(ctx, number) ?: return
                openUrl(ctx, "https://wa.me/${e164.removePrefix("+")}?text=" + URLEncoder.encode(message, "UTF-8"))
            } else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        } catch (e: Exception) { /* best effort — caller reports the failure */ }
    }
}
