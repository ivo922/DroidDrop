#!/usr/bin/env bash
# Build the Mac menu-bar app and relaunch it, then build and install the Android app on the attached phone.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/macos/build/Build/Products/Debug/DroidDrop.app"

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
    export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"

# Check for the phone first so a missing device fails before the Mac build.
if [[ "$(adb get-state 2>/dev/null)" != "device" ]]; then
    echo "No Android device ready: connect the phone with USB debugging on (adb devices)." >&2
    exit 1
fi

echo "== macOS app =="
(cd "$ROOT/macos" && xcodegen generate --quiet)
xcodebuild -project "$ROOT/macos/DroidDrop.xcodeproj" -scheme DroidDrop -configuration Debug \
    -destination "platform=macOS,arch=$(uname -m)" -derivedDataPath "$ROOT/macos/build" -quiet build

# Quit the running copy so the new build takes over; its state lives on disk.
pkill -x DroidDrop 2>/dev/null || true
for _ in $(seq 1 25); do
    pgrep -x DroidDrop >/dev/null || break
    sleep 0.2
done
open "$APP"
echo "launched $APP"

echo "== Android app =="
(cd "$ROOT/android" && ./gradlew :app:installDebug --console=plain)
