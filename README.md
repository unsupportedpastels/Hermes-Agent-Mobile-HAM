# HAM — Hermes Agent Mobile

![HAM mark](assets/brand/ham-mark.svg)

**HAM (Hermes Agent Mobile)** is an independent, open-source Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent). It gives you a native Material 3 workspace for connecting to a Hermes server you control.

> **Unofficial client.** HAM is not affiliated with or endorsed by Nous Research. The Hermes Agent project remains independently maintained and is MIT-licensed.

## What HAM does

- Connects to an unchanged, officially compatible `hermes serve` backend.
- Browses projects and sessions, creates local drafts, and starts a remote runtime only when you send the first prompt.
- Streams replies, tool activity, reasoning, approvals, clarifications, managed images, and remote attachments.
- Supports native Nous OAuth with system-browser PKCE, origin-scoped encrypted credentials, refresh, and reconnect/reconciliation.
- Adapts cleanly across compact phones, Fold cover screens, unfolded layouts, split screen, freeform windows, and DeX.
- Preserves a HAM-started live turn when you navigate away; it does not take over or close another client’s runtime.

## Security & privacy

HAM connects only to the server origin you configure. It does not include a hosted Hermes service, telemetry SDK, analytics SDK, ad network, or hard-coded remote endpoint.

- Credentials, cookies, connection state, and cached transcripts are scoped to the normalized server origin.
- WebSocket tickets are fresh, single-use, and held in memory only.
- Production connections should use HTTPS. Cleartext traffic is disabled in the manifest.
- Your prompts, attachments, and transcript data are processed by the Hermes server you choose—not by a HAM-operated service.

See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) for details.

## Status

HAM is pre-release software. It is being prepared for an initial Google Play release and is not yet a published Play Store app. See [release readiness](docs/release-readiness.md) for the remaining shipping checklist.

## Build from source

### Prerequisites

- JDK 17
- Android SDK platform corresponding to the project’s configured `compileSdk`
- An Android device or emulator for runtime verification

Create an untracked `local.properties` with your SDK path, then run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest
```

The debug APK is written to `app/build/outputs/apk/debug/`.

For local setup and runtime checks, see [docs/setup.md](docs/setup.md) and [docs/testing.md](docs/testing.md).

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or pull request. In particular, HAM must stay a client of released, official Hermes interfaces—no private backend route, plugin, or server fork is a requirement for the app.

## License

HAM is released under the [MIT License](LICENSE). Third-party components retain their own licenses.
