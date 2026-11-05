# DroidDrop Wire Protocol — version 1

This document is normative. The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119. Conformance vectors live in [`fixtures/`](fixtures/); both implementations MUST pass all of them.

## 1. Roles and transport

- The **server** is the Mac. The **client** is the phone.
- Transport is TCP carrying **TLS 1.3 only**. Both sides MUST present a certificate (mutual authentication).
- Certificates are self-signed X.509 with a P-256 ECDSA key. Implementations MUST NOT perform chain, expiry or hostname validation; they MUST instead compare the peer's **fingerprint** (§2) against the expected value.
- Default server port: **47321**. If unavailable, the server MAY use any port; the actual port is advertised via Bonjour (§7) and the pairing URI (§6).

## 2. Fingerprints

- `fingerprint = SHA-256(DER encoding of the peer's leaf certificate)` — 32 bytes.
- Text form (URI, Bonjour TXT, storage): unpadded **base64url** (RFC 4648 §5), 43 characters.
- Display form (UI only): the first 8 bytes as uppercase hex, in four groups of four characters separated by single spaces, e.g. `3F2A 9C10 77DE 0B41`.

## 3. Framing

All integers are unsigned big-endian.

```
Frame   = Length:u32  Kind:u8  Payload
Length  = 1 + byte length of Payload
```

| Kind | Name | Payload |
|---|---|---|
| `0x01` | control | UTF-8 JSON object (§4), at most **524 288** bytes |
| `0x02` | chunk | `TransferId:16 bytes` `Offset:u64` `Data:1..1 048 576 bytes` |

- `TransferId` in a chunk frame is the 16 raw bytes of the UUID (RFC 4122 byte order), i.e. the 32 hex digits of its text form in order.
- `Offset` is the byte position of `Data[0]` within the file.
- Maximum `Length` is therefore `1 + 16 + 8 + 1 048 576 = 1 048 601`.

Decoders MUST validate the header before reading or allocating the payload and MUST reject, with the given error code:

| Condition | Error |
|---|---|
| `Length == 0` | `empty_frame` |
| `Length > 1 048 601`, or Kind is control and `Length - 1 > 524 288` | `frame_too_large` |
| Kind not in the table | `unknown_kind` |
| Kind is chunk and `Length - 1 < 25` (no data byte) | `chunk_too_short` |

Any framing error is fatal: the receiver MUST close the connection. Decoders MUST support frames split across arbitrarily many reads.

## 4. Control messages

A control payload is a JSON object with a string member `type`. Rules:

- Unknown **members** MUST be ignored.
- Unknown **types** MUST be ignored (decoded as "unknown", logged, connection kept).
- A known type with a missing or invalid member, a payload that is not valid UTF-8 JSON, a JSON value that is not an object, or a missing/non-string `type` is a **protocol error**: the receiver MUST close the connection.
- Encoders MUST omit absent optional members (never emit `null`). Decoders treat an optional member whose value is `null` as absent.
- Integers are JSON numbers without fraction or exponent, range `0 … 2^53-1`.
- "Valid JSON" means strict RFC 8259 with no extensions, plus: no byte-order mark, no duplicate member names in any object (compared by code points after unescaping, without Unicode normalization), no unpaired surrogate escapes (`\ud800`), no number token `-0` without fraction or exponent anywhere in the payload, and nesting depth at most 64 (each object or array counts one level, the root object included). Anything else (trailing commas, comments, unquoted literals) is a protocol error.

Common member formats:

| Format | Rule |
|---|---|
| `uuid` | lowercase canonical form, regex `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` |
| `b64url32` | unpadded base64url that decodes to exactly 32 bytes |
| `sha256hex` | regex `^[0-9a-f]{64}$` |
| `nonempty` | string with at least one character |

### 4.1 Session messages

| type | members | notes |
|---|---|---|
| `hello` | `v`: int ≥ 1, `name`: nonempty, `platform`: `"android"` \| `"macos"` | Both sides send first. `v` is the protocol version; this spec is `1`. |
| `pair_request` | `proof`: b64url32 | Client → server, only when unpaired. |
| `pair_result` | `ok`: bool, `proof`: b64url32 (required when `ok` is true), `reason`: string (optional) | Server → client. |
| `ping` | `id`: int | |
| `pong` | `id`: int | Echoes the `ping` id. |

### 4.2 Clipboard

| type | members | notes |
|---|---|---|
| `clip` | `id`: uuid, `mime`: nonempty, `text`: string | v1 senders only use `mime = "text/plain"`. Receivers MUST ignore clips with any other mime. `text` is at most 262 144 UTF-8 bytes. |

### 4.3 File transfer

| type | direction | members |
|---|---|---|
| `file_offer` | sender → receiver | `transferId`: uuid, `name`: nonempty, `size`: int, `sha256`: sha256hex, `mime`: nonempty |
| `file_accept` | receiver → sender | `transferId`, `offset`: int |
| `file_reject` | receiver → sender | `transferId`, `reason`: string |
| `file_done` | sender → receiver | `transferId` |
| `file_result` | receiver → sender | `transferId`, `ok`: bool, `reason`: string (optional) |
| `file_cancel` | either | `transferId` |

## 5. Session

1. Client opens TCP + TLS 1.3. The server's TLS verify step accepts the client certificate if its fingerprint is paired **or** a pairing window (§6) is open; otherwise it fails the handshake. The client accepts the server certificate only if its fingerprint equals the pinned/expected one.
2. Each side immediately sends `hello`. A side that receives `hello` with `v ≠ 1` MUST close. Any message other than `hello` before `hello` is a protocol error.
3. **Unpaired client** (fingerprint not in the server's paired set): the only message the client may send is `pair_request`; anything else is a protocol error. See §6.
4. **Paired session:** all other messages are allowed. `hello` or `pair_request` received again is a protocol error.
5. **Keepalive:** the client sends `ping` (incrementing `id`) after 30 s without sending anything; the server answers `pong`. Each side closes the connection if nothing was received for 90 s.
6. The server keeps at most one session per client fingerprint; a new session for the same fingerprint replaces (closes) the old one.

## 6. Pairing

### 6.1 Pairing URI (QR code content)

```
droiddrop://pair?v=1&name=<server name>&hosts=<ipv4>[,<ipv4>…]&port=<port>&fp=<b64url32>&secret=<b64url32>
```

- Scheme `droiddrop`, host `pair`. Parameter order is free; unknown parameters are ignored. All listed parameters are required.
- `v` MUST be `1`. `port` is 1–65535. `hosts` is a comma-separated, non-empty list of dotted-quad IPv4 addresses (each octet 0–255, no leading zeros beyond a single `0`).
- `fp` is the server fingerprint; `secret` is 32 random bytes.
- **Encoding:** encoders MUST percent-encode every byte of a value outside the unreserved set `A–Z a–z 0–9 - . _ ~` (UTF-8, uppercase hex). **Decoding:** standard percent-decoding; `+` is a literal plus sign, **not** a space.
- **Strict parsing:** decoders MUST reject a URI unless all of the following hold:
  1. It starts with exactly `droiddrop://pair?` (lowercase scheme; authority exactly `pair` — no userinfo, no port, no path) and contains no fragment (`#`).
  2. Every raw character is printable ASCII 0x21–0x7E; anything else must be percent-encoded.
  3. Each of the six known parameters (`v`, `name`, `hosts`, `port`, `fp`, `secret`) appears exactly once (compared after percent-decoding the key) and contains `=`. Unknown parameters may repeat or lack `=`. Empty `&`-separated segments are ignored.
  4. `port` is ASCII decimal matching `[1-9][0-9]{0,4}` with value 1–65535. `v` is exactly `1`.
  5. Every percent escape is `%` followed by two hex digits (either case), and the decoded bytes of every key and value are valid UTF-8.

### 6.2 Pairing window

"Pair New Phone…" on the server opens a window: a fresh 32-byte secret valid for **120 s**, single use. Opening a new window invalidates the previous secret.

### 6.3 Proofs

```
client_proof = HMAC-SHA256(key = secret, msg = "droiddrop-pair-v1|client|" ‖ server_fp ‖ client_fp)
server_proof = HMAC-SHA256(key = secret, msg = "droiddrop-pair-v1|server|" ‖ server_fp ‖ client_fp)
```

Labels are ASCII; fingerprints are the raw 32 bytes. Proofs are sent as b64url32.

### 6.4 Flow

1. Client scans the URI, connects to any listed host (trying them in order) pinning `fp`, exchanges `hello`, sends `pair_request{proof: client_proof}`.
2. Server: if no pairing window is open → `pair_result{ok:false, reason:"window_closed"}` and close. If the proof does not match (constant-time compare) → `pair_result{ok:false, reason:"bad_proof"}` and close. Otherwise it stores the client fingerprint with the client's `hello.name`, closes the window, replies `pair_result{ok:true, proof: server_proof}`, and the session continues as paired.
3. Client verifies `server_proof` (constant-time). On mismatch it closes and discards. Otherwise it stores the server fingerprint, name, port and hosts.

## 7. Discovery

The server advertises Bonjour service type `_droiddrop._tcp` in domain `local.` with TXT records `v=1` and `fp=<server fingerprint, b64url>`. The client matches `fp` against its paired server and connects to the resolved address/port.

## 8. Clipboard semantics

- Each side remembers `lastRemoteHash = SHA-256(text)` of the last clip it applied from the peer, and MUST NOT send a local clipboard change whose text hashes to `lastRemoteHash`.
- Clips larger than 262 144 UTF-8 bytes are not sent.

## 9. File transfer semantics

1. Sender computes `sha256` and size, sends `file_offer`.
2. Receiver replies `file_accept{offset}` where `offset` is the length of a retained partial file with the same `sha256` and `size`, else `0` — or `file_reject{reason}`.
3. Sender sends chunk frames covering `[offset, size)` in order, each ≤ 1 MiB, then `file_done`. A zero-length file has no chunks.
4. Receiver checks that each chunk's `Offset` equals the bytes received so far; on mismatch it sends `file_cancel` and discards the partial. After `file_done` it verifies length and SHA-256, moves the file into place, and sends `file_result{ok}` (on failure `ok:false` with `reason` and discards the partial).
5. `file_cancel` from either side aborts the transfer; the receiver keeps the partial for resume. Chunks for an unknown or cancelled `transferId` MUST be ignored.
6. At most one active transfer per direction; senders queue.

### 9.1 File name sanitization (receiver)

Applied to `file_offer.name` before use:

1. Replace `/`, `\`, `:`, NUL and every control character (U+0000–U+001F, U+007F) with `_`.
2. Trim leading and trailing whitespace, then strip leading `.` characters.
3. If empty, use `file`.
4. If longer than 200 UTF-8 bytes, shorten it to fit, cutting only at character boundaries. The extension (from the last `.`, when that `.` is not the first character) is kept intact if it is ≤ 16 UTF-8 bytes and only the part before it is shortened; otherwise the whole name is cut to 200 bytes.
5. On collision in the destination folder, insert ` (n)` before the extension with the smallest `n ≥ 1` that is free.

## 10. Versioning

Incompatible changes bump `hello.v`. Additive changes (new optional members, new message types) do not, because unknown members and types are ignored.
