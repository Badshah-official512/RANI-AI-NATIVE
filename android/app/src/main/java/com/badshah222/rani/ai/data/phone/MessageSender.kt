package com.badshah222.rani.ai.data.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.badshah222.rani.ai.core.ContactMind
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
            ?: return if (RaniAccessibilityService.isEnabled(ctx)) SendOutcome.Failed("The accessibility service has not started yet — please turn RANI AI on again from Settings > Accessibility.")
            else SendOutcome.NeedsAccessibility
        // ---- resolve the number (on-device only) ----
        // FIX-3: through the single ContactMind resolver — the old code
        // used findBest/bestNumber with no ambiguity check and actually
        // SENT to the first match. An ambiguous name, a multi-number
        // contact or a near-miss now returns the ask string instead of a
        // number, and nothing is sent until the user picks. Digits pass
        // through unchanged.
        val resolved = ContactMind(ctx).resolveToNumber(target)
        if (resolved == "nocontacts") return SendOutcome.NeedsContactsPermission
        if (resolved == "notfound") {
            return SendOutcome.Failed("I could not find \"$target\" in your contacts — please say the name again or tell me the number.")
        }
        // FIX-3 item 4: save-OFF + genuinely not found — the honest line
        // (§23.4 item 1): nothing is saved, and how to turn the save on.
        if (resolved == "notfound_nosave") {
            return SendOutcome.Failed("I have not saved your contacts - turn on 'Save my contacts to RANI's mind' in Settings so I can find names like that. Or say the phone number instead.")
        }
        if (resolved == "fail") {
            return SendOutcome.Failed("I could not understand that contact — please say the name again or tell me the number.")
        }
        if (resolved.startsWith("multiple:")) {
            val opts = resolved.removePrefix("multiple:").replace(Regex(", ([^,]+)$"), " or $1")
            return SendOutcome.Failed("Which $target do you mean — $opts? Tell me the option, like \"$target option 2\".")
        }
        if (resolved.startsWith("multiple_number:")) {
            val opts = resolved.removePrefix("multiple_number:").replace(Regex(", ([^,]+)$"), " or $1")
            return SendOutcome.Failed("Which number for $target — $opts? Tell me, like \"$target mobile\".")
        }
        if (resolved.startsWith("nearmiss:")) {
            val parts = resolved.removePrefix("nearmiss:").split("|")
            val where = if (parts.getOrNull(1) == "whatsapp") "contacts or WhatsApp" else "contacts"
            return SendOutcome.Failed("$target is not in your $where, but ${parts[0]} is — shall I use ${parts[0]}? Reply yes to use it.")
        }
        val rawNumber = resolved
        val name = target
        // ---- open the chat (no pre-filled text — RANI types it herself) ----
        val opened = when (channel.lowercase()) {
            "whatsapp" -> {
                val e164 = ContactsHelper.toE164(ctx, rawNumber)
                    ?: return SendOutcome.Failed("For WhatsApp I need the number with its country code (for example +92...).")
                openUrl(ctx, "https://wa.me/${e164.removePrefix("+")}")
            }
            else -> openSms(ctx, rawNumber)
        }
        if (!opened) return SendOutcome.Failed("I could not open the chat — please check that the app is installed.")
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
            else SendOutcome.Failed("I wrote the message but could not find the Send button — please press Send yourself.")
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
            else SendOutcome.Failed("I could not type the message — the chat is open, please write and send it yourself.")
        }
        delay(400)
        return if (tapSend(svc)) SendOutcome.Sent(name, channel)
        else SendOutcome.Failed("I have written the message — I could not find the Send button, please press Send yourself.")
    }

    private suspend fun tapSend(svc: RaniAccessibilityService): Boolean {
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
