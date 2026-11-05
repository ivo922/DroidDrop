#!/usr/bin/env bash
# Pair Kotlin devcli with the Swift droiddrop-server, then connect --ping.
# Builds first, then starts the server. The server stays up after stdin EOF, so this script kills it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/dd-e2e.XXXXXX")"
SERVER_PID=""

cleanup() {
    local status=$?
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
        local _
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 0.2
        done
        if kill -0 "$SERVER_PID" 2>/dev/null; then
            kill -9 "$SERVER_PID" 2>/dev/null || true
        fi
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    if [[ -f "$WORK/server.log" ]]; then
        echo "===== server.log ====="
        cat "$WORK/server.log"
    fi
    exit "$status"
}
trap cleanup EXIT

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
    export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "== swift build =="
(cd "$ROOT/macos/DroidDropKit" && swift build)

SERVER="$ROOT/macos/DroidDropKit/.build/debug/droiddrop-server"
mkdir -p "$WORK/server"
echo "== droiddrop-server --port 0 --state $WORK/server --host 127.0.0.1 =="
"$SERVER" --port 0 --state "$WORK/server" --host 127.0.0.1 >"$WORK/server.log" 2>&1 </dev/null &
SERVER_PID=$!
# Drop the job so bash does not print "Terminated" when we kill the server.
disown "$SERVER_PID" 2>/dev/null || true

uri=""
for _ in $(seq 1 30); do
    if grep -q 'PAIR_URI ' "$WORK/server.log" 2>/dev/null; then
        uri="$(grep -oE 'droiddrop://[^[:space:]]+' "$WORK/server.log" | head -n 1)"
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "droiddrop-server exited before PAIR_URI" >&2
        exit 1
    fi
    sleep 1
done

if [[ -z "$uri" ]]; then
    echo "timed out waiting for PAIR_URI" >&2
    exit 1
fi
echo "PAIR_URI $uri"

echo "== devcli installDist =="
(cd "$ROOT/android" && ./gradlew :devcli:installDist --console=plain --warning-mode all)

export DROIDDROP_DEVCLI_HOME="$WORK/cli"
DEVCLI="$ROOT/android/devcli/build/install/devcli/bin/devcli"

echo "== devcli pair =="
pair_out="$("$DEVCLI" pair "$uri")"
printf '%s\n' "$pair_out"
if [[ "$pair_out" != "PAIRED droiddrop-server" ]]; then
    echo "unexpected pair output" >&2
    exit 1
fi

echo "== devcli connect --ping =="
connect_out="$("$DEVCLI" connect --ping)"
printf '%s\n' "$connect_out"
expected=$'CONNECTED droiddrop-server\nPONG 42'
if [[ "$connect_out" != "$expected" ]]; then
    echo "unexpected connect output" >&2
    exit 1
fi

grep -qE '^PAIRED devcli ' "$WORK/server.log"
grep -q 'CONNECTED devcli' "$WORK/server.log"
echo "INTEROP OK"
