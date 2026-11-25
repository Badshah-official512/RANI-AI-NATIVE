# RANI AI GF — Native Android App

**RANI AI GF** — your virtual AI girlfriend. A native Kotlin + Jetpack
Compose Android app (Material 3, dark holographic design). She talks like
a real human girlfriend, calls you **Babu Jaan** (in voice), remembers
you, builds websites and code, controls your phone by voice, and reads
your contacts by name.

> BADSHAH AI is a separate, closed project. RANI AI GF is new, native-only
> (no WebView, no website dependency), Android only. Free model — you bring
> your own free Gemini key (BYOK); there is no paid plan.

## The character

RANI is the **faceless glowing holographic girl-form** — RANI's own clean
version of the approved Stonic video frame (no TikTok watermark, no Stonic
text). She is always shown **full-figure, figure-shaped, never circular**:
a round crop hides the figure and leaves only a blue ball. Small avatar
spots use the head/shoulders crop in a rounded rectangle. Launcher icon =
a close crop of the figure's upper part (head/shoulders + glow), so the
character stays recognisable. The old poster is removed and must not be
used anywhere.

## UI/UX — Stonic style

The whole UI follows Stonic (StonicAi.com) — see
`STONIC-FULL-DETAILS.md` in the goal docs. One scrolling home page, no
tabs, no bottom bar:

- **Top bar** — RANI AI GF, Floating control, Awareness toggle (ON by
  default), avatar.
- **Centre** — the full-figure character (tap to talk) + status bar
  (Listening / Speaking / Thinking / Working / Ready) + emotion chip.
- **Four Core Circuits** — MEMORY (cyan), SKILLS (green), CHARACTER
  (red), SETTING (grey), each joined to the figure by its colour's
  connector line.
- **Visual Hub** — "What do you want to see?" Draw + explain, Gesture tab.
- **Your World** — globe + "Three things for you today" daily briefing.
- **VOICE | NOTES** — SYSTEM READY + mic, and your saved memories.
- Command bar + quick actions (Camera View, Visual Hub, Your World,
  Recordings, AI Tools, WebForge, Coder, Contacts, …).

UI text is **English only** — not a single Urdu word on screen. Voice
languages are Urdu / Pashto / English, and "Babu Jaan" is voice-only.

## What she does (Stonic's features + RANI extras)

Stonic's features, on Android, free: push-to-talk + hands-free voice,
tone/volume/speed, ~50 commands, WhatsApp dictation + send (confirmation
first), Visual Hub, Your World briefing, Awareness (ON by default, with a
clear toggle), camera (one still photo at a time, face recognition +
visual memories), long-term memory + My Memories, reminders/timers/alarms,
weekly routine, Skills / phone agent (screenshot → decide → open/click/
type/search), trainable skills, confirmation before dangerous actions,
Face Lock (encrypted on-device, OFF by default), local-by-default.

RANI extras **beyond Stonic** (Stonic does not claim these):

- **Camera View screen** — live front-camera video: "AI kya dekh raha
  hai" — what RANI sees, with her live description of the scene.
- **Screen recording** — what RANI sees, she saves as a screen recording
  (on-device only, view/delete in Recordings; never leaves the phone).
- **Voice-print memory** — once your voice is enrolled, RANI only talks
  to you (voice recognition gates voice sessions; camera recognises you
  too). On-device, encrypted, never sent anywhere.
- **Decisions + emotions** — RANI decides herself: sadness, anger,
  happiness, affection. She greets you, celebrates, comforts, jokes.
- **Phone safety without your permission** — she protects the phone on
  her own (suspicious app/activity warnings, Face Lock for other faces).
- **Always-on** — floating orb on screen anytime; a voice call ("G Babu")
  makes her appear and answer.

Plus the base feature set, all on-device where it matters:

- **Chat + Live voice** — Gemini Live, female voices only (Aoede default,
  + Kore, Zephyr, Leda, Sulafat, Callirrhoe). Session resumption: a dropped
  call picks up where it left off, no "Hello" again.
- **Message type + send** — after you confirm the exact text, RANI types
  the message and taps Send herself (accessibility). Without Accessibility
  she opens the chat and you press send.
- **YouTube searching** — search YouTube by voice.
- **Read ALL contacts** — RANI reads every contact on the phone to call
  or message anyone by name. Contacts stay on-device: never uploaded,
  never leave the phone.
- **WebForge** — describe a website, get a complete HTML site: preview,
  download, deploy to GitHub (your own token).
- **Coder** — interactive coding session: complete files as tasks,
  approve, download a ZIP. Attach a file with @file.
- **AI Tools** — Generate Image (Gemini image, free Pollinations
  fallback), Summarize, Translate, Write Code, Create PDF, Analyze File,
  Plan My Day.
- **Memory** — special days (birthdays, anniversaries — she wishes you
  first), favourites, "Forget everything".
- **Automation** — tasks/reminders + 6 routines (Good Morning, Focus,
  Meeting, Night, Daily Summary, Clean Up).
- **Screen Guardian** — watches only the screen you share, warns by voice
  on password / bank / OTP. Never reads passwords out loud.
- **System Monitor** — CPU / RAM / Disk / Battery gauges, top processes.

## Your own keys (BYOK)

RANI has **no built-in key**. You enter your own free Gemini key in
Settings → Connections → Test & save (encrypted on-device). Chat, Live
voice, image generation and vision all run on your key. Google login runs
on the shared Firebase project; email/password + Google sign-in work with
the same account as BADSHAH AI.

## Data — RANI admin panel

RANI's records are kept separate from BADSHAH's in the shared Supabase
project: every shared-table write carries `app: "rani"`, RPCs take
`p_app => 'rani'`, preferences live in `rani_preferences` /
`rani_provider_settings`, and every user action is logged (metadata only)
to `rani_activity`. Feature switches, persona, Gemini model, maintenance
and announcements come from `app_settings` (app = `'rani'`), controlled
from the RANI admin panel at `badshah222-ai.pages.dev/Ranipanal`. Full
contract: [docs/SUPABASE-RANI-DATA.md](docs/SUPABASE-RANI-DATA.md).
Writes are defensive until the panel's migration 015 lands — the app
keeps working with or without it.

## Build

```bash
cd android
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

CI builds the debug APK on every push (see `.github/workflows/android-build.yml`).
Secrets are injected at build time — see [docs/SETUP-SECRETS.md](docs/SETUP-SECRETS.md).
No keystore, no `google-services.json`, no real key is committed to this repo.

## Project layout

```
android/                  # the app (Kotlin + Jetpack Compose, AGP 8.7.3)
  app/src/main/java/com/badshah222/rani/ai/
    core/                 # SecureStore, services (orb, screen share, accessibility),
                          #   Awareness, remote config, screen recording
    data/                 # ai (Gemini/Groq/OpenRouter, Pollinations), auth, phone,
                          #   supabase, voice, identity (voice-print, face-print)
    domain/               # PhoneAction, CommandParser, LiveModels, LiveVoices, RaniLanguages, RaniTools
    features/             # home (Stonic page), chat, voice, settings, camera, visualhub,
                          #   world, recordings, tools, webforge, coder, files, media,
                          #   search, monitor, automation, memory, contacts, guardian, permissions…
    ui/                   # AppNav (no bottom bar), theme, orb, character, stonic (design system)
docs/                     # SETUP-SECRETS.md (secret names only), SUPABASE-RANI-DATA.md
upload-via-browser/       # binaries the connector cannot push (see its README)
```

## Credits

- Created by **BADSHAH222** (Zain Shah, Swabi, Pakistan).
- Behaviour inspired by MYRA Ultra (MIT License) — see
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). RANI's code is its own.
- UI/UX inspired by Stonic (StonicAi.com) — style only, not a copy; RANI
  is Android, free/BYOK, and more powerful than Stonic.
