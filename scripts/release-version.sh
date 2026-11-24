#!/usr/bin/env bash
# Maps a release tag vMAJOR.MINOR.PATCH to "<versionName> <versionCode>", with versionCode = MAJOR*10000 + MINOR*100 + PATCH
# so every later tag gets a larger Android versionCode (and macOS CFBundleVersion).
# Usage: scripts/release-version.sh v1.2.3   → prints "1.2.3 10203"
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]]; then
    echo "release tag must look like v1.2.3 (minor and patch at most 99), got '$tag'" >&2
    exit 2
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
code=$((major * 10000 + minor * 100 + patch))
if (( code == 0 )); then
    echo "v0.0.0 has no valid versionCode" >&2
    exit 2
fi
echo "$major.$minor.$patch $code"
