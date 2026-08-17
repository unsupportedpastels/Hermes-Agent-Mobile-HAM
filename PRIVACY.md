# Privacy policy

**Effective date:** August 15, 2026

HAM (Hermes Agent Mobile) is an Android client for a Hermes Agent server selected and controlled by the user.

## Data HAM handles

To operate, HAM may handle:

- the server address you choose;
- authentication cookies or tokens issued by that server;
- project, session, prompt, response, tool-status, and transcript data returned by that server; and
- files or images you explicitly attach for upload to that server.

HAM stores connection and session metadata locally on your device, scoped to the normalized server origin, selected profile, and durable session ID. This metadata cache is bounded, expires after 30 days, and may be shown with a cached/offline marker until Hermes Serve reconciles it. Authentication material is stored using Android-backed encrypted storage. Fresh WebSocket tickets remain in memory only.

Transcript tails are not stored unless you opt in under Settings. Opted-in transcript tails are encrypted with an Android Keystore-backed key and bounded to 200 messages per session, 128 KiB per message body, 100 sessions, and 4 MiB total. Tails associated with an origin are cleared on logout or when that origin is removed; all tails are cleared by the explicit cache control and application data removal. HAM never caches access or refresh tokens, tickets, transient runtime IDs, secret input, attachments, or connection strings. Android Auto Backup is disabled.

Host-file contents are downloaded only after an explicit preview, play, save, or share action. Save uses Android's user-selected document destination. Share creates a bounded temporary file in app-private cache and exposes only that file through a one-time Android content-URI grant; Android may later evict the cache file.

## Voice

- **Microphone audio.** When you start dictation or a voice conversation, HAM records audio and sends it to your configured Hermes server for transcription (`/api/audio/transcribe`). That server may forward the audio to its configured speech-to-text provider. Recording happens only after you explicitly start it and stops on release, silence, the server-configured cap, or leaving the app.
- **Spoken replies.** Text you ask HAM to read aloud (or that auto-speak reads, when the server's `voice.auto_tts` is enabled) is sent to your configured Hermes server for synthesis (`/api/audio/speak` and `/api/audio/speak-stream`), which may forward it to its configured text-to-speech provider.
- **No voice persistence.** Microphone recordings, transcripts in flight, and generated speech audio live in memory or short-lived app-cache temporary files that are deleted when playback or transcription finishes. They are never written to the offline transcript cache, saved state, logs, notifications, or any analytics (HAM has none).
- **Lifecycle default.** Voice capture and playback stop when you switch sessions or profiles, log out, lock the device, or leave the app.
- **Screen-off continuation (opt-in).** If you enable "Continue voice with screen off" in Settings, an already-started voice conversation keeps running behind a non-dismissable notification with a Stop action, using Android's microphone foreground service; Android's microphone indicator stays visible, and a partial wake lock keeps capture and network alive. The notification shows only the loop phase (Listening/Thinking/Speaking), never transcript text. After the process is killed, voice never restarts on its own.
- **Server-side processing** of voice audio and speech text — including provider choice, logging, and retention — is controlled by your server's operator and configuration.

## Where data goes

HAM sends data only to the Hermes server origin you configure in the app. HAM does **not** operate a shared hosted backend and does not include analytics, advertising, tracking, or telemetry SDKs.

Your chosen server’s operator and configuration determine how server-side data is processed, retained, logged, and secured. Review that server’s policies before connecting, especially if it is operated by someone else.

## Android permissions

- **Internet** — connect to your configured Hermes server.
- **Notifications** — optional alerts for completed turns and requests requiring your attention, plus the persistent voice-conversation status notification.
- **Foreground service / data sync** — maintain truthful, visible status while an active HAM-started turn is running.
- **Microphone (`RECORD_AUDIO`)** — voice dictation, voice conversations, and barge-in; requested only when you first start a voice feature, used only while one is active.
- **Foreground service / microphone and wake lock** — only for the opt-in screen-off voice continuation described above.

HAM does not request location, contacts, camera, or storage-wide file permissions. Attachments use Android’s user-mediated document picker.

## Security

Cleartext network traffic is disabled. HAM scopes credentials, connection settings, and cached transcript data by server origin, and it does not carry credentials to a newly selected origin.

No software can guarantee absolute security. Do not connect to a server you do not trust, and protect your device with a screen lock.

## Changes and contact

Material updates to this policy will be documented in this repository. For a security-sensitive privacy concern, follow [SECURITY.md](SECURITY.md) rather than opening a public issue.
