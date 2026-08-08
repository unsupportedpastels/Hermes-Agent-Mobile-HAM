# Hybrid C Agent Harness Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Replace the current sessions-and-transcript shell with the locked Hybrid C project-first agent harness, using the Nous × Hermes visual system on the Fold cover screen and an adaptive opened 4:3 workspace.

**Architecture:** Keep the unchanged `hermes serve` host authoritative. Extend the existing ticketed JSON-RPC client to read official project and run-event contracts, normalize them into pure Kotlin snapshot models, and render them through Navigation 3 scenes. Compact windows use `Home → Project → Session → Active Run`; opened 4:3 uses a combined project/session master pane beside the run workspace, with Plan/Changes/Terminal exposed as a supporting sheet unless width genuinely permits another pane.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation 3, Material 3 adaptive navigation scenes, Ktor JSON-RPC/WebSockets, kotlinx.serialization, coroutines/StateFlow, Robolectric Compose tests, Compose Preview Screenshot Testing, API 36 foldable emulator.

---

## Product contract

- **Locked information architecture:** Hybrid C project-first split workspace.
- **Locked visual direction:** Nous × Hermes — graphite/charcoal surfaces; desaturated teal for navigation, selection, links, and timeline structure; warm gold only for New task, Send, and active-operation progress; green only for completed states; no purple, gradients, glow, glass, or decorative AI styling.
- **Compact:** one pane at a time. Home distinguishes Projects from Sessions. Project drill-in lists durable sessions. Active Run contains objective, filter chips, timeline, grouped Working Set, agent output, and command dock.
- **Opened 4:3:** navigation rail plus combined project/session master and primary run workspace. Plan/Changes/Terminal is a supporting sheet at medium width and may become a persistent supporting scene only at an evidence-backed expanded breakpoint.
- **Truthfulness:** never show future-step totals, percent complete, or token/context telemetry without an authoritative contract. Show `N complete · M active` and indeterminate progress. Thinking text, durations, summaries, test counts, and diffs render only when present.
- **Server boundary:** client-only implementation against released official interfaces. Do not add or require custom Hermes routes or backend changes.

## Current baseline and constraints

- Branch: `main`; latest committed baseline is `c9d1d5e`.
- The worktree currently contains uncommitted attachment work in `MainActivity.kt`, `HermesConnectionViewModel.kt`, `HermesChatGateway.kt`, `HermesApp.kt`, and matching tests plus new attachment files. Preserve it; do not stash, reset, overwrite, or fold it accidentally into an unrelated commit.
- Current navigation routes are `SessionListRoute`, `SessionDetailRoute`, and `ServerSettingsRoute`.
- Current adaptive strategy already uses `ListDetailSceneStrategy` with one compact and two medium/expanded horizontal partitions.
- Current Android live-event parser accepts only `message.start`, `message.delta`, `message.complete`, and `error`, while the server also emits tool, reasoning, thinking, status, approval, and clarification events.
- Current server project contracts are `projects.tree` and `projects.project_sessions`; project-aware `session.create` accepts `cwd`.
- Existing screenshot source set covers compact, medium, expanded, dark/large-text, settings, and slash completion.

---

### Task 0: Protect and baseline the attachment worktree

**Objective:** Establish a verified starting point without losing or misattributing the existing attachment slice.

**Files:** Read-only inspection of current modified/untracked files; no Hybrid C production edits yet.

1. Run `git status --short` and `git diff --check`.
2. Review `git diff --stat` and the attachment-related diff so later edits preserve all staged-file behavior.
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`.
4. If the attachment slice is already green and complete, commit it separately as `feat: add remote composer attachments`; otherwise finish it under its own TDD scope before starting Task 1.
5. Record the clean baseline commit. Do not proceed with Hybrid C on an unexplained dirty tree.

**Expected:** baseline tests pass; attachment work is either separately committed or explicitly isolated with user-approved handling.

---

### Task 1: Freeze Hybrid C tokens and semantic roles

**Objective:** Replace the purple-biased theme with testable Nous × Hermes tokens before changing layout.

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/theme/Theme.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/theme/HermesSemanticColors.kt`
- Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/theme/HermesSemanticColorsTest.kt`
- Create: `docs/design/hybrid-c.md`

1. Write failing tests asserting distinct semantic roles for navigation/selection teal, primary-action gold, completed green, active progress gold, neutral surfaces, and error.
2. Run `./gradlew testDebugUnitTest --tests '*HermesSemanticColorsTest'`; expect RED because the token object does not exist.
3. Implement immutable light/dark token sets and map them into Material `ColorScheme`. Keep domain semantics outside arbitrary project-provided colors.
4. Document the approved component/color rules and prohibited styling in `docs/design/hybrid-c.md`.
5. Run the focused test; expect GREEN.
6. Commit: `feat: add Nous Hermes design tokens`.

**Acceptance:** no purple/tertiary default remains; gold is not reused for selection or completed states; both light and dark themes meet readable contrast in screenshot review.

---

### Task 2: Add first-class project and workspace models

**Objective:** Represent projects, folders, lanes, and project-owned durable sessions without conflating project IDs, folder paths, durable IDs, or runtime IDs.

**Files:**
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/app/ProjectModels.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/app/HermesAppState.kt`
- Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/app/ProjectModelsTest.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/app/HermesAppStateTest.kt`

1. Write RED tests for nonblank `ProjectId`, primary-folder selection, project/session membership, selected-project reconciliation, and unscoped Recent Sessions.
2. Model only released fields needed by Hybrid C: project ID/name/description/primary path, repositories/lanes, preview sessions, scoped session IDs, and load/error state.
3. Add pure reconciliation that merges refreshed server truth without dropping the selected project/session when still valid.
4. Run focused tests and make them GREEN.
5. Commit: `feat: model projects and workspace sessions`.

**Acceptance:** no basename-only project identity; sessions remain keyed by durable ID; paths are display metadata/workspace targets, not IDs.

---

### Task 3: Parse official project RPC responses

**Objective:** Load project overview and project drill-in through existing ticketed JSON-RPC without resuming or activating sessions.

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGateway.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGatewayTest.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/FakeHermesGateway.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/gateway/FakeHermesGatewayTest.kt`

1. Add RED protocol tests for `projects.tree` parameters (`preview_limit`, bounded `session_limit`) and tolerant decoding of unknown/missing fields.
2. Add RED tests for `projects.project_sessions(project_id)` and null/missing projects.
3. Extend the connection interface with read-only `loadProjectTree()` and `loadProjectSessions(ProjectId)` methods; do not call `session.resume`.
4. Bound project count, lane count, session count, strings, and frame/body sizes before retaining data.
5. Distinguish unsupported-method protocol errors from transient connection failures so older hosts can fall back to flat Recent Sessions.
6. Run `./gradlew testDebugUnitTest --tests '*HermesChatGatewayTest'`; expect GREEN.
7. Commit: `feat: load Hermes projects over JSON RPC`.

**Acceptance:** project browsing mints a fresh WS ticket but never takes control of another runtime.

---

### Task 4: Load and reconcile project state in the ViewModel

**Objective:** Publish project state to Compose with origin/profile generation guards and honest fallbacks.

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/HermesGateway.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionViewModel.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionViewModelTest.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/connection/HermesChatIntegrationTest.kt`

1. Write RED tests for initial project load after authentication, selected-project drill-in, origin changes, stale response suppression, transient failure retry state, and unsupported-host flat-session fallback.
2. Add project overview and per-project session cache to `HermesGatewaySnapshot` or a narrowly scoped sibling `StateFlow`.
3. Use a read-only one-shot gateway connection for project metadata; close it after the RPC. Do not resume sessions.
4. Reconcile project-scoped sessions with the existing REST durable-session list by durable ID.
5. Preserve credentials on transport/503/protocol-transient failures; only existing conclusive auth rejection may sign the user out.
6. Run focused ViewModel/integration tests; expect GREEN.
7. Commit: `feat: publish project workspace state`.

---

### Task 5: Introduce project-first Navigation 3 routes

**Objective:** Implement `Home → Project → Session → Active Run` while preserving current settings and back behavior.

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/navigation/Routes.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/navigation/RoutesTest.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt`

1. Write RED serialization and navigation tests for `HomeRoute`, `ProjectRoute(ProjectId)`, `SessionDetailRoute`, and `WorkspaceInspectorRoute`.
2. Replace `SessionListRoute` as root with `HomeRoute`; keep compatibility only if required to restore old saved state.
3. Make project/session master entries the Navigation 3 list pane and active run the detail pane.
4. Compact Back: Run → Project → Home. Opened list/detail: selection changes detail without showing an unnecessary Back control.
5. Ensure Back while a run is active changes presentation only; it must not close the runtime, clear partial output, or stop recovery.
6. Run focused route/UI tests; expect GREEN.
7. Commit: `feat: add project-first navigation`.

---

### Task 6: Build the compact Hybrid C home and project drill-in

**Objective:** Deliver the Fold cover-screen homepage with clear Projects, nested Sessions, Search, Open project, Resume, and New task actions.

**Files:**
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HomeScreen.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/ProjectSessionsScreen.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/ProjectSessionRows.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt`

1. Write RED Compose tests asserting separate `PROJECTS` and `SESSIONS` headings, project expand/collapse, session Resume, Search state, New task, Open project, loading/error/empty states, and accessibility labels.
2. Implement a compact top bar with independent Search and Settings actions; no outer shared capsule and no decorative branding block.
3. Render project rows from server data and unscoped recents separately; never infer projects from session source labels.
4. Use Material list rows, 12–16dp surfaces only where grouping adds meaning, minimum 48dp touch targets, and semantic Nous × Hermes colors.
5. Keep project search local and bounded. Do not trigger session resume while filtering or opening a project.
6. Run focused Compose tests; expect GREEN.
7. Commit: `feat: build project-first Hermes home`.

---

### Task 7: Make New task project-aware

**Objective:** Start a local draft within the selected project and pass its authoritative primary folder only when the first prompt creates the runtime.

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGateway.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionViewModel.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/MainActivity.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGatewayTest.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/connection/HermesChatIntegrationTest.kt`

1. Add RED tests that a new project draft creates no server row/runtime until first Send.
2. Add RED gateway tests that `session.create` includes `cwd` only for an explicit validated project `primary_path`; retain `profile=default` and `close_on_disconnect=false`.
3. Extend local draft state with optional `ProjectId` and workspace path; never use a client-local Android URI.
4. On first Send, create the runtime with the project workspace and then submit the prompt. Existing sessions remain bound to their stored workspace.
5. Handle projects without a primary path by requiring explicit project/folder selection or showing `No workspace`; never silently use the gateway launch directory.
6. Run focused tests; expect GREEN.
7. Commit: `feat: start tasks in selected project workspace`.

---

### Task 8: Parse rich live agent events

**Objective:** Convert official tool/reasoning/thinking/status events into bounded typed Android events instead of dropping them.

**Files:**
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/AgentRunEvent.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGateway.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGatewayTest.kt`

1. Write RED parser tests for `tool.start`, `tool.complete`, `tool.output_risk`, `reasoning.available`, `reasoning.delta`, `thinking.delta`, and `status.update`.
2. Pair tool start/complete by `tool_id`; preserve name, safe context, duration, summary, todos, and bounded `inline_diff` when present.
3. Do not retain or display unbounded raw args/results by default. Treat tool output as potentially sensitive and cap all previews.
4. Preserve event ordering; high-frequency reasoning/thinking deltas may be coalesced for display, but terminal tool/message events must flush in order.
5. Unknown event types/fields remain forward-compatible and nonfatal.
6. Add approval/clarification models only if their response RPCs are implemented in the same bounded slice; never display dead controls.
7. Run focused gateway tests; expect GREEN.
8. Commit: `feat: parse live Hermes run events`.

---

### Task 9: Reduce events into timeline and Working Sets

**Objective:** Build a deterministic, testable run-state reducer independent of Compose.

**Files:**
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/app/AgentRunState.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/app/AgentRunReducer.kt`
- Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/app/AgentRunReducerTest.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/HermesGateway.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionViewModel.kt`

1. Write RED tests for objective, thinking lifecycle, adjacent-tool grouping, start→complete replacement, active/completed counts, error/cancel states, filter projections, and reconnect snapshot replacement.
2. Use stable IDs: message ID where available, first tool ID for Working Set groups, durable ID for navigation, runtime ID only for live routing.
3. Group adjacent tool events into one Working Set while preserving expandable children and inline diff details.
4. Derive `completeCount` and `activeCount`; never derive percentages or future totals.
5. Preserve the existing transcript as the durable fallback. On reconnect, replace partial streaming snapshots rather than duplicating them.
6. Run reducer and integration tests; expect GREEN.
7. Commit: `feat: reduce agent events into working sets`.

---

### Task 10: Build the active run timeline and command dock

**Objective:** Replace role-labeled transcript rows with the Hybrid C agent harness while preserving Markdown, attachments, slash completion, streaming, and accessibility.

**Files:**
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/AgentRunScreen.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/RunTimeline.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/WorkingSetCard.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/RunCommandDock.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt`

1. Write RED Compose tests for objective, All/Thinking/Tools/Output filters, one selected chip only, grouped tool expansion, diff display, conditional thinking, streaming agent output, complete/active state semantics, attachment chips, slash menu, and command submission.
2. Render user objective and assistant output uncontained; do not use chat bubbles or sender avatars.
3. Render tools as compact timeline rows; expand only the selected group/operation. Use teal for structure/selection, gold for current progress and Send, green for completion.
4. Preserve `MarkdownMessage`, streaming anchor behavior, attachment staging, and slash completion.
5. Replace `Message` copy with `Next instruction or /command`; retain IME and safe-area behavior.
6. Run focused Compose tests; expect GREEN.
7. Commit: `feat: render agent run workspace`.

---

### Task 11: Add truthful Stop and interactive boundaries

**Objective:** Wire controls only to real RPC behavior and preserve dangerous-action boundaries.

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/gateway/HermesChatGateway.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/connection/HermesConnectionViewModel.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/AgentRunScreen.kt`
- Modify: matching gateway, integration, and Compose tests.

1. Write RED tests for `session.interrupt`, idempotent Stop state, terminal cancellation event, and Back-without-interrupt.
2. Label the control `Stop`, not Pause. Send `session.interrupt` only for the selected live runtime.
3. Preserve runtime/session state until terminal confirmation; do not call destructive `session.close` from Back.
4. If approval/clarification events were included in Task 8, implement their official response RPCs and expiry states now; otherwise hide those controls and defer them explicitly.
5. Run focused tests; expect GREEN.
6. Commit: `feat: add truthful run controls`.

---

### Task 12: Implement opened 4:3 adaptive workspace

**Objective:** Match Hybrid C on an unfolded Fold without shrinking a desktop dashboard into unreadable columns.

**Files:**
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/WorkspaceInspector.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/navigation/Routes.kt`
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt`

1. Write RED tests at compact and 900dp widths for one-pane drill-down versus simultaneous master/detail.
2. Keep Navigation 3 `SceneStrategy`; do not introduce legacy `ListDetailPaneScaffold`/`SupportingPaneScaffold`.
3. At opened 4:3, show combined project/session master plus run workspace and a navigation rail. Do not show a Back button in simultaneous list/detail.
4. Implement Plan/Changes/Terminal as a supporting route presented in a Material sheet at medium width. Promote it to a persistent supporting scene only when window metrics prove usable minimum pane widths and screenshot tests remain readable.
5. Drive decisions from window metrics/posture, never the Fold model name or orientation.
6. Verify no double system-bar/IME padding and no occlusion at the hinge.
7. Run focused Compose tests; expect GREEN.
8. Commit: `feat: adapt agent workspace for foldables`.

---

### Task 13: Expand screenshot and accessibility coverage

**Objective:** Lock the approved design across compact cover, opened 4:3, dark/light, and large text without blindly updating references.

**Files:**
- Modify: `app/src/screenshotTest/java/com/unsupportedpastels/hermesandroid/ui/HermesAppScreenshotTest.kt`
- Add screenshot preview fixtures for Home, Project, Active Run, expanded Working Set, and inspector sheet.

1. Add deterministic fake project/session/run fixtures with no private transcript content.
2. Add preview tests for approximately 400×900 cover and 900×1000 opened 4:3, plus 1.5× font scale and dark/light.
3. Run `./gradlew updateDebugScreenshotTest` only in an isolated review step to create candidate references.
4. Inspect every diff visually before accepting; do not update references merely to make CI green.
5. Run `./gradlew validateDebugScreenshotTest` and Compose semantics tests.
6. Check minimum targets, content descriptions, selected-state announcements, contrast, truncation, and keyboard navigation where applicable.
7. Commit: `test: cover Hybrid C adaptive workspace`.

---

### Task 14: Full verification and deployment

**Objective:** Prove the implementation works against the real unchanged Hermes host on the Fold emulator and physical device.

1. Run:
   ```bash
   git diff --check &&
   ./gradlew clean testDebugUnitTest validateDebugScreenshotTest lintDebug assembleDebug
   ```
2. Confirm all tests pass with zero lint errors and a debug APK is produced.
3. Run `adb devices -l`; choose explicit serials for every command.
4. Install on the API 36 foldable emulator without clearing auth state.
5. Verify compact: Home → expand project → resume session → active run → Back preserves run → reopen continues.
6. Resize/open to approximately 4:3; verify rail + project/session master + workspace, then open/close Plan/Changes/Terminal inspector.
7. Execute a harmless tool-heavy test prompt against the real host and confirm tool start/complete replacement, grouped Working Set, conditional diff, streaming output, and truthful counts.
8. Verify New task under a test project sends the expected workspace `cwd` without creating an empty durable session before first Send.
9. Verify Stop maps to `session.interrupt`; Back never stops the run.
10. Capture settled screenshots and inspect them; scan package logcat for fatal exceptions.
11. Install and inspect on the physical Fold only after emulator gates pass. If the device is locked, report visual verification as blocked rather than claiming it.
12. Request independent spec-compliance and code-quality/security review; remediate high-confidence findings and rerun the full gate.
13. Final commit or bounded commit series; leave the tree clean.

---

## Expected commit sequence

1. Existing attachment slice (separate baseline commit)
2. `feat: add Nous Hermes design tokens`
3. `feat: model projects and workspace sessions`
4. `feat: load Hermes projects over JSON RPC`
5. `feat: publish project workspace state`
6. `feat: add project-first navigation`
7. `feat: build project-first Hermes home`
8. `feat: start tasks in selected project workspace`
9. `feat: parse live Hermes run events`
10. `feat: reduce agent events into working sets`
11. `feat: render agent run workspace`
12. `feat: add truthful run controls`
13. `feat: adapt agent workspace for foldables`
14. `test: cover Hybrid C adaptive workspace`

## Risks and mitigations

- **Dirty overlapping worktree:** complete/commit attachment work first; never reset or overwrite it.
- **Older server lacks project RPCs:** capability-fail to flat Recent Sessions; distinguish unsupported from transient failure.
- **Project metadata size:** bound all project/lane/session collections and strings.
- **Cross-client runtime takeover:** project browsing is metadata-only; resume only after explicit user selection.
- **Sensitive tool output:** default to safe context/summary and bounded inline diff; raw results require explicit expansion and redaction policy.
- **Reasoning unavailable:** show generic active state or omit the block; never invent reasoning.
- **Unknown progress:** use counts plus indeterminate progress only.
- **4:3 density:** keep two persistent content panes; inspector is a sheet unless measured width supports more.
- **Reconnect races:** generation-guard project loads and replace inflight snapshots without duplicating events.
- **Navigation teardown:** Back hides the run but does not close or interrupt it.
- **Theme drift:** semantic token tests and reviewed screenshots prevent teal/gold roles from becoming random accents.

## Definition of done

- Project-first Home clearly distinguishes Projects and Sessions.
- A project can be opened, its durable sessions listed, and a session resumed.
- New task is project-aware and lazy-creates the server runtime on first Send.
- Active Run displays objective, conditional thinking, grouped tools, inline diffs, streamed output, filters, and command dock without chat bubbles.
- Only real server data is rendered; no fictional telemetry.
- Cover and opened 4:3 layouts pass screenshot, semantics, unit, lint, build, emulator, and physical-device verification.
- Existing OAuth, transcript IDs/profile scope, stale-auth recovery, Markdown, attachments, slash completion, security boundaries, and adaptive navigation remain intact.
- No custom Hermes backend changes are required.
