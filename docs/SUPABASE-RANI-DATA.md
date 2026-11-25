# RANI AI — Supabase data contract (for the RANI admin panel)

RANI AI writes to the **shared BADSHAH Supabase project** ("Badshah ai
voice assistant"). This document lists **exactly** what the RANI Android
app reads and writes, so the separate RANI admin panel
(`badshah222-ai.pages.dev/Ranipanal`) can read the same rows. Table and
column names only — no keys, no values, no secrets.

The panel's migration is **015**
(`20261125000015_rani_admin.sql`): it adds the `app` marker, the RANI-only
tables, and the real `app_settings` schema below. **Until migration 015
lands, the `rani_*` tables and the `app` column do not exist** — every
RANI-only write in the app is defensive (a missing table/column is logged
and skipped, never a crash), and shared-table writes retry without the
`app` tag. Once the migration is live, the tag takes effect.

## The `app` marker (how RANI rows are distinguished from BADSHAH rows)

- Every direct write to a **shared** table includes **`app` TEXT =
  `'rani'`** — but ONLY when the column exists.
- The app sends the tag optimistically; if PostgREST answers "column `app`
  does not exist" (PGRST204), the app retries the same write **without**
  the tag and remembers the column is missing for the rest of the session.
  Writes never hard-crash on a missing column.
- RPCs take **`p_app => 'rani'`** (last parameter, default `'badshah'`).
  When the old function signature is still live, PostgREST rejects the
  extra argument and the app retries the same call **without** `p_app`.

## Tables RANI writes (direct PostgREST — shared, app-tagged)

| Table | Columns the app writes | Notes |
|---|---|---|
| `conversations` | `user_id`, `title`, `ai_provider`, `model_name` (+ `app` when present) | One row per chat; `updated_at` is patched on every new message. Read: `id, title, updated_at` (list, latest 100). |
| `messages` | `conversation_id`, `user_id`, `role`, `content`, `ai_provider`, `model_name` (+ `app` when present) | Chat + Live-call turns. Read: `role, content` (per conversation, latest 500). |
| `support_tickets` | `user_id`, `subject`, `category`, `message` (+ `app` when present) | Created from Help Center. |
| `support_messages` | `ticket_id`, `sender_type` (`'user'`), `message` (+ `app` when present) | User replies on a ticket. |

## Tables RANI writes (RANI-only — no `app` column, defensive writes)

| Table | Columns the app writes | Notes |
|---|---|---|
| `rani_preferences` | `user_id`, `selected_provider`, `selected_model`, `selected_voice`, `voice_engine`, `speech_rate`, `speech_pitch`, `language`, `personality`, `memory_enabled`, `updated_at` | Upsert on `user_id`. `selected_model` / `selected_voice` are omitted when unset so the panel default survives. **Never** `user_preferences` (shared row — BADSHAH is closed). No API keys — status only. |
| `rani_provider_settings` | `user_id`, `provider`, `is_configured`, `connection_status`, `last_tested_at`, `updated_at` | Upsert on (`user_id`, `provider`). **Never** `user_provider_settings`. Key status only, never the key — and never `key_fingerprint`. |
| `rani_activity` | `user_id`, `event_type`, `metadata` (jsonb) | **Every user action, metadata only — NEVER message content, contacts data, recordings, or raw voice.** `event_type` is one of: `chat`, `voice_call`, `voice_minutes`, `command`, `youtube_search`, `message_sent`, `contacts_read`, `screen_recording`, `camera_event`, `face_recognition`, `voiceprint_enrol`, `face_lock`, `safety_alert`, `awareness`. |
| `rani_screen_recordings` | `user_id`, `file_name`, `duration_sec`, `size_bytes` | **Metadata only** — the MP4 file itself stays on the phone. Written when a recording stops. |
| `rani_voiceprints` | `user_id`, `model_version`, `is_active` (enrol); `last_verified_at` (verify) | **Metadata only** — raw voice never leaves the phone. Written on enrolment; the latest row's `last_verified_at` is patched on a successful verification. |

## Tables RANI writes (via RPCs — with `p_app => 'rani'`)

| RPC | Writes to | Arguments |
|---|---|---|
| `app_touch_profile` | `profiles` | `p_email`, `p_display_name`, `p_avatar_url`, `p_app`. Returns `is_blocked`. |
| `app_record_login` | `login_events` | `p_provider`, `p_event_type`, `p_device` (`'android'`), `p_app`. |
| `app_record_usage` | `ai_usage_events` | `p_provider`, `p_model`, `p_event_type`, `p_success`, `p_input_tokens`, `p_output_tokens`, `p_latency_ms`, `p_error_code`, `p_app`. Best-effort only. |

## Tables RANI reads (never writes)

| Table | Columns read | Purpose |
|---|---|---|
| `app_settings` | `setting_key`, `setting_value` where `app = 'rani'` | **Remote feature config — the panel writes this, the app reads it.** See below. RLS: SELECT to authenticated users only (NOT anon) — the read is fail-soft to defaults when signed out. |
| `app_config` | `config_value` where `config_key = 'maintenance'` | Fallback maintenance banner (used only when `app_settings` has no `maintenance` group). |
| `announcements` | `title`, `content` where `is_published = true` | Fallback announcements (used only when `app_settings` has no `announcement` group). |
| `voice_profiles` | `id`, `label`, `category`, `pitch`, `speed`, `language` | Admin-defined voice profiles (display only). |

## `app_settings` — remote config the panel controls

**Real schema (migration 015):** columns `app TEXT`,
`setting_key TEXT`, `setting_value JSONB`, `updated_at`. One row per
group for `app = 'rani'`. (An earlier draft of this doc described a
`(app, key, value)` schema — that was wrong and is superseded by this.)

| `setting_key` | `setting_value` (JSON object) | Panel seed | What it controls |
|---|---|---|---|
| `features` | `{"awareness": bool, "camera": bool, "screen_recording": bool, "voiceprint_gate": bool, "floating_orb": bool}` | all `true` | Feature switches. Remote wins when set; the user's own toggle still applies when the panel has not set the key. |
| `persona` | `{"default_voice": "Aoede", "tone": "loving", "call_greeting": "G Babu"}` | as shown | Default female Live voice; default emotion tone (an emotion id); what RANI says first on a call (voice-only, never on screen). |
| `gemini` | `{"model": "gemini-3.8-flash"}` | as shown | Default Gemini model when the user has not picked one. |
| `maintenance` | `{"enabled": bool, "message": "..."}` | disabled | Maintenance banner (checked before the legacy `app_config` table). |
| `announcement` | `{"enabled": bool, "title": "...", "body": "..."}` | disabled | Home announcement (checked before the legacy `announcements` table). |

The app reads `app_settings` at start and on Home start (`RaniRemoteConfig.refresh()`)
and caches the last good values locally. Fail-soft: if the table does not
exist yet, the read returns nothing and the app uses its on-device defaults.

## What NEVER leaves the phone (the panel must not expect these rows)

- Gemini API key (encrypted on-device only), voice-print vectors,
  face-print vectors, contacts, photos, camera frames, screen recordings,
  call audio, message content.
- Voice-print and face-print profiles live in EncryptedSharedPreferences
  on the device. The panel sees only the metadata rows above
  (`rani_voiceprints`, `rani_activity` with `voiceprint_enrol` /
  `face_recognition` / `face_lock` events) — there is no server-side copy
  of any print.

## Code pointers (app side)

- `data/supabase/SupabaseApi.kt` — all reads/writes, `APP_TAG = "rani"`,
  `postTable` (shared, optimistic tag + retry), `postRaniTable`
  (RANI-only, defensive), `callRpcWithApp` (try `p_app`, retry without),
  `recordActivity` / `recordScreenRecording` / `recordVoiceprintEnrolled` /
  `recordVoiceprintVerified`, `fetchAppSettings(app)`.
- `core/RaniRemoteConfig.kt` — cached remote config + effective values
  (`awarenessOn`, `cameraEnabled`, `screenRecordingOn`, `voicePrintGateOn`,
  `floatingOrbOn`, `defaultVoice`, `tone`, `callGreeting`, `geminiModel`,
  `maintenance`, `announcement`).
- `core/SecureStore.kt` — on-device settings that the remote keys override.
