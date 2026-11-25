# Setup — GitHub Secrets (names only, never values)

RANI AI GF is a **public** repo. Nothing secret is committed: no keystore, no
`google-services.json`, no API key. CI injects everything from GitHub Secrets
(repo → Settings → Secrets and variables → Actions → New repository secret).

The debug APK builds **without any of these** (placeholder
`google-services.json` + placeholder Supabase values), so the first green
build needs nothing. Each secret unlocks one real capability:

| Secret name | What it is | Needed for |
|---|---|---|
| `GOOGLE_SERVICES_JSON` | The whole `google-services.json` file for package `com.badshah222.rani.ai`, base64-encoded. Get it from the Firebase console after registering the Android app (project `badshah-ai-badshah222`). | Firebase Auth, Google sign-in, FCM, Crashlytics |
| `SUPABASE_URL` | Supabase project URL (the shared "Badshah ai voice assistant" project). | Login, cloud sync, admin panel |
| `SUPABASE_ANON_KEY` | Supabase publishable/anon key. (CI maps it to the Gradle property `SUPABASE_PUBLISHABLE_KEY`.) | Same as above |
| `ANDROID_KEYSTORE_BASE64` | The shared BADSHAH release keystore file, base64-encoded. **Same keystore as BADSHAH AI** — no new keystore. | Signed release APK |
| `KEYSTORE_PASSWORD` | Password of that keystore. | Signed release APK |
| `KEY_ALIAS` | Key alias inside that keystore. | Signed release APK |
| `KEY_PASSWORD` | Password of that key. | Signed release APK |

## How the workflow uses them

- `GOOGLE_SERVICES_JSON` → decoded to `android/app/google-services.json`
  before the build. If the secret is missing, a well-formed placeholder is
  written instead (Firebase login won't work in that build, everything else
  will).
- `SUPABASE_URL` / `SUPABASE_ANON_KEY` → written into
  `android/gradle.properties` before the build (overriding the placeholders
  committed there).
- Keystore secrets → decoded to `android/app/rani-release.keystore` +
  `android/keystore.properties`; the signed release APK is built only when
  all four are present.

## Local development

- Copy `google-services.json.example` to
  `android/app/google-services.json` and fill in the real values **only on
  your own machine** — it is gitignored and must never be committed.
- Put real Supabase values in `android/gradle.properties` locally if you
  want cloud features while developing (that file's committed values are
  placeholders; keep real ones out of git).
- Release signing locally: create `android/keystore.properties` with
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (also gitignored).

## The user's own Gemini key is NOT a secret here

Each user enters their own free Gemini key inside the app
(Settings → Connections → Test & save). It is stored encrypted on their phone
only. It never appears in the repo, in CI, or in any file.
