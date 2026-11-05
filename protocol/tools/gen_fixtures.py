#!/usr/bin/env python3
"""Regenerates protocol/fixtures/*.json. Deterministic: same output on every run."""

import base64
import hashlib
import hmac
import json
import struct
import uuid
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"

MAX_CONTROL = 524_288
MAX_CHUNK_DATA = 1_048_576
MAX_LENGTH = 1 + 16 + 8 + MAX_CHUNK_DATA


B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"


def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def control_frame(obj) -> str:
    payload = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode()
    return (struct.pack(">IB", 1 + len(payload), 1) + payload).hex()


def chunk_frame(transfer_id: str, offset: int, data: bytes) -> str:
    payload = uuid.UUID(transfer_id).bytes + struct.pack(">Q", offset) + data
    return (struct.pack(">IB", 1 + len(payload), 2) + payload).hex()


def display_code(fp: bytes) -> str:
    h = fp[:8].hex().upper()
    return " ".join(h[i : i + 4] for i in range(0, 16, 4))


def frames():
    tid = "0f8e2d1c-3b4a-4c5d-8e6f-7a8b9c0d1e2f"
    ping = {"type": "ping", "id": 7}
    clip = {"type": "clip", "id": "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", "mime": "text/plain", "text": "héllo 👋"}
    return {
        "maxControlPayload": MAX_CONTROL,
        "maxChunkData": MAX_CHUNK_DATA,
        "maxFrameLength": MAX_LENGTH,
        "valid": [
            {"name": "control_ping", "hex": control_frame(ping), "kind": "control", "json": ping},
            {"name": "control_utf8", "hex": control_frame(clip), "kind": "control", "json": clip},
            {"name": "chunk_small", "hex": chunk_frame(tid, 0, bytes([0, 1, 2, 3, 255])), "kind": "chunk",
             "transferId": tid, "offset": 0, "dataHex": "00010203ff"},
            {"name": "chunk_offset_above_32_bits", "hex": chunk_frame(tid, 4_294_967_301, b"\xab"), "kind": "chunk",
             "transferId": tid, "offset": 4_294_967_301, "dataHex": "ab"},
        ],
        # Header-only hex is enough: decoders must reject before reading the payload.
        "invalid": [
            {"name": "empty_frame", "hex": "00000000", "error": "empty_frame"},
            {"name": "frame_too_large", "hex": struct.pack(">IB", MAX_LENGTH + 1, 2).hex(), "error": "frame_too_large"},
            {"name": "control_too_large", "hex": struct.pack(">IB", MAX_CONTROL + 2, 1).hex(), "error": "frame_too_large"},
            {"name": "unknown_kind", "hex": "0000000203aa", "error": "unknown_kind"},
            {"name": "chunk_without_data", "hex": (struct.pack(">IB", 25, 2) + bytes(24)).hex(), "error": "chunk_too_short"},
        ],
    }


def messages():
    tid = "0f8e2d1c-3b4a-4c5d-8e6f-7a8b9c0d1e2f"
    proof = b64url(bytes(range(32)))
    sha = hashlib.sha256(b"hello world").hexdigest()
    valid = [
        {"type": "hello", "v": 1, "name": "Ivo's MacBook Pro", "platform": "macos"},
        {"type": "hello", "v": 1, "name": "Pixel 9", "platform": "android"},
        {"type": "pair_request", "proof": proof},
        {"type": "pair_result", "ok": True, "proof": proof},
        {"type": "pair_result", "ok": False, "reason": "bad_proof"},
        {"type": "pair_result", "ok": False},
        {"type": "ping", "id": 0},
        {"type": "pong", "id": 9007199254740991},
        {"type": "clip", "id": "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", "mime": "text/plain", "text": ""},
        {"type": "clip", "id": "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", "mime": "text/plain", "text": "line1\nline2 \"quoted\" ✓"},
        {"type": "file_offer", "transferId": tid, "name": "photo.jpg", "size": 11, "sha256": sha, "mime": "image/jpeg"},
        {"type": "file_offer", "transferId": tid, "name": "empty.txt", "size": 0,
         "sha256": hashlib.sha256(b"").hexdigest(), "mime": "text/plain"},
        {"type": "file_accept", "transferId": tid, "offset": 0},
        {"type": "file_accept", "transferId": tid, "offset": 5_000_000_000},
        {"type": "file_reject", "transferId": tid, "reason": "disk_full"},
        {"type": "file_done", "transferId": tid},
        {"type": "file_result", "transferId": tid, "ok": True},
        {"type": "file_result", "transferId": tid, "ok": False, "reason": "hash_mismatch"},
        {"type": "file_cancel", "transferId": tid},
    ]
    return {
        "valid": [{"name": f"{i:02d}_{m['type']}", "json": m} for i, m in enumerate(valid)],
        "normalized": [
            {"name": "unknown_members_dropped",
             "input": {"type": "ping", "id": 3, "future": {"nested": True}},
             "output": {"type": "ping", "id": 3}},
        ],
        "validRaw": [
            {"name": "escaped_surrogate_pair",
             "raw": "{\"type\":\"clip\",\"id\":\"5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f\",\"mime\":\"text/plain\",\"text\":\"\\ud83d\\ude00\"}",
             "json": {"type": "clip", "id": "5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f", "mime": "text/plain", "text": "\U0001F600"}},
            {"name": "insignificant_whitespace", "raw": " {\n\"type\" : \"ping\" ,\t\"id\":7 }\r\n",
             "json": {"type": "ping", "id": 7}},
            {"name": "same_name_in_different_objects", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":{\"id\":2,\"y\":[{\"id\":3}]}}",
             "json": {"type": "ping", "id": 1}},
            {"name": "raw_multibyte_utf8", "raw": "{\"type\":\"ping\",\"id\":1,\"é\":\"é😀ÿ\",\"e\":2}",
             "json": {"type": "ping", "id": 1}},
            {"name": "distinct_names_not_normalized", "raw": "{\"type\":\"ping\",\"id\":1,\"\\u212B\":1,\"\\u00C5\":2,\"A\\u030A\":3}",
             "json": {"type": "ping", "id": 1}},
            {"name": "nesting_depth_64", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":" + "[" * 63 + "]" * 63 + "}",
             "json": {"type": "ping", "id": 1}},
            {"name": "large_finite_double_ok", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":1.7e308}",
             "json": {"type": "ping", "id": 1}},
            {"name": "pair_result_null_optionals", "raw": "{\"type\":\"pair_result\",\"ok\":false,\"proof\":null,\"reason\":null}",
             "json": {"type": "pair_result", "ok": False}},
        ],
        "unknown": [
            {"name": "future_type", "json": {"type": "screen_share", "x": 1}, "type": "screen_share"},
        ],
        "invalid": [
            {"name": "not_json", "raw": "{type:ping"},
            {"name": "array", "raw": "[1,2]"},
            {"name": "string", "raw": "\"ping\""},
            {"name": "missing_type", "raw": "{\"id\":1}"},
            {"name": "type_not_string", "raw": "{\"type\":5}"},
            {"name": "hello_missing_name", "raw": "{\"type\":\"hello\",\"v\":1,\"platform\":\"macos\"}"},
            {"name": "hello_empty_name", "raw": "{\"type\":\"hello\",\"v\":1,\"name\":\"\",\"platform\":\"macos\"}"},
            {"name": "hello_v_string", "raw": "{\"type\":\"hello\",\"v\":\"1\",\"name\":\"a\",\"platform\":\"macos\"}"},
            {"name": "hello_v_zero", "raw": "{\"type\":\"hello\",\"v\":0,\"name\":\"a\",\"platform\":\"macos\"}"},
            {"name": "hello_bad_platform", "raw": "{\"type\":\"hello\",\"v\":1,\"name\":\"a\",\"platform\":\"ios\"}"},
            {"name": "pair_request_short_proof", "raw": "{\"type\":\"pair_request\",\"proof\":\"" + b64url(bytes(31)) + "\"}"},
            {"name": "pair_request_padded_proof", "raw": "{\"type\":\"pair_request\",\"proof\":\"" + b64url(bytes(32)) + "=\"}"},
            {"name": "pair_request_noncanonical_proof", "raw": "{\"type\":\"pair_request\",\"proof\":\"" + b64url(bytes(32))[:-1] + "B\"}"},
            {"name": "pair_result_ok_without_proof", "raw": "{\"type\":\"pair_result\",\"ok\":true}"},
            {"name": "file_result_reason_object", "raw": "{\"type\":\"file_result\",\"transferId\":\"" + tid + "\",\"ok\":false,\"reason\":{}}"},
            {"name": "ping_negative", "raw": "{\"type\":\"ping\",\"id\":-1}"},
            {"name": "ping_fraction", "raw": "{\"type\":\"ping\",\"id\":1.5}"},
            {"name": "ping_leading_zero", "raw": "{\"type\":\"ping\",\"id\":01}"},
            {"name": "ping_plus_sign", "raw": "{\"type\":\"ping\",\"id\":+1}"},
            {"name": "file_result_ok_uppercase", "raw": "{\"type\":\"file_result\",\"transferId\":\"" + tid + "\",\"ok\":TRUE}"},
            {"name": "clip_uppercase_uuid", "raw": "{\"type\":\"clip\",\"id\":\"5B2C8F9E-1D3A-4E7B-9C6D-0A1B2C3D4E5F\",\"mime\":\"text/plain\",\"text\":\"x\"}"},
            {"name": "clip_missing_text", "raw": "{\"type\":\"clip\",\"id\":\"5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f\",\"mime\":\"text/plain\"}"},
            {"name": "file_offer_negative_size", "raw": "{\"type\":\"file_offer\",\"transferId\":\"" + tid + "\",\"name\":\"a\",\"size\":-1,\"sha256\":\"" + sha + "\",\"mime\":\"x/y\"}"},
            {"name": "file_offer_uppercase_sha", "raw": "{\"type\":\"file_offer\",\"transferId\":\"" + tid + "\",\"name\":\"a\",\"size\":1,\"sha256\":\"" + sha.upper() + "\",\"mime\":\"x/y\"}"},
            {"name": "file_offer_empty_name", "raw": "{\"type\":\"file_offer\",\"transferId\":\"" + tid + "\",\"name\":\"\",\"size\":1,\"sha256\":\"" + sha + "\",\"mime\":\"x/y\"}"},
            {"name": "file_accept_bad_uuid", "raw": "{\"type\":\"file_accept\",\"transferId\":\"not-a-uuid\",\"offset\":0}"},
            {"name": "file_accept_negative_offset", "raw": "{\"type\":\"file_accept\",\"transferId\":\"" + tid + "\",\"offset\":-5}"},
            {"name": "ping_negative_zero", "raw": "{\"type\":\"ping\",\"id\":-0}"},
            {"name": "negative_zero_in_unknown_member", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":-0}"},
            {"name": "trailing_comma_object", "raw": "{\"type\":\"ping\",\"id\":1,}"},
            {"name": "trailing_comma_nested_array", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":[1,]}"},
            {"name": "comment", "raw": "{\"type\":\"ping\",/*c*/\"id\":1}"},
            {"name": "duplicate_member", "raw": "{\"type\":\"ping\",\"id\":1,\"id\":2}"},
            {"name": "duplicate_member_nested", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":{\"a\":1,\"a\":2}}"},
            {"name": "duplicate_member_escaped", "raw": "{\"type\":\"ping\",\"id\":1,\"\\u0069d\":2}"},
            {"name": "lone_high_surrogate", "raw": "{\"type\":\"clip\",\"id\":\"5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f\",\"mime\":\"text/plain\",\"text\":\"\\ud800\"}"},
            {"name": "lone_low_surrogate_in_unknown_member", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":\"\\udc00\"}"},
            {"name": "trailing_garbage", "raw": "{\"type\":\"ping\",\"id\":1}x"},
            {"name": "nesting_depth_65", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":" + "[" * 64 + "]" * 64 + "}"},
            {"name": "number_overflow_in_unknown_member", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":1E400}"},
            {"name": "negative_number_overflow", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":-1e309}"},
            {"name": "long_integer_overflow", "raw": "{\"type\":\"ping\",\"id\":1,\"x\":1" + "0" * 400 + "}"},
            {"name": "ping_fraction_zero", "raw": "{\"type\":\"ping\",\"id\":1.0}"},
            {"name": "ping_exponent", "raw": "{\"type\":\"ping\",\"id\":1e3}"},
            {"name": "ping_above_max", "raw": "{\"type\":\"ping\",\"id\":9007199254740992}"},
            {"name": "ping_u64_max", "raw": "{\"type\":\"ping\",\"id\":18446744073709551615}"},
            {"name": "ping_id_bool", "raw": "{\"type\":\"ping\",\"id\":true}"},
            {"name": "ping_id_null", "raw": "{\"type\":\"ping\",\"id\":null}"},
            {"name": "clip_text_null", "raw": "{\"type\":\"clip\",\"id\":\"5b2c8f9e-1d3a-4e7b-9c6d-0a1b2c3d4e5f\",\"mime\":\"text/plain\",\"text\":null}"},
            {"name": "hello_v_float", "raw": "{\"type\":\"hello\",\"v\":1.0,\"name\":\"a\",\"platform\":\"macos\"}"},
            {"name": "file_result_ok_number", "raw": "{\"type\":\"file_result\",\"transferId\":\"" + tid + "\",\"ok\":1}"},
        ],
        "invalidHex": [
            {"name": "bad_utf8", "hex": "7b2274797065223a2270696e67222c226964223a312c2278223a22ff22 7d".replace(" ", "")},
            {"name": "utf8_bom", "hex": "efbbbf" + b"{\"type\":\"ping\",\"id\":1}".hex()},
            {"name": "utf8_surrogate", "hex": (b"{\"type\":\"ping\",\"id\":1,\"x\":\"" + b"\xed\xa0\x80" + b"\"}").hex()},
            {"name": "utf8_overlong", "hex": (b"{\"type\":\"ping\",\"id\":1,\"x\":\"" + b"\xc0\x80" + b"\"}").hex()},
        ],
    }


def pairing():
    vectors = []
    for i, (secret, server_cert, client_cert) in enumerate([
        (bytes(range(32)), b"server-certificate-der-1", b"client-certificate-der-1"),
        (hashlib.sha256(b"secret-2").digest(), b"server-certificate-der-2", b"client-certificate-der-2"),
    ]):
        sfp = hashlib.sha256(server_cert).digest()
        cfp = hashlib.sha256(client_cert).digest()
        cp = hmac.new(secret, b"droiddrop-pair-v1|client|" + sfp + cfp, hashlib.sha256).digest()
        sp = hmac.new(secret, b"droiddrop-pair-v1|server|" + sfp + cfp, hashlib.sha256).digest()
        vectors.append({
            "name": f"vector_{i + 1}",
            "secret": b64url(secret),
            "serverCertificateHex": server_cert.hex(),
            "clientCertificateHex": client_cert.hex(),
            "serverFingerprint": b64url(sfp),
            "clientFingerprint": b64url(cfp),
            "clientProof": b64url(cp),
            "serverProof": b64url(sp),
            "serverDisplayCode": display_code(sfp),
            "clientDisplayCode": display_code(cfp),
        })
    return {"vectors": vectors}


def pair_uri():
    fp = b64url(hashlib.sha256(b"server-certificate-der-1").digest())
    secret = b64url(bytes(range(32)))
    base = f"fp={fp}&secret={secret}"
    expected = {"fingerprint": fp, "secret": secret}
    params = {"v": "v=1", "name": "name=Mac", "hosts": "hosts=192.168.1.20", "port": "port=47321",
              "fp": f"fp={fp}", "secret": f"secret={secret}"}
    query = "&".join(params.values())
    basic = {"name": "Mac", "hosts": ["192.168.1.20"], "port": 47321, **expected}

    def with_param(key, part):
        return "droiddrop://pair?" + "&".join({**params, key: part}.values())

    return {
        "valid": [
            {"name": "basic", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.1.20&port=47321&{base}",
             "expected": {"name": "Mac", "hosts": ["192.168.1.20"], "port": 47321, **expected}},
            {"name": "encoded_name_and_hosts",
             "uri": f"droiddrop://pair?v=1&name=Ivo%27s%20MacBook%20Pro%20%E2%9C%93&hosts=192.168.1.20%2C10.0.0.5&port=50001&{base}",
             "expected": {"name": "Ivo's MacBook Pro ✓", "hosts": ["192.168.1.20", "10.0.0.5"], "port": 50001, **expected}},
            {"name": "literal_plus_is_not_space_and_param_order_free",
             "uri": f"droiddrop://pair?{base}&port=1&hosts=0.0.0.0,255.255.255.255&name=Mac+Mini&v=1&extra=ignored",
             "expected": {"name": "Mac+Mini", "hosts": ["0.0.0.0", "255.255.255.255"], "port": 1, **expected}},
            {"name": "lowercase_hex_escape",
             "uri": f"droiddrop://pair?v=1&name=%c3%a9&hosts=192.168.1.20&port=47321&{base}",
             "expected": {"name": "é", "hosts": ["192.168.1.20"], "port": 47321, **expected}},
            {"name": "unknown_param_repeated", "uri": f"droiddrop://pair?{query}&extra=1&extra=2",
             "expected": basic},
            {"name": "unknown_param_without_equals", "uri": f"droiddrop://pair?{query}&extra",
             "expected": basic},
            {"name": "empty_segments_ignored", "uri": f"droiddrop://pair?&{query.replace('&', '&&', 1)}&",
             "expected": basic},
            {"name": "combining_mark_key_is_unknown", "uri": f"droiddrop://pair?{query}&fp%CC%81=x",
             "expected": basic},
        ],
        "invalid": [
            {"name": "wrong_scheme", "uri": f"https://pair?v=1&name=Mac&hosts=192.168.1.20&port=47321&{base}"},
            {"name": "wrong_host", "uri": f"droiddrop://pairing?v=1&name=Mac&hosts=192.168.1.20&port=47321&{base}"},
            {"name": "version_2", "uri": f"droiddrop://pair?v=2&name=Mac&hosts=192.168.1.20&port=47321&{base}"},
            {"name": "missing_name", "uri": f"droiddrop://pair?v=1&hosts=192.168.1.20&port=47321&{base}"},
            {"name": "missing_fp", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.1.20&port=47321&secret={secret}"},
            {"name": "short_secret", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.1.20&port=47321&fp={fp}&secret={b64url(bytes(16))}"},
            {"name": "port_zero", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.1.20&port=0&{base}"},
            {"name": "port_too_big", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.1.20&port=65536&{base}"},
            {"name": "host_octet_too_big", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.1.256&port=47321&{base}"},
            {"name": "host_leading_zero", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=192.168.01.2&port=47321&{base}"},
            {"name": "host_not_ipv4", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=mac.local&port=47321&{base}"},
            {"name": "empty_hosts", "uri": f"droiddrop://pair?v=1&name=Mac&hosts=&port=47321&{base}"},
            {"name": "port_plus", "uri": with_param("port", "port=+1")},
            {"name": "port_leading_zero", "uri": with_param("port", "port=047321")},
            {"name": "port_non_ascii_digits", "uri": with_param("port", "port=%D9%A4%D9%A7")},
            {"name": "raw_non_ascii", "uri": with_param("name", "name=Mac✓")},
            {"name": "userinfo", "uri": f"droiddrop://user@pair?{query}"},
            {"name": "authority_port", "uri": f"droiddrop://pair:99?{query}"},
            {"name": "empty_authority_port", "uri": f"droiddrop://pair:?{query}"},
            {"name": "path", "uri": f"droiddrop://pair/?{query}"},
            {"name": "uppercase_scheme", "uri": f"DROIDDROP://pair?{query}"},
            {"name": "fragment", "uri": f"droiddrop://pair?{query}#frag"},
            {"name": "duplicate_fp", "uri": f"droiddrop://pair?{query}&fp={fp}"},
            {"name": "duplicate_fp_encoded_key", "uri": f"droiddrop://pair?{query}&%66p={fp}"},
            {"name": "name_without_equals", "uri": with_param("name", "name")},
            {"name": "malformed_escape_trailing", "uri": with_param("name", "name=Mac%")},
            {"name": "malformed_escape_nonhex", "uri": with_param("name", "name=%zz")},
            {"name": "truncated_utf8", "uri": with_param("name", "name=%E2%9C")},
            {"name": "hosts_trailing_comma", "uri": with_param("hosts", "hosts=192.168.1.20,")},
            {"name": "hosts_three_octets", "uri": with_param("hosts", "hosts=1.2.3")},
            {"name": "hosts_five_octets", "uri": with_param("hosts", "hosts=1.2.3.4.5")},
            {"name": "empty_v", "uri": with_param("v", "v=")},
            {"name": "empty_name", "uri": with_param("name", "name=")},
            {"name": "unknown_value_invalid_utf8", "uri": f"droiddrop://pair?{query}&extra=%FF"},
            {"name": "unknown_key_malformed_escape", "uri": f"droiddrop://pair?{query}&%zz=1"},
            {"name": "name_cesu_surrogate", "uri": with_param("name", "name=%ED%A0%80")},
            {"name": "name_overlong_nul", "uri": with_param("name", "name=%C0%80")},
            {"name": "name_above_max_code_point", "uri": with_param("name", "name=%F4%90%80%80")},
            {"name": "duplicate_v_encoded_key", "uri": f"droiddrop://pair?{query}&%76=1"},
            {"name": "fp_noncanonical_tail_bits", "uri": with_param("fp", f"fp={fp[:-1]}{B64_ALPHABET[B64_ALPHABET.index(fp[-1]) ^ 1]}")},
            {"name": "bom_prefixed_known_key", "uri": with_param("v", "%EF%BB%BFv=1")},
        ],
    }


def sanitize():
    return {"cases": [
        {"input": "photo.jpg", "output": "photo.jpg"},
        {"input": "../../etc/passwd", "output": "_.._etc_passwd"},
        {"input": "a/b\\c:d", "output": "a_b_c_d"},
        {"input": "tab\there\u0000nul", "output": "tab_here_nul"},
        {"input": "  .hidden  ", "output": "hidden"},
        {"input": "...", "output": "file"},
        {"input": "   ", "output": "file"},
        {"input": "ünïcödé ✓.txt", "output": "ünïcödé ✓.txt"},
        {"input": "a" * 250 + ".txt", "output": "a" * 196 + ".txt"},
        {"input": "é" * 150, "output": "é" * 100},
        {"input": "b" * 190 + "." + "x" * 20, "output": ("b" * 190 + "." + "x" * 20).encode()[:200].decode()},
    ]}


def main():
    FIXTURES.mkdir(parents=True, exist_ok=True)
    for name, data in {
        "frames": frames(),
        "messages": messages(),
        "pairing": pairing(),
        "pair-uri": pair_uri(),
        "sanitize": sanitize(),
    }.items():
        (FIXTURES / f"{name}.json").write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
