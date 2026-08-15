# Public build readiness plan

## Progress

- [x] **Fix 1 (token-refresh mutex): DONE 2026-08-14.** Added `tokenRefreshMutex` to
  `HermesConnectionViewModel`; `accessTokenForRequest` now enters
  `refreshAndPublishTokensLocked`, which re-reads `activeTokens` under the lock so a
  waiter reuses the winner's refreshed set instead of burning the rotated refresh
  token. Regression test
  `concurrentOperationsShareOneTokenRefreshAndNeverBurnRotatedRefreshToken` added to
  `HermesConnectionViewModelTest` (gated fake refresh client with single-use rotation;
  asserts one refresh call, no sign-out, rotated set persisted). Full ViewModel suite
  green (42 tests).
- [x] **Fix 2 (crash-proof notification service starts): DONE 2026-08-14.**
  Restructured `HermesTurnNotificationService.kt`: per-session attention/completion
  notifications are now posted directly from the app process via a new
  `SessionNotificationPoster` (no service round-trip, legal from background); the
  service is only the FGS anchor for the ongoing notification and handles only
  START/COUNT actions. The controller's `publishActiveCount` uses
  `startForegroundService` when count > 0 and catches `IllegalStateException`
  (covers `ForegroundServiceStartNotAllowedException`) so a denied background start
  degrades instead of crashing. `EXTRA_SESSION_ID` moved to `EXTRA_TAP_SESSION_ID`
  in `NotificationTapActivity.kt`. Instrumented test updated to call the poster
  directly for the completion step (`HermesTurnNotificationServiceTest`, not run —
  needs a device).
- [x] **Fix 3 (exhaustive activeTurnIds sync): DONE 2026-08-14.** `updateChat` now
  calls `syncActiveTurnNotifications()`, which retains `activeTurnIds` against
  authoritative `isSending` state and republishes the count only when it changed
  (`lastPublishedActiveTurnCount` gate). Adds remain explicit via `markTurnActive`
  at server-accepted points: prompt accepted in `sendMessage`, `ensureLiveSession`
  resume with `running=true` (adopted turns — also fixes the FGS never starting for
  them), and `recoverChat` re-resume. Removal is derived, so completion, error,
  stop, recovery give-up, and `running=false` all release the notification;
  `onCleared` now publishes count 0 so swiping the task away stops the FGS. The
  isSending-only retain condition deliberately keeps a recovering turn counted
  while its controller is temporarily removed during backoff. New tests:
  `openingASessionWithARunningTurnCountsItActiveAndReleasesOnCompletion` and
  `recoveryGiveUpReleasesTheActiveTurnCount` in `HermesChatIntegrationTest`;
  existing `rejectedPromptDoesNotStartAPhantomActiveTurn` still passes (accepted-only
  semantics preserved). Full unit suite + lint green.
- [x] **Fix 4 (tolerate bad frames / buffer overflow): DONE 2026-08-14.**
  `HermesChatGateway`: unparseable frames are skipped in `handleFrame` instead of
  throwing `HermesChatProtocolException` (released-server tolerance now covers
  whole frames, not just unknown event types), and `eventChannel` uses
  `BufferOverflow.DROP_OLDEST` so a slow Main-thread collector sheds oldest
  deltas instead of tearing down the socket and failing pending RPCs. NOTE: this
  reverses the previous deliberate fail-closed choice codified in
  `eventBufferOverflowFailsClosedInsteadOfDroppingStreamEvents`; that test was
  rewritten as `eventBufferOverflowDropsOldestEventsInsteadOfTearingDownTheConnection`
  (final text integrity is preserved because `message.complete` carries the full
  text; only transient partials can lose chunks). New test:
  `malformedFramesAreSkippedWithoutTearingDownTheConnection`.
- [x] **Fix 5 (proguard-rules.pro): DONE 2026-08-14.** Created `app/proguard-rules.pro`
  with keep rules for this app's kotlinx.serialization models, the Ktor CIO
  engine ServiceLoader container, and Tink's protobuf-lite reflection surface.
  Minify remains OFF and there is still no release signingConfig — both are
  deliberate user decisions still open (see "Release build config decisions").

**All five code fixes are complete.** Final verification 2026-08-14:
`./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest`
→ BUILD SUCCESSFUL. Changed files: `HermesConnectionViewModel.kt`,
`HermesTurnNotificationService.kt`, `NotificationTapActivity.kt`,
`HermesChatGateway.kt`, `app/proguard-rules.pro` (new), plus tests
(`HermesConnectionViewModelTest.kt`, `HermesChatIntegrationTest.kt`,
`HermesChatGatewayTest.kt`, `NotificationTapIntentTest.kt`,
`HermesTurnNotificationServiceTest.kt`). Still to do outside this batch: the
"Fix soon after" and "Lower priority" lists below, Play-submission process
items, and on-device verification (including the `/loop` scenarios).

- [x] **Fix 6 (Home-project drafts blocked from sending): DONE 2026-08-14.** Found
  on-device: Home project → new task → Send stayed disabled with a "Workspace: No
  workspace" banner. Root cause: the gateway's "Home" project is the synthetic
  `__no_project__` bucket with `path: null` by design (sessions in it have no cwd;
  the server applies its default working directory, i.e. the user's home folder),
  but the app treated any project draft without an absolute workspace path as
  unsendable. Fix: added `NO_PROJECT_BUCKET_ID` / `isNoProjectBucket` to
  `ProjectModels.kt`; `createProjectSession` no longer seeds the "No workspace"
  error for the bucket; `sendMessage`'s workspace gate exempts it (the gateway
  already omits cwd on create, server default applies); `HermesApp.kt` enables
  Send and hides the misleading banner for bucket drafts (the real cwd shows once
  the server reports it after the first send). Real projects with invalid paths
  stay blocked (existing test `projectDraftWithoutValidWorkspaceRejectsSendBeforeConnecting`
  unchanged). New test: `homeBucketDraftSendsWithoutWorkspaceUsingServerDefaultCwd`.
  Deployed to the Fold; on-device confirmation pending (device locked at the time).

Nothing committed yet; all changes are in the worktree
`.claude/worktrees/app-review-public-build-301948`.

Findings from the 2026-08-14 pre-release audit (security, release config, runtime
correctness), ranked, with the intended fix order. `testDebugUnitTest` and `lintDebug`
pass; lint has only minor warnings. All fixes below are client-side only — the gateway
(`hermes serve`) stays unchanged.

## Fix before public build

### 1. Serialize OAuth token refresh (forced-logout race)
`HermesConnectionViewModel.kt:3873-3924` — `accessTokenForRequest()` /
`refreshIfNeeded()` have no mutual exclusion. Two coroutines that both observe an
expired access token each POST `/auth/native/refresh` with the same refresh token;
with server-side refresh-token rotation the loser gets 401 →
`NativeRefreshExpiredException` → `tokenStore.clear()` + sign-in required, wiping
valid credentials. Two successful refreshes can also persist a stale token set
last-writer-wins.

**Fix:** wrap the refresh path in a `Mutex`; after acquiring, re-check whether the
token was already refreshed by the previous holder before issuing a new refresh.

### 2. Make the notification controller crash-proof (background `startService`)
`HermesTurnNotificationService.kt:44-66` — `activeCountChanged`, `approvalRequired`,
`clarificationRequired`, `turnCompleted` use plain `context.startService(...)`, which
throws `IllegalStateException` from a cached background process. The FGS is only
started from `sendMessage` (`HermesConnectionViewModel.kt:1616-1620`), but two live
paths set `isSending=true` without `turnStarted`:
- `openSession` → `ensureLiveSession` when `resumed.running == true` (:2412-2436, :2961)
- `recoverChat` re-resume of a running turn (:2862-2884)

Backgrounding the app while attached to such a turn, then receiving any
approval/clarification/completion event, crashes the app. `startForegroundService`
in `turnStarted` can also throw `ForegroundServiceStartNotAllowedException` on
API 31+ if the user backgrounds during the send round-trip.

**Fix:** catch and degrade (post the notification directly / defer to next
foreground) rather than crash; start the FGS from every path that transitions a
session to `isSending=true`, not just `sendMessage`.

### 3. Exhaustive `activeTurnIds` maintenance (stuck "Hermes is working" FGS)
`activeTurnIds` is removed only on `MessageComplete` (:2698) and `Error` (:2720).
Paths that clear `isSending` but never update the service: `recoverChat` give-up
(:2916-2923), stream-end fallback (:2780-2785), send transport-failure fallback
(:1677-1682), `publishStopSuccess` (:3578-3608), `SessionInfo(running=false)`
(:2629-2636). `onCleared()` (:4006) clears the set without notifying the service, so
swiping the task away orphans the FGS + ongoing notification.

**Fix:** replace scattered add/remove with a single `syncActiveTurns()` derived from
actual per-session `isSending` state, called on every state transition and in
`onCleared()`. Fixes both directions (stuck-on and the count drifting).

### 4. Tolerate bad frames / event-buffer overflow without killing the socket
`HermesChatGateway.kt` — any unparseable frame throws `HermesChatProtocolException`
(:1097-1100) and a full 128-slot `eventChannel` throws
`HermesChatTransportException` (:1371-1373); both tear down the connection and fail
all pending RPCs. The collector runs on Main, so streaming during fold/unfold
recomposition jank can overflow the buffer; recovery budget is 2 per operation, so
two hiccups end a turn with "Connection lost". A newer server emitting one
unexpected frame is fatal — violates the tolerate-released-servers requirement
(unknown event *types* are already tolerated; unknown *frames* are not).

**Fix:** skip malformed frames; on buffer pressure drop oldest events (resume
reconciliation already re-fetches authoritative state) instead of throwing.

### 5. Release build config decisions
`app/build.gradle.kts:20-25`:
- `proguard-rules.pro` is referenced but **does not exist** in the repo. Create it
  (keep rules for kotlinx.serialization, Ktor CIO, Tink) before ever enabling minify.
- `isMinifyEnabled = false`, no `isShrinkResources` — public binary ships unshrunk
  and trivially decompilable. Decide deliberately; enabling requires the rules above.
- No release `signingConfig`. Fine if CI-injected, but document that; otherwise the
  build produces an unsigned artifact Play rejects.

## Play submission process (not code)

- `FOREGROUND_SERVICE_DATA_SYNC` requires a Play Console declaration form + video for
  targetSdk 34+; "keep a connection alive / show progress" justifications have been
  rejected. Prepare the justification (or evaluate `shortService`) before submitting.
- `versionCode 1` / `versionName "0.1.0"` — confirm the public version story.
- Lint suppressions `AndroidGradlePluginVersion` / `OldTargetApi` hide future
  target-policy drift; leave a reminder to remove them.

## Fix soon after (or alongside)

- **Notification permission UX** — `MainActivity.kt:67-69` requests
  `POST_NOTIFICATIONS` on every `onCreate` (no `savedInstanceState == null` guard, no
  rationale, empty callback). On the Z Fold 8 every posture change re-prompts an
  unanswered dialog. Also against Play notification-permission guidance.
- **DNS-rebinding TOCTOU in remote-media SSRF guard** — `RemoteMediaImage.kt:149-159`
  validates resolved IPs, then ktor re-resolves on connect; TTL-0 DNS can pass
  validation and connect to an internal address. Impact bounded by HTTPS-only (no
  readable response; reachability/timing oracle only). Pin the verified IP or
  re-verify the connected address.
- **Approval queue mismatch** — UI keeps only the latest `ApprovalRequest`, gateway
  validates against `pendingApprovals.peekFirst()` (`HermesChatGateway.kt:810-821`);
  with two queued approvals a legitimate tap fails ("choice was not advertised").
- **Duplicate user bubble after reconnect with attachments** — optimistic bubble uses
  typed text (:1599-1603) but `applyResume` compares against submitted text with
  `@file:` refs (:2930-2940), so the prompt renders twice.
- **Unbounded transcript memory** — `updateAssistant` (:2996-3020) has no cumulative
  cap on streamed assistant text; `chatSessions` retains every visited session until
  sign-out; each delta copies the full message list (quadratic). Cap/evict.
- **Latent WS ticket leak** — connect failures embed the full wss URL (ticket
  included) in the retained exception cause (`HermesChatGateway.kt:567-572`,
  :1676-1683). Nothing logs today; strip/replace the cause in
  `KtorChatWebSocketFactory.connect` before any crash-reporting SDK is ever added.

## Lower priority / cleanups

- Composer drafts persist plaintext in saved-instance state (`HermesApp.kt:186-190`) —
  judgment call vs. the "prompts never at rest outside Tink" spirit.
- Notification ID scheme `31 * sessionId.hashCode() + kind` can collide across
  sessions (`HermesTurnNotificationService.kt:212`).
- Notification tap is a silent no-op for sessions outside the loaded 20-item recent
  list (`HermesApp.kt:436-449`).
- `clearTransientChatStates()` (`HermesConnectionViewModel.kt:3815`) is dead code.
- Blank/absent-message `error` events are dropped, leaving the spinner running
  (`HermesChatGateway.kt:1245-1246`).
- `RemoteImageRuntime` statically caches up to ~64 MB of bitmaps with no
  memory-pressure trim (`RemoteMediaImage.kt:216-235`).
- All UI strings are hardcoded Kotlin literals; app cannot be localized without a
  string-resource refactor (`supportsRtl` is moot for translation; RTL layout itself
  is clean).
- Lint: `Configuration.screenWidthDp` in `HermesApp.kt:360` can misreport in
  split-screen/DeX — migrate to `LocalWindowInfo.containerSize`. Plus two style nits
  (`ComposableNaming` in `ScheduledJobsPanel.kt:134`, `AutoboxingStateCreation` ×2)
  and available dependency updates (compose-bom 2026.08.00, adaptive 1.3.0 stable,
  nav3 1.1.6).
- `FakeHermesGateway.kt` ships in the main source set — move to debug/test.
- Screenshot-test plugin is 0.0.1-alpha16 and applied unconditionally; material3
  adaptive is the only non-stable runtime dependency (1.3.0-rc01, stable available).

## `/loop` support (new hermes command)

No client change needed for basic support: slash completion is fully server-driven
(`completeSlash` RPC), and everything except the locally intercepted `/model` and
`/reasoning` passes through to the server as prompt text (`HermesApp.kt:3443-3459`).
`/loop` will autocomplete once the gateway advertises it and submits as-is.

But `/loop` makes previously rare paths routine — loop sessions start new turns
server-side while the app is backgrounded or after restart, which is exactly the
"turn this client didn't start" path. Treat items **2** and **3** above as
prerequisites for shipping publicly with `/loop` live, and note item **4** (long
streams) and transcript growth apply to long-lived loop sessions.

Device verification additions (Fold, per docs/testing.md):
- Start a `/loop` from the app; background across ≥2 iterations; verify no crash,
  correct notifications, and no stuck "working" notification.
- Kill and reopen the app mid-loop; verify resume reconciliation and working
  indicator show idle between iterations and active during them.
- Stop during an active iteration; verify scoped Stop semantics against the loop.
- Verify `/loop` completion rows (interval + nested slash-command argument) apply
  cleanly through `applySlashCompletion`.

## Verified clean (for the record)

Zero sensitive logging anywhere; strict loopback PKCE OAuth (127.0.0.1-only bind,
exact state match, S256, bounded reads/deadlines); Tink AES256-GCM with
origin-bound associated data and fail-closed commits; origin changes tear down all
jobs/controllers with generation guards, no credential carry-forward; no cleartext,
no redirect following, no TLS overrides, no WebView; manifest hygiene (only
launcher exported, immutable PendingIntents, backup fully excluded); bounded
reconnect with no leaked jobs or double-connects; JSON tolerant of unknown
fields/event types/enum values; attachment streams closed; stop/approval/clarify
state machine guarded by request/runtime identity.
