# DroidDrop

Android ↔ macOS file transfer and clipboard sync over the local network.

- Design: [`docs/specs/2026-11-05-droiddrop-design.md`](docs/specs/2026-11-05-droiddrop-design.md)
- Wire protocol: [`protocol/SPEC.md`](protocol/SPEC.md), conformance fixtures in [`protocol/fixtures/`](protocol/fixtures/)
- Plans: [`docs/superpowers/plans/`](docs/superpowers/plans/)

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
