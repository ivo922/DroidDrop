#!/usr/bin/env bash
# Pair Kotlin devcli with the Swift droiddrop-server, connect --ping, round-trip a file each way, send a clip, then pair again.
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

echo "== devcli send up1.bin up2.bin (phone to Mac batch) =="
head -c 1200000 /dev/urandom >"$WORK/up1.bin"
head -c 900000 /dev/urandom >"$WORK/up2.bin"
batch_out="$("$DEVCLI" send "$WORK/up1.bin" "$WORK/up2.bin")"
printf '%s\n' "$batch_out"
expected=$'CONNECTED droiddrop-server\nSENT up1.bin\nSENT up2.bin'
if [[ "$batch_out" != "$expected" ]]; then
    echo "unexpected batch send output" >&2
    exit 1
fi
# The codec sorts keys, so "sent" comes before "type".
wait_for_log '^MESSAGE devcli .*"sent":2.*"type":"batch_end"'
# Batch files report through the batch summary (count done failed paths...), not FILE_RECEIVED.
wait_for_log '^BATCH_SUMMARY in 2 2 0 '
cmp "$WORK/up1.bin" "$WORK/downloads/up1.bin"
cmp "$WORK/up2.bin" "$WORK/downloads/up2.bin"
echo "up1.bin and up2.bin match"

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

echo "== devcli receive --batch-end (Mac to phone batch) =="
head -c 1500000 /dev/urandom >"$WORK/down1.bin"
head -c 1100000 /dev/urandom >"$WORK/down2.bin"
connected_before="$(grep -c '^CONNECTED devcli$' "$WORK/server.log" || true)"
"$DEVCLI" receive "$WORK/down2" --count 2 --batch-end >"$WORK/receive2.out" &
RECEIVE_PID=$!
connected=0
for _ in $(seq 1 100); do
    connected="$(grep -c '^CONNECTED devcli$' "$WORK/server.log" || true)"
    if (( connected > connected_before )); then
        break
    fi
    if ! kill -0 "$RECEIVE_PID" 2>/dev/null; then
        echo "devcli batch receive exited before it connected" >&2
        cat "$WORK/receive2.out" >&2 || true
        exit 1
    fi
    sleep 0.2
done
if (( connected <= connected_before )); then
    echo "timed out waiting for the devcli batch receive session" >&2
    exit 1
fi
echo "send-file $fingerprint $WORK/down1.bin $WORK/down2.bin" >&3

for _ in $(seq 1 300); do
    kill -0 "$RECEIVE_PID" 2>/dev/null || break
    sleep 0.1
done
if kill -0 "$RECEIVE_PID" 2>/dev/null; then
    echo "timed out waiting for devcli batch receive to finish" >&2
    cat "$WORK/receive2.out" >&2 || true
    exit 1
fi
receive_status=0
wait "$RECEIVE_PID" || receive_status=$?
RECEIVE_PID=""
cat "$WORK/receive2.out"
if (( receive_status != 0 )); then
    echo "devcli batch receive exited with $receive_status" >&2
    exit 1
fi
for name in down1.bin down2.bin; do
    if ! grep -qxF "RECEIVED $WORK/down2/$name" "$WORK/receive2.out"; then
        echo "devcli batch receive did not report $name" >&2
        exit 1
    fi
    cmp "$WORK/$name" "$WORK/down2/$name"
done
if ! grep -qxF "BATCH_END 2 2" "$WORK/receive2.out"; then
    echo "devcli batch receive did not report BATCH_END 2 2" >&2
    exit 1
fi
echo "down1.bin and down2.bin match"
wait_for_log '^BATCH_SUMMARY out 2 2 0'

echo "== devcli clip (phone to Mac) =="
clip_out="$("$DEVCLI" clip 'héllo from devcli')"
printf '%s\n' "$clip_out"
expected=$'CONNECTED droiddrop-server\nSENT'
if [[ "$clip_out" != "$expected" ]]; then
    echo "unexpected clip output" >&2
    exit 1
fi
wait_for_log '^MESSAGE devcli .*"type":"clip"'
if ! grep -E '^MESSAGE devcli .*"type":"clip"' "$WORK/server.log" | grep -qF '"text":"héllo from devcli"'; then
    echo "server did not receive the clip text" >&2
    exit 1
fi
echo "clip received"

echo "== devcli pair again (server still trusts devcli) =="
echo "pair" >&3
for _ in $(seq 1 50); do
    (( $(grep -c 'PAIR_URI ' "$WORK/server.log") >= 2 )) && break
    sleep 0.2
done
uri2="$(grep -oE 'droiddrop://[^[:space:]]+' "$WORK/server.log" | sed -n 2p)"
if [[ -z "$uri2" ]]; then
    echo "no second PAIR_URI" >&2
    exit 1
fi
repair_out="$("$DEVCLI" pair "$uri2")"
printf '%s\n' "$repair_out"
if [[ "$repair_out" != "ALREADY_PAIRED droiddrop-server" ]]; then
    echo "unexpected pair-again output" >&2
    exit 1
fi
echo "INTEROP OK"
