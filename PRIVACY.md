# Privacy policy

**Effective date:** August 15, 2026

HAM (Hermes Agent Mobile) is an Android client for a Hermes Agent server selected and controlled by the user.

## Data HAM handles

To operate, HAM may handle:

- the server address you choose;
- authentication cookies or tokens issued by that server;
- project, session, prompt, response, tool-status, and transcript data returned by that server; and
- files or images you explicitly attach for upload to that server.

HAM stores connection and session data locally on your device, scoped to the normalized server origin. Authentication material is stored using Android-backed encrypted storage. Fresh WebSocket tickets remain in memory only.

## Where data goes

HAM sends data only to the Hermes server origin you configure in the app. HAM does **not** operate a shared hosted backend and does not include analytics, advertising, tracking, or telemetry SDKs.

Your chosen server’s operator and configuration determine how server-side data is processed, retained, logged, and secured. Review that server’s policies before connecting, especially if it is operated by someone else.

## Android permissions

- **Internet** — connect to your configured Hermes server.
- **Notifications** — optional alerts for completed turns and requests requiring your attention.
- **Foreground service / data sync** — maintain truthful, visible status while an active HAM-started turn is running.

HAM does not request location, contacts, camera, microphone, or storage-wide file permissions. Attachments use Android’s user-mediated document picker.

## Security

Cleartext network traffic is disabled. HAM scopes credentials, connection settings, and cached transcript data by server origin, and it does not carry credentials to a newly selected origin.

No software can guarantee absolute security. Do not connect to a server you do not trust, and protect your device with a screen lock.

## Changes and contact

Material updates to this policy will be documented in this repository. For a security-sensitive privacy concern, follow [SECURITY.md](SECURITY.md) rather than opening a public issue.
