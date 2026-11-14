#!/usr/bin/env bash
# Runs every verification gate: Swift tests, Android unit tests, lint and builds, then the Swift↔Kotlin interop.
# Usage: scripts/check.sh [--no-interop]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INTEROP=1
for arg in "$@"; do
    case "$arg" in
        --no-interop) INTEROP=0 ;;
        *) echo "usage: scripts/check.sh [--no-interop]" >&2; exit 2 ;;
    esac
done

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
    export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "== swift test =="
(cd "$ROOT/macos/DroidDropKit" && swift test)

echo "== android: tests, lint, builds =="
(cd "$ROOT/android" && ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :devcli:installDist \
    --console=plain --warning-mode all)

if (( INTEROP )); then
    echo "== interop =="
    "$ROOT/scripts/swift-kotlin-interop.sh"
fi

echo "CHECK OK"
