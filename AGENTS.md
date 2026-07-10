# Hermes Android

## Product
Native Android remote shell for Hermes Agent. The phone never embeds or reimplements the agent: models, tools, skills, memory, sessions, projects, and filesystem access remain on a remote `hermes serve` host.

## Stack
- Kotlin, Jetpack Compose, Material 3
- minSdk 26, target/compileSdk 35
- OkHttp for REST and the upcoming JSON-RPC WebSocket client
- Tolerant JSON decoding: unknown/missing fields must not crash the app

## UI
- When making UI changes, reference the Material 3 documentation: https://m3.material.io/components/chips/overview

## Remote contract
Use the current Hermes Agent source and docs as the authority. Never invent endpoint or RPC shapes. Relevant surfaces:
- `GET /api/status` for liveness/auth/profile topology
- `GET /api/profiles/sessions` for the cross-profile session list
- WebSocket `/api/ws` using JSON-RPC 2.0 for live chat
- Core RPCs: `session.create`, `session.resume`, `prompt.submit`
- Project RPCs: `projects.list`, `projects.tree`, `projects.project_sessions`
- Event notifications arrive as method `event`, with types such as `message.delta`, `message.complete`, `tool.start`, `tool.complete`, `session.info`, and `status.update`

## Rules
- Keep secrets in encrypted Android storage before production; never log tokens.
- Strip auth/custom headers on cross-origin redirects.
- Prefer OAuth or a VPN/Tailscale-protected host; basic shared-password auth is trusted-network only.
- Preserve per-session model/tool configuration; model switching is a session override, not a global default change.
- Build with the Android Studio JBR on this Windows host.
