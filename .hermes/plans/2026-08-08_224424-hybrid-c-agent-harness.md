# Hybrid C Agent Harness — Simplified Immediate Sprint Plan

> **For Hermes:** Implement the immediate sprint as vertical RED → GREEN → REFACTOR slices. Do not begin the tracked backlog unless the user explicitly promotes an item into scope.

**Goal:** Turn the current sessions-and-transcript Android client into a usable project-first Hermes controller while preserving the unchanged `hermes serve` boundary and the client capabilities already implemented.

**Architecture:** Keep the remote Hermes host authoritative. Add the smallest project data path needed for `Home → Project → Session Workspace`, lazily create project-bound runtimes on first Send, and extend the existing session detail with essential tool and blocking-interaction events. Reuse the current Navigation 3 list/detail structure rather than introducing a parallel desktop-style workspace architecture.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation 3, Material 3 adaptive Navigation 3 scenes, Ktor JSON-RPC/WebSockets, kotlinx.serialization, coroutines/StateFlow, Robolectric Compose tests, Compose Preview Screenshot Testing, and an API 36 foldable emulator.

---

## Decision summary

The original fourteen-task plan mixed the minimum usable project-first controller with speculative IDE-style surfaces and release-level polish. This revision keeps the core product direction but narrows the immediate sprint to five vertical slices.

### Immediate sprint

1. Project browsing and project-first navigation.
2. Project-aware local drafts with runtime creation on first Send.
3. Essential agent events and controller interactions.
4. Nous × Hermes visual treatment and adaptive project/session workspace.
5. Focused verification on the local foldable emulator.

### Tracked, but not in this sprint

- Elaborate reasoning/thinking views and filters.
- Advanced Working Set grouping and expanded inline diffs.
- Tool-output risk presentation.
- Plan/Changes/Terminal inspector and any third persistent pane.
- A permanent navigation rail before multiple useful top-level destinations exist.
- Full repository/lane hierarchy and advanced project search.
- Secret, sudo, and terminal-input response UI.
- Exhaustive screenshot permutations, physical-device release verification, and routine independent review.

---

## Product contract

- **Information architecture:** `Home → Project → Session Workspace`.
- **Session workspace:** durable transcript and live-run state share one destination. “Active run” is runtime state, not a separate route.
- **Compact windows:** one pane at a time.
- **Suitable wider windows:** project/session master and session workspace render through the existing Navigation 3 `ListDetailSceneStrategy`.
- **Visual direction:** graphite/charcoal surfaces; desaturated teal for navigation, selection, links, and timeline structure; warm gold for New task, Send, Stop/current-operation emphasis; green for completed states; no purple, gradients, glow, glass, or decorative AI styling.
- **Truthfulness:** render only fields received from an authoritative contract. Do not invent percentages, future-step totals, token/context telemetry, plans, diffs, durations, or terminal state.
- **Server boundary:** client-only work against released official interfaces. No custom server route, plugin, fork, dashboard extension, or gateway worker.
- **Runtime safety:** project browsing is read-only. Resume or control a runtime only after explicit user action. Back changes presentation and never sends `session.close` or `session.interrupt`.
- **Compatibility:** decode missing and unknown fields tolerantly and fall back to flat durable sessions when project RPCs are unsupported.

---

## Current baseline

- Branch: `main`.
- Clean baseline commit: `3f59c6a`.
- Remote attachments are committed separately in `ab5bebc`; the old dirty-worktree protection task is complete.
- Current root/detail routes are `SessionListRoute`, `SessionDetailRoute`, and `ServerSettingsRoute`.
- The app already uses Navigation 3 with `ListDetailSceneStrategy` and compact versus wider partition directives.
- Existing features to preserve: Nous OAuth, origin-scoped authentication, durable transcript loading, streaming messages, reconnect handling, Markdown rendering, slash completion, and remote attachment staging.
- Current Android live-event parsing handles only `message.start`, `message.delta`, `message.complete`, and `error`.
- The currently installed Hermes source verifies the official project RPCs `projects.tree` and `projects.project_sessions`, project-aware `session.create(cwd)`, tool/status events, `clarify.request`/`clarify.respond`, `approval.request`/`approval.respond`, and `session.interrupt`. Revalidate these contracts at implementation time and capability-gate unsupported methods rather than branching on a Hermes version number.

---

## Implementation rules

1. Use vertical RED → GREEN → REFACTOR slices. Add meaningful tests before each behavior change.
2. Prefer extending existing models, gateway code, ViewModel state, and UI before creating new abstractions.
3. Extract a new file only when it owns a clear model, reducer, or reusable composable; do not create one file per conceptual label in advance.
4. Keep project IDs, folder paths, durable session IDs, local draft IDs, and transient runtime IDs as distinct types or explicitly named values.
5. Bound retained strings, collection sizes, event previews, and frame bodies without persisting raw tool arguments/results by default.
6. Preserve origin/profile generation guards and do not clear credentials for transient transport or protocol failures.
7. Keep controller operations scoped to the selected runtime and current origin/profile generation.
8. Run focused tests during each slice. Run the complete Android gate once at sprint completion unless a cross-cutting change requires it earlier.

---

## Slice 1: Project browsing and project-first navigation

**Objective:** Browse authoritative Hermes projects and their durable sessions without resuming or activating a runtime.

**Expected files:**

- Create or modify a small project model under `app/src/main/java/com/unsupportedpastels/hermesandroid/app/`.
- Modify `gateway/HermesChatGateway.kt` and its tests.
- Modify `gateway/HermesGateway.kt` only as needed to publish project state.
- Modify `connection/HermesConnectionViewModel.kt` and focused tests.
- Modify `navigation/Routes.kt` and route tests.
- Modify `ui/HermesApp.kt` and focused Compose tests; extract Home/Project composables only if that file becomes harder to maintain.

### Required behavior

1. Introduce only the models used by the first UI:
   - nonblank project ID;
   - project label and optional primary path;
   - session count and bounded preview sessions;
   - scoped durable session IDs;
   - project drill-in sessions;
   - loading, unsupported, transient-error, and loaded-empty states.
2. Do not model project color/icon, every repository/lane field, pinning, manual ordering, or discovery controls unless the immediate UI consumes them.
3. Add read-only RPC calls:
   - `projects.tree` with bounded `preview_limit` and `session_limit`;
   - `projects.project_sessions` with `project_id` and a bounded `session_limit`.
4. Decode unknown/missing fields non-fatally and distinguish method-not-found from transient connection errors.
5. Load project metadata after authentication with origin/profile generation guards.
6. Use a metadata-only ticketed connection that never calls `session.resume`, `session.create`, or another controller operation.
7. Reconcile project rows with the existing REST durable-session list by durable ID.
8. Add routes:
   - `HomeRoute` as root;
   - `ProjectRoute(ProjectId)`;
   - existing `SessionDetailRoute(DurableSessionId)` as the session workspace;
   - existing `ServerSettingsRoute`.
9. Compact Back behavior: Session → Project → Home.
10. Wider list/detail behavior: selection changes detail without an unnecessary Back affordance.
11. When project RPCs are unsupported, retain a functional flat Recent Sessions view.

### Focused acceptance checks

- Project browsing performs no runtime resume/create call.
- Duplicate basenames do not become project identity.
- Stale project results cannot overwrite a new origin/profile state.
- Unsupported project methods produce a flat-session fallback rather than a broken Home screen.
- Compact and wide navigation behavior is covered by Compose tests.

---

## Slice 2: Project-aware local drafts and lazy first Send

**Objective:** Start a task inside a selected project without creating a runtime or durable server row until the user sends the first instruction.

**Expected files:**

- Modify `gateway/HermesChatGateway.kt` and gateway tests.
- Modify `connection/HermesConnectionViewModel.kt` and integration tests.
- Modify `MainActivity.kt` or `HermesAppHost` wiring only where callbacks require project context.
- Modify the Home/Project/Session workspace Compose tests.

### Required behavior

1. Extend local draft state with optional project ID and authoritative workspace path.
2. Creating or opening a draft must not connect a chat socket or call `session.create`.
3. On first Send:
   - validate the selected project primary path;
   - open a fresh ticketed connection;
   - call `session.create` with `profile`, `close_on_disconnect=false`, and `cwd` only when an explicit valid project path exists;
   - stage attachments;
   - submit the prompt.
4. Preserve the local draft identity until the server returns a canonical durable identity.
5. Existing durable sessions resume using their stored workspace; do not replace it with the currently selected project.
6. A project without a primary path must show `No workspace` and disable project-scoped Send. The user may start an unscoped task from Home; never silently use the gateway launch directory or invent a folder picker in this sprint.
7. Failed attachment staging leaves the text and attachment chips editable and does not submit a partial prompt.

### Focused acceptance checks

- New task and navigation create no server runtime.
- First Send includes the expected `cwd` exactly once.
- Existing sessions never receive a new project `cwd`.
- Attachments and slash completion still work for the lazily created runtime.
- Canonical durable ID reconciliation remains correct after the first turn.

---

## Slice 3: Essential agent events and controller interactions

**Objective:** Make a submitted run understandable and operable without building the full deferred timeline system.

**Expected files:**

- Add one typed run-event model or extend `HermesChatEvent`.
- Add one small pure reducer/state model if event replacement cannot remain clear in `ChatSessionSnapshot`.
- Modify `gateway/HermesChatGateway.kt` and protocol tests.
- Modify `connection/HermesConnectionViewModel.kt` and integration tests.
- Modify the existing session workspace UI and focused Compose tests.

### Immediate event set

- Existing message start/delta/complete/error.
- `tool.start`.
- `tool.complete`.
- `status.update`.
- `clarify.request` plus `clarify.respond`.
- `approval.request` plus `approval.respond`.
- Expiry/terminal state for blocking requests when advertised by the released contract.
- `session.interrupt` for the selected active runtime.

### Required behavior

1. Pair tool start/complete by stable `tool_id`; completion replaces transient active state rather than adding a duplicate operation.
2. Retain bounded safe context and summary only. Do not retain or render unbounded raw tool arguments/results by default.
3. Preserve event ordering and accept stream events that race the prompt-submit acknowledgment.
4. Render a compact tool/status row inside the current session workspace. A sophisticated grouped Working Set is not required in this sprint.
5. Render live clarification and approval controls only when their matching response RPC is wired.
6. Generation-guard responses so an old request cannot answer a different runtime or origin.
7. For recognized but unsupported blocking requests such as secret/sudo/terminal input, show a truthful non-interactive state indicating that another supported controller is required. Do not show dead input controls.
8. Label interruption as `Stop`; call `session.interrupt` only for the selected active runtime.
9. Back must preserve the runtime ID, selected durable ID, sending state, partial output, socket, and recovery work. It must not call `session.close` or `session.interrupt`.
10. Unknown event types and fields remain nonfatal.

### Focused acceptance checks

- Tool completion replaces its matching start row.
- Clarification and approval responses target the correct request/runtime.
- Stop is idempotent and scoped to the active runtime.
- Back during a long run hides the workspace without stopping it; reopening shows the retained partial response.
- Reconnect snapshot replacement does not duplicate the streaming assistant text or tool row.

---

## Slice 4: Nous × Hermes visual treatment and adaptive workspace

**Objective:** Present the new project-first flow as an agent workspace without adding speculative desktop-style panes.

**Expected files:**

- Modify `theme/Theme.kt`; add a semantic color file only if it improves call-site clarity.
- Modify the project Home, Project, and Session workspace composables.
- Modify focused Compose and screenshot fixtures.

### Required behavior

1. Apply semantic visual roles:
   - teal for navigation, selection, links, and timeline structure;
   - gold for New task, Send, Stop/current-operation emphasis;
   - green for completed state;
   - neutral graphite/charcoal surfaces;
   - Material error roles for failures.
2. Verify semantics through behavior and screenshot tests; do not add tests that merely freeze literal color values.
3. Home clearly separates Projects from flat/unscoped Recent Sessions.
4. Project drill-in lists its durable sessions with Open/Resume and New task actions.
5. Session workspace preserves Markdown, streaming bottom-follow behavior, attachments, slash completion, and IME/system-bar handling.
6. Move away from chat bubbles and sender avatars where practical, but evolve the current screen incrementally rather than replacing it in one big rewrite.
7. Compact windows remain one pane.
8. Suitable wider windows show the project/session master and session workspace through the existing `ListDetailSceneStrategy`.
9. Do not add a permanent navigation rail, supporting inspector, or third pane in this sprint.
10. Keep minimum touch targets, accessibility descriptions, selected-state semantics, and large-text readability.

### Focused screenshot set

- Compact project Home, light theme.
- Compact active session workspace with one tool operation.
- Medium list/detail state.
- Opened approximately 4:3 project/session master plus workspace.
- One compact dark-theme, 1.5× font-scale state.

Do not regenerate references without visual inspection.

---

## Slice 5: Sprint verification

**Objective:** Prove the narrowed sprint works against the unchanged real Hermes host on the local foldable emulator.

1. Run focused tests while implementing each slice.
2. At sprint completion run:

   ```bash
   git diff --check &&
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

3. Run `validateDebugScreenshotTest` after visually reviewing any intentional candidate reference changes.
4. Use `adb devices -l` and qualify every command with the selected emulator serial.
5. Install the exact debug APK without clearing authentication state.
6. Verify compact flow:
   - Home loads projects;
   - open project;
   - create local task;
   - confirm no runtime exists before Send;
   - Send creates the runtime in the expected workspace;
   - tool/status rows update;
   - clarification/approval can be answered;
   - Stop interrupts only the selected run;
   - Back during a run preserves it;
   - reopen continues from retained state.
7. Verify opened approximately 4:3 flow:
   - project/session master and workspace are simultaneously readable;
   - no unnecessary Back control appears;
   - no hinge, system-bar, or IME occlusion is visible.
8. Capture settled screenshots, inspect the UI hierarchy, and scan package logcat for fatal exceptions.
9. Report any blocked real-host interaction honestly. Do not substitute a build or static screenshot for interaction proof.

---

## Suggested commit boundaries

Use coherent commits rather than one commit per micro-step:

1. `feat: add project-first Hermes navigation`
2. `feat: create project tasks lazily on first send`
3. `feat: handle essential Hermes run interactions`
4. `feat: apply adaptive Nous Hermes workspace design`
5. `test: verify project-first agent workspace`

The exact boundary may be adjusted to keep every commit buildable and to avoid splitting tightly coupled production and test changes.

---

## Tracked backlog after the immediate sprint

These items remain intentional future work. They are not deleted from the product direction, but they require a separate scope decision before implementation.

### Rich run visualization

- Parse and present `thinking.delta`, `reasoning.delta`, and `reasoning.available` when available.
- Add All/Thinking/Tools/Output filters only after the underlying content is useful on mobile.
- Group adjacent tools into expandable Working Sets with stable first-tool IDs.
- Render bounded inline diffs with an explicit expansion interaction.
- Present `tool.output_risk` metadata with a reviewed redaction and severity design.
- Add truthful active/completed counts; continue to prohibit percentages and invented totals.

### Workspace inspector

- Re-evaluate Plan/Changes/Terminal only after each tab has an authoritative released contract.
- Start as a supporting sheet; consider a persistent supporting scene only after measured pane widths and screenshot review.
- Never infer plan or terminal state from unrelated transcript text.
- Do not add a third pane solely to imitate a desktop IDE.

### Navigation and project depth

- Add a permanent navigation rail only when there are multiple useful top-level destinations.
- Add full repository/lane hierarchy when users need branch/worktree navigation rather than a flat project session list.
- Add advanced project search, expand/collapse state, pinning, ordering, or discovery controls only with concrete use cases and released contracts.

### Additional controller inputs

- Secure secret-entry response UI.
- Secure sudo-password response UI.
- Terminal input/read-response UI where the released remote contract supports the Android use case.
- Expiry, cancellation, process-recreation, and accessibility tests for each sensitive input type.

### Release hardening

- Broader compact/medium/expanded screenshot permutations.
- Additional dark-theme and large-font component matrices.
- Keyboard, mouse/trackpad, split-screen, freeform, DeX, predictive Back, and process-recreation coverage.
- Physical Fold install and cover/unfolded verification before declaring a release milestone complete.
- Independent security/code review when auth, origin scoping, sensitive input, attachment boundaries, or complex reconnect races materially change; not as an automatic step for every ordinary slice.

---

## Risks and mitigations

- **Older server lacks project RPCs:** distinguish method-not-found and retain flat Recent Sessions.
- **Project metadata is large:** bound collections, strings, preview counts, and frame bodies.
- **Cross-client runtime takeover:** project browsing remains metadata-only; resume/control follows explicit selection.
- **Local draft creates an empty runtime:** move `session.create` from draft-open to first Send and cover it with an integration test.
- **Blocking request is silently dropped:** support clarification/approval now and render a truthful handoff state for unsupported sensitive requests.
- **Sensitive tool output leaks:** keep bounded context/summary; do not retain raw args/results by default.
- **Reconnect duplicates state:** generation-guard operations and replace inflight snapshots/tool state by stable IDs.
- **Back destroys a live run:** separate workspace visibility from selected session/runtime ownership.
- **Adaptive scope expands into a desktop clone:** reuse list/detail and defer rail, inspector, and third pane.
- **Visual regression work dominates the sprint:** use representative screenshots during the sprint and track the exhaustive matrix under release hardening.

---

## Immediate sprint definition of done

- Home loads authoritative projects when supported and falls back to flat sessions when not.
- A project can be opened and its durable sessions browsed without activating a runtime.
- New task remains local until first Send and then creates the runtime with the selected project workspace.
- Session workspace shows streamed output, essential tool/status activity, and working clarification/approval controls.
- Stop maps to `session.interrupt`; Back never stops or closes the run.
- Existing OAuth, origin/profile scoping, transcript loading, reconnect behavior, Markdown, attachments, slash completion, and security boundaries remain intact.
- Compact and opened approximately 4:3 layouts pass focused behavior/screenshot checks.
- Unit tests, lint, debug assembly, screenshot validation, emulator install, interaction smoke test, and logcat inspection complete successfully.
- No custom Hermes backend change is required.

## Explicit non-goals for this sprint

- Full desktop/Hermex feature parity.
- Plan/Changes/Terminal inspector.
- Three persistent content panes.
- Permanent navigation rail.
- Exhaustive reasoning and tool visualization.
- Secret/sudo/terminal input entry.
- Project administration or repository discovery controls.
- Physical-device release certification.
- Automatic independent review for every commit.