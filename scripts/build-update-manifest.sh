#!/usr/bin/env bash
# Builds and Ed25519-signs dist/update.json + dist/update.json.sig for a release tag.
# Requires DROIDDROP_UPDATE_KEY_BASE64 (base64 of the 32-byte raw Ed25519 private seed).
# Usage: scripts/build-update-manifest.sh vX.Y.Z dist/ [owner/repo]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() { echo "usage: scripts/build-update-manifest.sh vX.Y.Z dist/ [owner/repo]" >&2; exit 2; }
[[ $# -ge 2 && $# -le 3 ]] || usage

TAG="$1"
DIST="$2"
REPO="${3:-ivo922/DroidDrop}"

if [[ -z "${DROIDDROP_UPDATE_KEY_BASE64:-}" ]]; then
    echo "set DROIDDROP_UPDATE_KEY_BASE64 to sign update.json" >&2
    exit 1
fi

read -r VERSION VERSION_CODE < <("$ROOT/scripts/release-version.sh" "$TAG")
MAC_ZIP="$DIST/DroidDrop-$VERSION-macOS.zip"
ANDROID_APK="$DIST/DroidDrop-$VERSION.apk"
[[ -f "$MAC_ZIP" ]] || { echo "missing $MAC_ZIP" >&2; exit 1; }
[[ -f "$ANDROID_APK" ]] || { echo "missing $ANDROID_APK" >&2; exit 1; }

MANIFEST="$DIST/update.json"
SIGNATURE="$DIST/update.json.sig"

python3 - "$TAG" "$VERSION" "$VERSION_CODE" "$REPO" "$MAC_ZIP" "$ANDROID_APK" "$ROOT/CHANGELOG.md" "$MANIFEST" <<'PY'
import hashlib, json, os, re, sys

tag, version, code_str, repo, mac_path, apk_path, changelog_path, out_path = sys.argv[1:]
version_code = int(code_str)

def asset_info(path, filename):
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
            size += len(chunk)
    return {
        "url": f"https://github.com/{repo}/releases/download/{tag}/{filename}",
        "sha256": h.hexdigest(),
        "size": size,
    }

def extract_notes(path, version):
    if not os.path.isfile(path):
        return ""
    text = open(path, "r", encoding="utf-8").read()
    pattern = re.compile(rf"^##\s+{re.escape(version)}(?:\b[^\n]*)?\n(.*?)(?=^##\s|\Z)", re.M | re.S)
    match = pattern.search(text)
    return match.group(1).strip() if match else ""

manifest = {
    "version": version,
    "versionCode": version_code,
    "notes": extract_notes(changelog_path, version),
    "macos": asset_info(mac_path, os.path.basename(mac_path)),
    "android": asset_info(apk_path, os.path.basename(apk_path)),
}

with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY

if [[ "$(uname -s)" == "Darwin" ]]; then
    swift - "$MANIFEST" "$SIGNATURE" <<'SWIFT'
import CryptoKit
import Foundation

let args = CommandLine.arguments
let manifestURL = URL(fileURLWithPath: args[1])
let sigURL = URL(fileURLWithPath: args[2])
guard let b64 = ProcessInfo.processInfo.environment["DROIDDROP_UPDATE_KEY_BASE64"]?.trimmingCharacters(in: .whitespacesAndNewlines),
      let seed = Data(base64Encoded: b64), seed.count == 32 else {
    fputs("DROIDDROP_UPDATE_KEY_BASE64 must be base64 of a 32-byte Ed25519 seed\n", stderr)
    exit(1)
}
let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
let data = try Data(contentsOf: manifestURL)
let sig = try key.signature(for: data)
guard key.publicKey.isValidSignature(sig, for: data) else {
    fputs("self-verification failed\n", stderr)
    exit(1)
}
if let expectedPub = ProcessInfo.processInfo.environment["DROIDDROP_UPDATE_PUBLIC_KEY_BASE64"]?.trimmingCharacters(in: .whitespacesAndNewlines),
   !expectedPub.isEmpty,
   expectedPub != key.publicKey.rawRepresentation.base64EncodedString() {
    fputs("private key does not match DROIDDROP_UPDATE_PUBLIC_KEY_BASE64\n", stderr)
    exit(1)
}
try (sig.base64EncodedString() + "\n").write(to: sigURL, atomically: true, encoding: .utf8)
SWIFT
else
    tmp="$(mktemp -d)"
    chmod 700 "$tmp"
    trap 'rm -rf "$tmp"' EXIT
    python3 - "$tmp/priv.der" <<'PY'
import base64, os, sys
raw = base64.b64decode(os.environ["DROIDDROP_UPDATE_KEY_BASE64"].strip())
if len(raw) != 32:
    sys.exit("DROIDDROP_UPDATE_KEY_BASE64 must be base64 of a 32-byte Ed25519 seed")
prefix = bytes.fromhex("302e020100300506032b657004220420")
with open(sys.argv[1], "wb") as f:
    f.write(prefix + raw)
PY
    openssl pkey -inform DER -in "$tmp/priv.der" -pubout -outform DER -out "$tmp/pub.der"
    if [[ -n "${DROIDDROP_UPDATE_PUBLIC_KEY_BASE64:-}" ]]; then
        actual_pub="$(python3 - "$tmp/pub.der" <<'PY'
import base64, sys
pub_der = open(sys.argv[1], "rb").read()
assert len(pub_der) == 44 and pub_der[:12] == bytes.fromhex("302a300506032b6570032100")
print(base64.b64encode(pub_der[12:]).decode())
PY
)"
        if [[ "$actual_pub" != "$DROIDDROP_UPDATE_PUBLIC_KEY_BASE64" ]]; then
            echo "private key does not match DROIDDROP_UPDATE_PUBLIC_KEY_BASE64" >&2
            exit 1
        fi
    fi
    openssl pkeyutl -sign -rawin -keyform DER -inkey "$tmp/priv.der" -in "$MANIFEST" -out "$tmp/sig.bin"
    openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey "$tmp/pub.der" -sigfile "$tmp/sig.bin" -in "$MANIFEST" >/dev/null
    base64 < "$tmp/sig.bin" | tr -d '\n' > "$SIGNATURE"
    printf '\n' >> "$SIGNATURE"
fi

echo "wrote $MANIFEST and $SIGNATURE"
