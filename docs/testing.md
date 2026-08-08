# Testing Strategy

## Fast local gate

```bash
./gradlew testDebugUnitTest
```

Use local tests for reducers, origin normalization, protocol parsing, observer/controller policy, request correlation, reconnect reconciliation, and state restoration models. Prefer explicit fakes over mocks.

## Build and static gate

```bash
./gradlew lintDebug assembleDebug
```

## Adaptive UI matrix

Every screen-level adaptive screenshot suite must cover compact, medium, and expanded widths and representative heights, including 400x500, 610x1000, and 900x1000 dp. Add dark theme and 1.5 font-scale variants for core screens.

Behavior checks must cover:

- compact one-pane list -> detail -> back;
- unfolded list/detail selection;
- fold/unfold with selected session and composer draft preserved;
- resize while streaming and while blocking input is pending;
- edge-to-edge system bars and IME visibility;
- predictive back;
- keyboard focus and navigation;
- split screen, freeform, and DeX-sized windows.

## Device gate

Use a foldable emulator for automated smoke tests. Before milestone completion, install and exercise the debug APK on the standard/non-Ultra Galaxy Z Fold 8. Capture the live layout tree and screenshots for cover, unfolded portrait, and unfolded landscape. A missing physical device blocks only real-device verification, not local development.

Do not regenerate screenshot references without inspecting the visual diff.
