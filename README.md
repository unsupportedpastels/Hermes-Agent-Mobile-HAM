# Hermes Android

A native Android remote shell for [Hermes Agent](https://github.com/NousResearch/hermes-agent). The app connects to `hermes serve`; all inference, models, skills, tools, memory, projects, and sessions remain on the remote host.

## Current slice
- Remote URL + Nous Research Portal OAuth/PKCE login
- Persistent cookie session with automatic sign-in on app launch
- Live `/api/status` probe
- Cross-profile session list from `/api/profiles/sessions`
- Project-style grouping by session working directory
- Token totals, active state, model, profile, and message count

## Build
```bash
./gradlew test assembleDebug
```

On Windows Git Bash, set `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr` if Java is not already on PATH.

## Planned protocol work
1. Authenticate with Hermes' advertised provider (basic/OAuth + WS ticket).
2. Connect to `/api/ws` with JSON-RPC 2.0.
3. Implement `session.create`, `session.resume`, and `prompt.submit` plus streaming events.
4. Pull `projects.tree` and model options from the gateway.
5. Add approvals, clarification prompts, tool cards, attachments, voice, and encrypted credential storage.

The iOS [Hermex](https://github.com/uzairansaruzi/hermex) project is a design/reference client, but its `hermes-webui` REST API is not treated as the Hermes Agent API contract.
