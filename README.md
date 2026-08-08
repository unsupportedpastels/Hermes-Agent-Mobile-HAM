# Hermes Android

Native Kotlin/Jetpack Compose remote client for an unchanged `hermes serve` backend.

## Current milestone

- Reproducible AGP 9 / Gradle 9 build
- Foldable-first Material 3 adaptive shell
- Navigation 3 list/detail sessions UI
- Strict observer-by-default compatibility with released Hermes servers
- No credentials or backend changes in source control

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Android SDK path belongs in untracked `local.properties`.

## Target device

The primary physical target is the standard/non-Ultra Samsung Galaxy Z Fold 8. The UI must also adapt by live window size rather than device-model checks, including cover display, unfolded display, split screen, freeform windows, and DeX.

See [setup](docs/setup.md), [requirements](docs/requirements.md), and [testing](docs/testing.md).
