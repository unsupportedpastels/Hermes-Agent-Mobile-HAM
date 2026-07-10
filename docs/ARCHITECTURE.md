# Architecture

## Boundary
Android is a presentation and transport client. `hermes serve` remains the sole agent runtime. This guarantees the phone automatically inherits every skill, model, provider, MCP server, memory, and tool configured on the host.

## Transport
- REST: discovery, liveness, stored-session lists/details, analytics, and management APIs.
- JSON-RPC WebSocket (`/api/ws`): runtime sessions and streaming agent events.
- Auth: inspect `/api/status`; use Nous Research Portal OAuth/PKCE, persist the resulting dashboard cookies, and mint one-use `/api/auth/ws-ticket` credentials for WebSockets.

## Session lifecycle
1. List stored sessions through `/api/profiles/sessions`.
2. New chat: `session.create` with `source=android`, profile, cwd, model/provider, reasoning effort, and fast flag.
3. Existing chat: `session.resume` with the stored session id.
4. Submit: `prompt.submit` with the runtime session id and text.
5. Fold `event` notifications into UI state (`message.delta`, tool events, approvals, clarification, usage/status).
6. On reconnect, resume the stored id instead of silently creating a second conversation.

## Projects
Projects are backend-owned, per-profile records exposed by `projects.*` RPC methods. The REST-only prototype groups by `cwd`; production must replace that approximation with `projects.tree` and lazy `projects.project_sessions` reads.

## Security
- Never expose `hermes serve` directly to the public internet with only shared basic auth.
- Prefer HTTPS + OAuth, or Tailscale/VPN for a trusted private host.
- Store credentials in Android Keystore-backed encrypted storage.
- Never forward credentials across origins or redirects.
