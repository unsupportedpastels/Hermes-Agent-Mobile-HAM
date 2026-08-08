# Hermes Android

Native Kotlin/Jetpack Compose remote client for an unchanged `hermes serve` backend.

## Current milestone

- Reproducible AGP 9 / Gradle 9 build
- Foldable-first Material 3 adaptive shell
- Navigation 3 list/detail sessions UI
- Persistent HTTPS server-origin setup and editing
- Real public status/provider probing against the configured origin
- Native Nous OAuth via the system browser, loopback PKCE callback, bearer verification, and read-only durable-session listing
- Strict observer-by-default compatibility with released Hermes servers
- No credentials or backend changes in source control

This milestone deliberately keeps OAuth tokens in memory only. Closing the process requires signing in again. Live WebSocket events, transcript loading, prompt submission, token refresh, and background authentication restoration are not represented as available UI functionality yet.

## Build

```bash
./gradlew clean testDebugUnitTest validateDebugScreenshotTest lintDebug assembleDebug
```

The Android SDK path belongs in untracked `local.properties`.

## Target device

The primary physical target is the standard/non-Ultra Samsung Galaxy Z Fold 8. The UI must also adapt by live window size rather than device-model checks, including cover display, unfolded display, split screen, freeform windows, and DeX.

See [setup](docs/setup.md), [requirements](docs/requirements.md), and [testing](docs/testing.md).
