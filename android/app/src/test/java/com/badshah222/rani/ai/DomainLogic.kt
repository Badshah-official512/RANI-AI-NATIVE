package com.badshah222.rani.ai

import com.badshah222.rani.ai.core.FarewellDetector
import com.badshah222.rani.ai.data.ai.Pollinations
import com.badshah222.rani.ai.domain.*

/** Framework-free assertions. JUnit wrappers in DomainLogicTest call these; so does the standalone runner. */
object DomainLogic {
    private var n = 0
    val failures = mutableListOf<String>()
    private fun eq(name: String, actual: Any?, expected: Any?) {
        n++; if (actual != expected) failures += "FAIL $name: expected <$expected> got <$actual>" else println("PASS  $name")
    }
    fun count() = n

    fun runAll() {
        val p = CommandParser::parse
        eq("open youtube", p("Open YouTube"), PhoneAction.OpenYouTube)
        eq("roman urdu youtube", p("youtube kholo"), PhoneAction.OpenYouTube)
        eq("open whatsapp", p("open whatsapp"), PhoneAction.OpenWhatsApp)
        eq("open chrome", p("launch Chrome"), PhoneAction.OpenChrome)
        eq("assistant browser plain", p("browser kholo"), PhoneAction.OpenBrowser(""))
        eq("assistant browser search", p("browser mein cricket search karo"), PhoneAction.OpenBrowser("cricket"))
        eq("open camera", p("open the camera"), PhoneAction.OpenCamera)
        eq("open gallery", p("open photos"), PhoneAction.OpenGallery)
        eq("open arbitrary app", p("open Spotify app"), PhoneAction.OpenApp("spotify"))
        eq("pick contact", p("select a contact"), PhoneAction.PickContact)
        eq("call by name", p("call ali khan"), PhoneAction.Call("Ali Khan"))
        eq("call by number", p("call +92 325 1787969"), PhoneAction.Call("+923251787969"))
        eq("roman urdu call", p("ahmed ko call karo"), PhoneAction.Call("Ahmed"))
        eq("whatsapp msg", p("send whatsapp to Sara saying I'll be late"), PhoneAction.WhatsAppMessage("Sara", "I'll be late"))
        eq("whatsapp msg keeps case", p("WhatsApp Bilal: Meeting at 5 PM"), PhoneAction.WhatsAppMessage("Bilal", "Meeting at 5 PM"))
        eq("sms msg", p("send a text to mom saying call me back"), PhoneAction.Sms("Mom", "call me back"))
        eq("roman urdu sms", p("usman ko sms karo ke main aa raha hun"), PhoneAction.Sms("Usman", "main aa raha hun"))
        eq("alarm 7:30 am", p("set an alarm for 7:30 am"), PhoneAction.SetAlarm(7, 30))
        eq("alarm 6 pm", p("set alarm at 6 pm"), PhoneAction.SetAlarm(18, 0))
        eq("alarm 12 am", p("alarm 12 am"), PhoneAction.SetAlarm(0, 0))
        eq("alarm 24h", p("set alarm 19:45"), PhoneAction.SetAlarm(19, 45))
        eq("alarm invalid hour rejected", p("set alarm 27:00"), null)
        eq("timer minutes", p("set a timer for 5 minutes"), PhoneAction.StartTimer(300))
        eq("timer hours", p("start timer 2 hours"), PhoneAction.StartTimer(7200))
        eq("timer seconds", p("timer 30 seconds"), PhoneAction.StartTimer(30))
        eq("roman urdu timer", p("10 minute ka timer"), PhoneAction.StartTimer(600))
        eq("timer over 24h rejected", p("timer 99 hours"), null)
        eq("web search", p("search for best pizza near me"), PhoneAction.WebSearch("best pizza near me"))
        eq("plain question goes to AI", p("what is the capital of France"), null)
        eq("chit-chat goes to AI", p("how are you today"), null)
        eq("the word 'call' mid-sentence is not a call", p("what does the word call mean"), null)
        eq("empty", p("   "), null)
        eq("confirm: call", PhoneAction.Call("x").needsConfirmation, true)
        eq("confirm: sms", PhoneAction.Sms("x", "y").needsConfirmation, true)
        eq("confirm: whatsapp", PhoneAction.WhatsAppMessage("x", "y").needsConfirmation, true)
        eq("no confirm: open youtube", PhoneAction.OpenYouTube.needsConfirmation, false)
        eq("no confirm: alarm", PhoneAction.SetAlarm(7, 0).needsConfirmation, false)

        eq("open my videos", p("open my videos"), PhoneAction.OpenVideos)
        eq("roman urdu videos", p("meri videos kholo"), PhoneAction.OpenVideos)
        eq("open files", p("open files"), PhoneAction.OpenFiles)
        eq("downloads kholo", p("downloads kholo"), PhoneAction.OpenFiles)

        // ---- Gemini Live voices (the real human voices) ----
        eq("12 live voices in total", LiveVoices.all.size, 12)
        eq("6 male live voices", LiveVoices.male.size, 6)
        eq("6 female live voices", LiveVoices.female.size, 6)
        eq("every live voice has a style", LiveVoices.all.all { it.style.isNotBlank() }, true)
        eq("male list has no female voice", LiveVoices.male.none { it.female }, true)
        eq("female list is all female", LiveVoices.female.all { it.female }, true)
        eq("live voice lookup by name", LiveVoices.fromName("aoede").name, "Aoede")
        eq("live voice lookup unknown falls back to first male", LiveVoices.fromName("nope"), LiveVoices.male.first())
        eq("voice info keeps engine data", VoiceInfo("en-us-x-iom-local", "en-US", false, 400).localeTag, "en-US")

        // ---- new emotions ----
        eq("38 emotions", Emotions.all.size, 38)
        eq("7 emotion families", Emotions.families.size, 7)
        eq("every family has emotions", Emotions.families.all { Emotions.byFamily(it).isNotEmpty() }, true)
        eq("emotion lookup fallback is warm", Emotions.fromId("nope").id, "warm")
        eq("sad words => comforting", Emotions.detect("i am sad today").id, "comforting")
        eq("gussa => calm", Emotions.detect("mujhe gussa aa raha hai").id, "calm")
        eq("shukriya => grateful", Emotions.detect("shukriya bhai").id, "grateful")
        eq("success => celebratory", Emotions.detect("ho gaya kaam").id, "celebratory")
        eq("neend => sleepy", Emotions.detect("mujhe neend aa rahi hai").id, "sleepy")
        eq("plain text => warm", Emotions.detect("what is the capital of France").id, "warm")
        eq("provider lookup falls back to Gemini", Provider.fromId("nonsense"), Provider.GEMINI)
        eq("provider lookup groq", Provider.fromId("groq"), Provider.GROQ)
        eq("unknown provider id falls back to Gemini", Provider.fromId("fusion"), Provider.GEMINI)
        eq("live models default key is 3.1", LiveModels.DEFAULT_KEY, "3.1")
        eq("live models are four", LiveModels.all.size, 4)
        eq("female voices are six, Aoede default", LiveVoices.female.size, 6)
        eq("default live voice is Aoede", LiveVoices.female.first().name, "Aoede")
        eq("languages are 148", RaniLanguages.all.size, 148)
        eq("free image url contains the prompt", Pollinations.imageUrl("golden crown").contains("golden%20crown"), true)
        eq("battery", p("battery kitni hai"), PhoneAction.DeviceInfo("battery"))
        eq("storage", p("storage kitna hai"), PhoneAction.DeviceInfo("storage"))
        eq("time", p("time kya hai"), PhoneAction.DeviceInfo("time"))
        eq("vol-up", p("volume barhao"), PhoneAction.Volume("up"))
        eq("vol-down", p("awaz kam karo"), PhoneAction.Volume("down"))
        eq("screen", p("meri screen dekho"), PhoneAction.OpenScreen)

        // ---- v2.0.0 §22.3 farewell detector (mirrors detectFarewell() in
        // rani-live.html — same phrases, same three rules, same guards) ----
        val f = FarewellDetector::detect
        eq("farewell: good night", f("good night", false), "good night")
        eq("farewell: bas kardo", f("bas kardo", false), "bas kardo")
        eq("farewell: cut kardo", f("cut kardo", false), "cut kardo")
        eq("farewell: ok good night", f("ok good night", false), "good night")
        eq("farewell: allah hafiz", f("Allah Hafiz", false), "allah hafiz")
        eq("farewell: final clause is bye", f("Allah Hafiz, bye", false), "bye")
        eq("farewell: final clause", f("ok bas kardo, good night", false), "good night")
        eq("farewell: repeat fires", f("good night", true), "good night")
        eq("farewell: story does not fire", f("us ne mujhe good night kaha tha, phir kya hua", false), null)
        eq("farewell: question does not fire", f("good night ka matlab kya hai?", false), null)
        eq("farewell: hypothetical does not fire", f("agar main good night kahun to kya hoga", false), null)
        eq("farewell: long turn does not fire", f("aaj main bohat thak gaya hoon aur kal subah jaldi uthna hai, good night kehna tha bas", false), null)
        eq("farewell: morning is not farewell", f("good morning, aaj kya karna hai", false), null)
        eq("farewell: goodbye is goodbye", f("goodbye", false), "goodbye")
        eq("farewell: assistant goodbye", FarewellDetector.isGoodbye("Good night, Babu Jaan, sleep well."), true)
        eq("farewell: assistant plain talk is not goodbye", FarewellDetector.isGoodbye("Tell me more about your day."), false)
    }
}
