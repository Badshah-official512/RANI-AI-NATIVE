package com.badshah222.rani.ai.domain

/** Everything the assistant can ask the phone to do. Pure data: no Android types. */
sealed class PhoneAction {
    data class OpenApp(val appName: String) : PhoneAction()
    object OpenYouTube : PhoneAction()
    object OpenScreen : PhoneAction()
    data class DeviceInfo(val kind: String) : PhoneAction() // battery | storage | time | date
    data class Volume(val dir: String) : PhoneAction() // up | down | mute
    object OpenWhatsApp : PhoneAction()
    object OpenChrome : PhoneAction()
    object OpenCamera : PhoneAction()
    object OpenGallery : PhoneAction()
    object OpenVideos : PhoneAction()
    object OpenFiles : PhoneAction()
    object PickContact : PhoneAction()
    data class Call(val target: String) : PhoneAction()
    data class WhatsAppMessage(val target: String, val message: String) : PhoneAction()
    data class Sms(val target: String, val message: String) : PhoneAction()
    /** Type + send via accessibility full control (spec §14.1): RANI types the
     *  message into the chat box and taps Send. Only after user confirmation,
     *  and only when Accessibility is ON (user enabled it himself). */
    data class AutoSendMessage(val target: String, val message: String, val channel: String) : PhoneAction() // channel: whatsapp | sms
    /** YouTube search (spec §14.2): opens YouTube with the search results. */
    data class YouTubeSearch(val query: String) : PhoneAction()
    /** Open RANI's Contacts screen (all on-device contacts, spec §14.3). */
    object OpenContacts : PhoneAction()
    data class SetAlarm(val hour24: Int, val minute: Int, val label: String? = null) : PhoneAction()
    data class StartTimer(val seconds: Int, val label: String? = null) : PhoneAction()
    data class WebSearch(val query: String) : PhoneAction()
    /** The assistant's OWN in-app browser (empty query = just open it). Handled by the UI layer, not the executor. */
    data class OpenBrowser(val query: String) : PhoneAction()
    data class Flashlight(val on: Boolean) : PhoneAction()

    /** Calls and messages are never sent silently: the user must confirm first.
     *  Auto-send (type + send) also confirms — the confirmation dialog shows
     *  the exact message before RANI types it. */
    val needsConfirmation: Boolean
        get() = this is Call || this is WhatsAppMessage || this is Sms || this is AutoSendMessage
}
