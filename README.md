# RANI AI GF — Native Android App

**RANI AI GF** — your virtual AI girlfriend. A native Kotlin + Jetpack Compose
Android app (Material 3, dark purple-neon design). She talks like a real
human girlfriend, calls you **Babu Jaan**, remembers you, builds websites and
code, controls your phone by voice, and reads your contacts by name.

> BADSHAH AI is a separate, closed project. RANI AI GF is new, native-only
> (no WebView, no website dependency), Android only.

## What she does (MYRA behaviour on a BADSHAH base)

MYRA Ultra (MIT open-source) is the behaviour source — what MYRA *does*,
ported into Kotlin: chat, Live voice, vision, Coder, WebForge website builder,
AI Tools, memory, routines, files, media, web search, system monitor, screen
guardian. The BADSHAH AI project is the Android base underneath: auth
(Supabase + Firebase), database, permissions, Android skeleton.

- **Chat + Live voice** — Gemini Live, female voices only (Aoede default, +
  Kore, Zephyr, Leda, Sulafat, Callirrhoe). Session resumption: a dropped call
  picks up where it left off, no "Hello" again.
- **Message type + send** — "Ali ko message likho aur bhejo: …" — RANI types
  the message and taps Send herself (accessibility, only after you confirm
  the exact text). Without Accessibility she opens the chat and you press send.
- **YouTube searching** — "YouTube par lo-fi search karo" opens YouTube search.
- **Read ALL contacts** — RANI reads every contact on the phone to call or
  message anyone by name. Contacts stay on-device: never uploaded, never leave
  the phone (same rule as accessibility).
- **WebForge** — describe a website, get a complete HTML site: preview,
  download, deploy to GitHub (your own token).
- **Coder** — interactive coding session: complete files as tasks, approve,
  download a ZIP. Attach a file with @file.
- **AI Tools** — Generate Image (Gemini image, free Pollinations fallback),
  Summarize, Translate, Write Code, Create PDF, Analyze File, Plan My Day.
- **Memory** — special days (birthdays, anniversaries — she wishes you first),
  favourites, "Sab bhool jao".
- **Automation** — tasks/reminders + 6 routines (Good Morning, Focus, Meeting,
  Night, Daily Summary, Clean Up) with volume/brightness actions.
- **Screen Guardian** — watches only the screen you share, warns by voice on
  password / bank / OTP. Never reads passwords out loud.
- **System Monitor** — CPU / RAM / Disk / Battery gauges, top processes + kill.

## Your own keys (BYOK)

RANI has **no built-in key**. You enter your own free Gemini key in
Settings → Connections → Test & save (encrypted on-device). Chat, Live voice,
image generation and vision all run on your key. Google login runs on the
shared Firebase project; email/password + Google sign-in work with the same
account as BADSHAH AI.

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
    core/                 # SecureStore, services (orb, screen share, accessibility)
    data/                 # ai (Gemini/Groq/OpenRouter, Pollinations), auth, phone, supabase, voice
    domain/               # PhoneAction, CommandParser, LiveModels, LiveVoices, RaniLanguages, RaniTools
    features/             # home, chat, voice, settings, tools, webforge, coder, files, media,
                          #   search, monitor, automation, memory, contacts, guardian, permissions…
    ui/                   # AppNav, theme, orb
docs/SETUP-SECRETS.md     # which GitHub Secrets are needed (names only)
upload-via-browser/       # binaries + workflow the connector cannot push (see its README)
```

## Credits

- Created by **BADSHAH222** (Zain Shah, Swabi, Pakistan).
- Behaviour inspired by MYRA Ultra (MIT License) — see
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). RANI's code is its own.
