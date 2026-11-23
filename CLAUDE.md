# DroidDrop — agent notes

Android ↔ macOS file transfer and clipboard sync over the LAN. Read `README.md` for the layout and
`protocol/SPEC.md` for the wire format (normative).

`docs/` is local only (git-ignored, not in the repo), so it may be missing in a fresh clone. When it is there, read
`docs/specs/2026-11-05-droiddrop-design.md` for behavior. Each milestone's plan in `docs/superpowers/plans/` lists
"Decisions not in the spec" (R1, R2, …): treat those as settled. Never `git add` anything under `docs/`.

## Verify

| What | Command |
|---|---|
| Everything (the gate before any commit) | `scripts/check.sh` (add `--no-interop` to skip the ~1 min interop run) |
| Swift tests | `cd macos/DroidDropKit && swift test` |
| Android unit tests | `cd android && ./gradlew :core:test :app:testDebugUnitTest` |
| Android lint | `cd android && ./gradlew :app:lintDebug` |
| Swift ↔ Kotlin interop | `scripts/swift-kotlin-interop.sh` (ends with `INTEROP OK`) |
| Mac app build | `cd macos && xcodegen generate && xcodebuild -project DroidDrop.xcodeproj -scheme DroidDrop build` |
| Install both apps on a device | `scripts/dev-install.sh` (restarts the running Mac app, installs on the adb phone) |

## Rules that are easy to break

- No shared code: each side is native. Protocol changes go into `protocol/SPEC.md` and `protocol/fixtures/` first
  (regenerate with `protocol/tools/gen_fixtures.py`); both test suites load the same fixtures.
- Mac: all `DroidDropServer` state stays on its serial `queue`; `FileTransfers` has its own queue; UI is `@MainActor`.
  Hop to main with `DispatchQueue.main.async { MainActor.assumeIsolated { … } }` when order matters (one `Task` per
  callback does not keep order).
- Android: `SessionListener` callbacks run on the session's IO thread and must never throw (an exception ends the
  session). Service-scope coroutines have no exception handler: an escaping exception crashes the process.
- Android app unit tests are plain JVM (JUnit 5), no Robolectric. Keep decisions in pure classes
  (`ReconnectPolicy`, `SendGate`, `ClipLink`, …) and test those; framework glue stays thin.
- Swift tests use Swift Testing (`@Suite`, `@Test`, `#expect`).
- Clip text, pairing secrets and pairing URIs are never logged on either side.
- Commits: short conventional subjects, e.g. `fix(android): keep pairing on network resets`; scopes are `macos`,
  `android`, `protocol`, `scripts`, `plans`.
