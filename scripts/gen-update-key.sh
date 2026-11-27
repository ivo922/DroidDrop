#!/usr/bin/env bash
# Generates an Ed25519 keypair for signing update.json manifests.
# Prints two single-line base64 values:
#   DROIDDROP_UPDATE_KEY_BASE64  → store in GitHub Actions secrets (never commit)
#   UPDATE_PUBLIC_KEY_BASE64     → commit in UpdateConfig.swift and UpdateConfig.kt
set -euo pipefail

if [[ "$(uname -s)" == "Darwin" ]]; then
    swift - <<'SWIFT'
import CryptoKit
import Foundation

let key = Curve25519.Signing.PrivateKey()
print("DROIDDROP_UPDATE_KEY_BASE64=\(key.rawRepresentation.base64EncodedString())")
print("UPDATE_PUBLIC_KEY_BASE64=\(key.publicKey.rawRepresentation.base64EncodedString())")
SWIFT
else
    tmp="$(mktemp -d)"
    chmod 700 "$tmp"
    trap 'rm -rf "$tmp"' EXIT
    openssl genpkey -algorithm ED25519 -outform DER -out "$tmp/priv.der"
    openssl pkey -inform DER -in "$tmp/priv.der" -pubout -outform DER -out "$tmp/pub.der"
    python3 - "$tmp/priv.der" "$tmp/pub.der" <<'PY'
import base64, sys
priv_der = open(sys.argv[1], "rb").read()
pub_der = open(sys.argv[2], "rb").read()
assert len(priv_der) == 48 and priv_der[:16] == bytes.fromhex("302e020100300506032b657004220420")
assert len(pub_der) == 44 and pub_der[:12] == bytes.fromhex("302a300506032b6570032100")
print("DROIDDROP_UPDATE_KEY_BASE64=" + base64.b64encode(priv_der[16:]).decode())
print("UPDATE_PUBLIC_KEY_BASE64=" + base64.b64encode(pub_der[12:]).decode())
PY
fi
