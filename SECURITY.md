# Security policy

## Supported versions

Security fixes are applied to the latest development version of HAM. The first public Play release has not yet been published.

## Reporting a vulnerability

Please **do not** open a public issue for a suspected vulnerability, credential exposure, authentication flaw, or sensitive privacy report.

After this repository is public, use GitHub’s private vulnerability-reporting flow once it has been enabled. Until then, contact the repository owner privately through their [GitHub profile](https://github.com/unsupportedpastels). Include:

- a concise description of the impact;
- reproducible steps or a minimal proof of concept;
- the affected HAM version or commit; and
- any mitigation you identified.

Do not include real passwords, access tokens, refresh tokens, cookies, WebSocket tickets, private server addresses, prompts, transcripts, or attachments in the report.

## Local offline cache

HAM caches a bounded set of session metadata in app-private storage so the session list can paint while Hermes Serve is unreachable. Cached metadata is never treated as authoritative and is marked cached/offline until the server replaces it.

Transcript tails are **disabled by default**. A user may explicitly opt in from Settings. When enabled, transcript tails are encrypted with an Android Keystore-backed Tink key and are bounded to 200 messages per session, 128 KiB per message body, 100 sessions, 4 MiB total, and 30 days. The cache authority boundary is normalized server origin + profile + durable session ID.

The cache never stores access or refresh tokens, WebSocket tickets, transient runtime IDs, secret or sudo input, attachments, or connection strings. Corrupt or tampered cache rows are ignored and removed without exposing their contents. Transcript tails associated with an origin are cleared on logout or origin removal; explicit cache clearing removes all rows, and deleting a server session removes its local row. Android Auto Backup remains disabled.

Managed artifact downloads remain bounded by the client transport. Explicit sharing writes only the selected artifact to app-private cache and grants read access through a non-exported `FileProvider` content URI. HAM does not execute or render HTML/SVG artifacts in a WebView.

## Scope

HAM is a native client for released Hermes interfaces. Reports affecting Hermes Agent itself, a server deployment, or another upstream dependency may need coordinated disclosure with that project or vendor. We will acknowledge valid reports, assess the client impact, and coordinate a fix where appropriate.
