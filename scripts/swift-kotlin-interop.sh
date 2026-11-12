#!/usr/bin/env bash
# Pair Kotlin devcli with the Swift droiddrop-server, connect --ping, then round-trip a file each way.
# Builds first, then starts the server with stdin on a FIFO (fd 3) so this script can type commands.
# The server stays up after stdin EOF, so this script kills it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Strip a trailing slash so $WORK matches the normalized paths devcli prints.
TMP_BASE="${TMPDIR:-/tmp}"
WORK="$(mktemp -d "${TMP_BASE%/}/dd-e2e.XXXXXX")"
SERVER_PID=""
RECEIVE_PID=""

cleanup() {
    local status=$?
    # Closing fd 3 and killing every child keeps a failed run from hanging on the FIFO.
    exec 3>&- 2>/dev/null || true
    if [[ -n "$RECEIVE_PID" ]] && kill -0 "$RECEIVE_PID" 2>/dev/null; then
        kill "$RECEIVE_PID" 2>/dev/null || true
    fi
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
    rm -rf "$WORK"
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
mkfifo "$WORK/server.in"
echo "== droiddrop-server --port 0 --state $WORK/server --host 127.0.0.1 --downloads $WORK/downloads =="
"$SERVER" --port 0 --state "$WORK/server" --host 127.0.0.1 --downloads "$WORK/downloads" >"$WORK/server.log" 2>&1 <"$WORK/server.in" &
SERVER_PID=$!
# Drop the job so bash does not print "Terminated" when we kill the server.
disown "$SERVER_PID" 2>/dev/null || true
# Opening for write blocks until the server has opened its read end, which it does before it execs.
exec 3>"$WORK/server.in"

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

# Polls server.log for an extended regex, up to 10 s.
wait_for_log() {
    local pattern="$1" _
    for _ in $(seq 1 50); do
        if grep -qE "$pattern" "$WORK/server.log"; then
            return 0
        fi
        sleep 0.2
    done
    echo "timed out waiting for $pattern in server.log" >&2
    return 1
}

echo "== devcli send (phone to Mac) =="
head -c 3000000 /dev/urandom >"$WORK/up.bin"
send_out="$("$DEVCLI" send "$WORK/up.bin")"
printf '%s\n' "$send_out"
expected=$'CONNECTED droiddrop-server\nSENT up.bin'
if [[ "$send_out" != "$expected" ]]; then
    echo "unexpected send output" >&2
    exit 1
fi
wait_for_log '^FILE_RECEIVED '
cmp "$WORK/up.bin" "$WORK/downloads/up.bin"
echo "up.bin matches"

echo "== devcli receive (Mac to phone) =="
head -c 2500000 /dev/urandom >"$WORK/down.bin"
fingerprint="$(grep -E '^PAIRED devcli ' "$WORK/server.log" | head -n 1 | awk '{print $3}')"
if [[ -z "$fingerprint" ]]; then
    echo "no fingerprint in PAIRED line" >&2
    exit 1
fi
# Pairing, connect --ping and send each registered a session, so the receive session is a later CONNECTED line.
connected_before="$(grep -c '^CONNECTED devcli$' "$WORK/server.log" || true)"
"$DEVCLI" receive "$WORK/down" --count 1 >"$WORK/receive.out" &
RECEIVE_PID=$!
connected=0
for _ in $(seq 1 100); do
    connected="$(grep -c '^CONNECTED devcli$' "$WORK/server.log" || true)"
    if (( connected > connected_before )); then
        break
    fi
    if ! kill -0 "$RECEIVE_PID" 2>/dev/null; then
        echo "devcli receive exited before it connected" >&2
        cat "$WORK/receive.out" >&2 || true
        exit 1
    fi
    sleep 0.2
done
if (( connected <= connected_before )); then
    echo "timed out waiting for the devcli receive session (CONNECTED devcli lines: $connected, before: $connected_before)" >&2
    exit 1
fi
echo "send-file $fingerprint $WORK/down.bin" >&3

for _ in $(seq 1 300); do
    kill -0 "$RECEIVE_PID" 2>/dev/null || break
    sleep 0.1
done
if kill -0 "$RECEIVE_PID" 2>/dev/null; then
    echo "timed out waiting for devcli receive to finish" >&2
    cat "$WORK/receive.out" >&2 || true
    exit 1
fi
receive_status=0
wait "$RECEIVE_PID" || receive_status=$?
RECEIVE_PID=""
cat "$WORK/receive.out"
if (( receive_status != 0 )); then
    echo "devcli receive exited with $receive_status" >&2
    exit 1
fi
if ! grep -qxF "RECEIVED $WORK/down/down.bin" "$WORK/receive.out"; then
    echo "devcli receive did not report down.bin" >&2
    exit 1
fi
cmp "$WORK/down.bin" "$WORK/down/down.bin"
echo "down.bin matches"
echo "INTEROP OK"
