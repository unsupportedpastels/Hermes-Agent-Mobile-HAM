# Hermes Android

Native Kotlin/Jetpack Compose remote client for an unchanged `hermes serve` backend.

## Current capabilities

- Reproducible AGP 9 / Gradle 9 build and a foldable-first Material 3 adaptive shell.
- Navigation 3 `Home → Project → Session workspace` flow with compact one-pane and wider list/detail presentation.
- Authoritative project/session browsing with a flat-session fallback when project RPCs are unavailable.
- Project-aware local drafts that create a remote runtime only on first Send.
- Persistent HTTPS server-origin setup and origin-scoped encrypted native OAuth tokens.
- Nous OAuth through the system browser and loopback PKCE callback, proactive/reactive token refresh, and process-start authentication restoration.
- Durable transcript loading, ticketed JSON-RPC WebSockets, prompt submission, streaming output, bounded reconnect/resume reconciliation, Markdown, managed images, remote attachments, and live slash completion.
- Concurrent HAM-started sessions with controller state keyed by durable session ID; navigating away does not stop a running turn.
- Tool/status activity, reasoning, clarification and approval responses, scoped Stop, and truthful handoff states for unsupported secure input.
- Foreground lifecycle support plus notifications for final responses and blocking input. Notification taps open the exact durable session.
- Strict observer-by-default compatibility with released Hermes servers and no required backend changes.

The remote `hermes serve` process remains authoritative. HAM does not resume or take control of another connected client's runtime merely to inspect it, and it never closes a shared runtime because the Android UI navigated away or disconnected.

## State and security boundaries

- Durable stored-session IDs, local draft IDs, project IDs, and transient runtime IDs remain distinct.
- Credentials and cached connection state are scoped by normalized server origin; changing origins tears down stale jobs/controllers and never carries credentials forward.
- WebSocket tickets are fresh, single-use, and in memory only.
- Working indicators represent HAM's actual `isSending` turn state, not an idle attached runtime or REST recency metadata.
- Tokens, cookies, tickets, prompts, transcripts, attachments, and connection strings must never be logged.

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest
```

Screenshot references must not be regenerated without visual review. For runtime verification, install the exact debug APK with a serial-qualified, data-preserving `adb install -r` and exercise Back/reopen, concurrent turns, reconnect, notification routing, and compact/wide layouts.

The Android SDK path belongs in untracked `local.properties`.

## Target device

The primary physical target is the standard/non-Ultra Samsung Galaxy Z Fold 8. The UI must also adapt by live window size rather than device-model checks, including cover display, unfolded display, split screen, freeform windows, and DeX.

See [setup](docs/setup.md), [requirements](docs/requirements.md), and [testing](docs/testing.md).
