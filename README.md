# DroidDrop

Android ↔ macOS file transfer and clipboard sync over the local network.

- Wire protocol: [`protocol/SPEC.md`](protocol/SPEC.md), conformance fixtures in [`protocol/fixtures/`](protocol/fixtures/)
- Design spec and milestone plans live in `docs/`, which is local only (git-ignored) and not part of the repo.

## Verify

`scripts/check.sh` runs every gate: Swift tests, Android unit tests, lint, builds and the Swift ↔ Kotlin interop.

## Layout

| Path | What |
|---|---|
| `protocol/` | Normative spec, fixtures, fixture generator |
| `macos/DroidDropKit/` | Swift package with all Mac-side logic (`swift test`) |
| `macos/DroidDrop/` | Menu-bar app shell (XcodeGen: `cd macos && xcodegen generate`) |
| `android/core/` | Pure Kotlin/JVM protocol logic (`./gradlew :core:test`) |
| `android/app/` | Android app |
| `android/devcli/` | JVM CLI client for exercising the Mac without a phone |
| `docs/` | Local only, git-ignored: design spec and milestone plans |
