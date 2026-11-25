#!/usr/bin/env bash
# Render the Mac app icon sizes from macos/AppIcon.svg (needs rsvg-convert: brew install librsvg).
# The Android icon is a vector drawable (android/app/src/main/res/drawable/ic_launcher_foreground.xml); edit it by hand.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/macos/AppIcon.svg"
OUT="$ROOT/macos/DroidDrop/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$OUT"
for px in 16 32 64 128 256 512 1024; do
    rsvg-convert -w "$px" -h "$px" "$SRC" -o "$OUT/icon_$px.png"
done
echo "wrote $OUT"
