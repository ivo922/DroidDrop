#!/usr/bin/env bash
# Builds a release download into dist/ for one platform; the release workflow runs it, and it works locally too.
#   mac      → dist/DroidDrop-<version>-macOS.zip  (universal, ad-hoc signed)
#   android  → dist/DroidDrop-<version>.apk        (signed with the release key)
# Android needs DROIDDROP_KEYSTORE (path to the .jks) and DROIDDROP_KEYSTORE_PASSWORD.
# Usage: scripts/build-release.sh mac|android v1.2.3
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"

usage() { echo "usage: scripts/build-release.sh mac|android vX.Y.Z" >&2; exit 2; }
[[ $# -eq 2 ]] || usage
platform="$1"
read -r name code < <("$ROOT/scripts/release-version.sh" "$2")
mkdir -p "$DIST"

case "$platform" in
    mac)
        export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
        (cd "$ROOT/macos" && xcodegen generate --quiet)
        xcodebuild -project "$ROOT/macos/DroidDrop.xcodeproj" -scheme DroidDrop -configuration Release \
            -destination 'generic/platform=macOS' -derivedDataPath "$ROOT/macos/build" -quiet \
            MARKETING_VERSION="$name" CURRENT_PROJECT_VERSION="$code" build
        app="$ROOT/macos/build/Build/Products/Release/DroidDrop.app"
        codesign --verify --deep --strict "$app"
        out="$DIST/DroidDrop-$name-macOS.zip"
        rm -f "$out"
        ditto -c -k --keepParent "$app" "$out"
        ;;
    android)
        if [[ -z "${DROIDDROP_KEYSTORE:-}" || -z "${DROIDDROP_KEYSTORE_PASSWORD:-}" ]]; then
            echo "set DROIDDROP_KEYSTORE and DROIDDROP_KEYSTORE_PASSWORD to sign the release APK" >&2
            exit 1
        fi
        if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null; then
            JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
            export JAVA_HOME
        fi
        (cd "$ROOT/android" && ./gradlew :app:assembleRelease --console=plain \
            -Pdroiddrop.versionName="$name" -Pdroiddrop.versionCode="$code")
        out="$DIST/DroidDrop-$name.apk"
        cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$out"
        ;;
    *) usage ;;
esac
echo "built $out"
