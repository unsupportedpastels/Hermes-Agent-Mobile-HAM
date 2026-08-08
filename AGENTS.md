# Repository Instructions

## Product boundary

This is a native Android client for the official interfaces of an unchanged shared `hermes serve` process. Do not add, require, or assume custom server routes, plugins, forks, dashboard extensions, or gateway workers.

Released Hermes compatibility is conservative: observe durable/live metadata without implicit transport takeover. Resume or activate another remote connected client's runtime only after explicit user action. Never close a shared runtime merely because this client disconnects. Capability-gate multi-subscriber streaming until a safe released transport advertises it.

## Android architecture

- Kotlin, single-activity Jetpack Compose, Material 3.
- Navigation 3 with serializable `NavKey`s and saveable back stacks.
- Adaptive list/detail uses `ListDetailSceneStrategy` from `adaptive-navigation3`; do not use legacy `ListDetailPaneScaffold` or `NavigableListDetailPaneScaffold`.
- Make decisions from current window metrics/posture, never device model names or orientation alone.
- Edge-to-edge is mandatory. Apply insets at individual screen/list/composer boundaries and avoid double IME/system-bar padding.
- Keep durable stored-session IDs separate from transient live runtime-session IDs.
- Keep observer and controller roles explicit.

## Security

- Scope credentials, cookies, trust decisions, cached transcripts, and connection settings by normalized server origin.
- Treat WebSocket tickets as fresh, single-use, in-memory values.
- Never log credentials, tokens, cookies, tickets, prompts, transcripts, attachments, secrets, sudo/terminal input, or connection strings.
- Export only the launcher activity. Add no exported component without an explicit threat model and tests.
- Do not commit `local.properties`, signing material, credentials, server URLs, or generated build output.

## Development workflow

Use strict RED -> GREEN -> REFACTOR. Every behavior change starts with a meaningful failing test. Keep reducers, protocol parsing, reconciliation, and policy decisions platform-independent where practical and test them locally.

Required gates for changed Android code:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For adaptive UI changes, also run the configured screenshot/UI test gates and test compact, medium, and expanded windows. Do not update screenshot references without visual review.

## Official project-local skills

The current Google-authored skills live under `.agents/skills/`. Consult matching skills before changes, especially `adaptive`, `navigation-3`, `edge-to-edge`, `testing-setup`, `android-intent-security`, and `android-cli`.
