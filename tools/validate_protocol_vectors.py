#!/usr/bin/env python3
"""Validate the framing, hashes, and hostile coverage of shared Protocol v1 vectors."""

from __future__ import annotations

import hashlib
import json
import re
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTORS = ROOT / "protocol/v1/conformance-vectors.json"
JOIN_VECTORS = ROOT / "protocol/v1/join-link-vectors.json"


def fail(message: str) -> None:
    raise ValueError(message)


def reject_duplicate_members(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate corpus member: {key}")
        result[key] = value
    return result


def raw(value: str, label: str) -> bytes:
    if not isinstance(value, str) or len(value) % 2 or not re.fullmatch(r"[0-9a-f]*", value):
        raise ValueError(f"{label} is not lowercase even-length hex")
    try:
        return bytes.fromhex(value)
    except ValueError as error:
        raise ValueError(f"{label} is not lowercase even-length hex") from error


def canonical_json(value: object) -> bytes:
    """Integral-only RFC 8785 bytes, including UTF-16 object-member ordering."""
    if value is None or isinstance(value, (bool, int, str)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if isinstance(value, list):
        return b"[" + b",".join(canonical_json(item) for item in value) + b"]"
    if isinstance(value, dict):
        keys = sorted(value, key=lambda item: item.encode("utf-16-be", "surrogatepass"))
        return b"{" + b",".join(
            canonical_json(key) + b":" + canonical_json(value[key]) for key in keys
        ) + b"}"
    fail(f"unsupported canonical JSON value: {type(value).__name__}")


def validate(path: Path = VECTORS) -> None:
    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicate_members,
        parse_float=lambda value: fail(f"non-integral corpus number: {value}"),
        parse_constant=lambda value: fail(f"invalid corpus number: {value}"),
    )
    if set(value) != {"corpus_format", "hostile", "schema_version", "valid"}:
        fail("corpus root is not closed-world")
    if value["corpus_format"] != "adc-protocol-conformance-v1" or value["schema_version"] != 1:
        fail("corpus identity is wrong")
    valid = value["valid"]
    if set(valid) != {"bundle", "canonical_json", "signed_configuration", "upload_receipt"}:
        fail("valid corpus is incomplete")
    if set(valid["canonical_json"]) != {"canonical_jcs_utf8_hex"}:
        fail("valid canonical JSON fixture is not closed-world")
    jcs = raw(valid["canonical_json"]["canonical_jcs_utf8_hex"], "canonical JSON")
    if canonical_json(json.loads(jcs)) != jcs:
        fail("canonical JSON Unicode fixture is not RFC 8785 ordered")
    config = valid["signed_configuration"]
    if set(config) != {
        "canonical_jcs_sha256",
        "canonical_jcs_utf8_hex",
        "envelope_hex",
        "signature_base64url",
        "signer_key_id",
        "signer_private_key_base64url",
        "signer_public_key_base64url",
    }:
        fail("valid signed configuration fixture is not closed-world")
    canonical = raw(config["canonical_jcs_utf8_hex"], "configuration")
    if canonical_json(json.loads(canonical)) != canonical:
        fail("configuration fixture is not canonical JSON")
    if hashlib.sha256(canonical).hexdigest() != config["canonical_jcs_sha256"]:
        fail("configuration digest mismatch")
    envelope = raw(config["envelope_hex"], "configuration envelope")
    if envelope[:8] != b"ADCCFG01":
        fail("configuration magic mismatch")
    key_length, config_length = struct.unpack(">HI", envelope[8:14])
    if envelope[14 : 14 + key_length].decode() != config["signer_key_id"]:
        fail("configuration key ID mismatch")
    if envelope[14 + key_length : 14 + key_length + config_length] != canonical:
        fail("configuration frame does not contain canonical bytes")
    if len(envelope) != 14 + key_length + config_length + 64:
        fail("configuration frame has the wrong fixed signature tail")
    bundle = valid["bundle"]
    if set(bundle) != {
        "bundle_id",
        "container_hex",
        "content_key_hex",
        "content_nonce_hex",
        "context_jcs_utf8_hex",
        "document_jcs_utf8_hex",
        "hpke_ephemeral_private_key_base64url",
        "hpke_wrapped_content_key_hex",
        "researcher_private_key_base64url",
        "researcher_public_key_base64url",
        "sha256",
    }:
        fail("valid bundle fixture is not closed-world")
    container = raw(bundle["container_hex"], "bundle")
    if container[:8] != b"ADCEXP01" or hashlib.sha256(container).hexdigest() != bundle["sha256"]:
        fail("bundle framing or digest mismatch")
    key_length = struct.unpack(">H", container[56:58])[0]
    key_id = container[70 : 70 + key_length].decode()
    context_bytes = raw(bundle["context_jcs_utf8_hex"], "context")
    context = json.loads(context_bytes)
    if canonical_json(context) != context_bytes:
        fail("bundle context is not canonical JSON")
    if key_id != context["researcher_key_id"] or container[24:56].hex() != context["configuration_sha256"]:
        fail("bundle context does not match framing")
    if len(raw(bundle["hpke_wrapped_content_key_hex"], "wrapped key")) != 80:
        fail("HPKE wrapped key must be 80 bytes")
    receipt = valid["upload_receipt"]
    if set(receipt) != {"canonical_jcs_utf8_hex", "value"} or set(receipt["value"]) != {
        "bundle_id",
        "byte_count",
        "configuration_sha256",
        "event_count",
        "first_sequence_number",
        "last_sequence_number",
        "sha256",
    }:
        fail("valid receipt fixture is not closed-world")
    receipt_bytes = raw(receipt["canonical_jcs_utf8_hex"], "receipt")
    if receipt_bytes != canonical_json(receipt["value"]):
        fail("receipt is not canonical")
    if receipt["value"]["sha256"] != bundle["sha256"] or receipt["value"]["byte_count"] != str(len(container)):
        fail("receipt does not describe the valid bundle")
    hostile = value["hostile"]
    ids = [item["id"] for item in hostile]
    if len(ids) != len(set(ids)):
        fail("hostile vector IDs are not unique")
    required = {
        "canonical_json",
        "configuration_jcs",
        "signed_configuration",
        "bundle",
        "bundle_unwrap_context",
        "receipt",
    }
    if {item["entrypoint"] for item in hostile} != required:
        fail("hostile corpus does not cover every protocol entrypoint")
    required_categories = {
        "body_tampering",
        "catalog_contract",
        "hpke_context",
        "integral_bounds",
        "malformed_length",
        "nonfinite_sensor",
        "old_v1",
        "outer_inner_identity",
        "range_count",
        "raw_key_encoding",
        "signature_input",
        "trailing_bytes",
        "unicode_jcs",
        "unknown_field",
        "unknown_payload",
    }
    actual_categories = {item["category"] for item in hostile}
    if not required_categories <= actual_categories:
        fail(f"hostile corpus misses normative categories: {sorted(required_categories-actual_categories)}")
    for item in hostile:
        if set(item) != {"category", "entrypoint", "expected_failure", "id", "input_hex"}:
            fail(f"hostile vector {item.get('id')} is not closed-world")
        if not all(isinstance(item[key], str) and item[key] for key in ("category", "entrypoint", "expected_failure", "id")):
            fail(f"hostile vector {item.get('id')} has an empty label")
        raw(item["input_hex"], item["id"])


def validate_join(path: Path = JOIN_VECTORS) -> None:
    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicate_members,
        parse_float=lambda item: fail(f"non-integral join corpus number: {item}"),
        parse_constant=lambda item: fail(f"invalid join corpus number: {item}"),
    )
    if set(value) != {"corpus_format", "hostile", "schema_version", "valid"}:
        fail("join corpus root is not closed-world")
    if value["corpus_format"] != "adc-join-link-conformance-v1" or value["schema_version"] != 1:
        fail("join corpus identity is wrong")
    valid = value["valid"]
    if set(valid) != {"artifact_sha256", "artifact_url", "encoded", "signer_fingerprint"}:
        fail("valid join fixture is not closed-world")
    if not isinstance(valid["artifact_sha256"], str) or not re.fullmatch(
        r"[0-9a-f]{64}", valid["artifact_sha256"]
    ):
        fail("valid join digest is malformed")
    if not isinstance(valid["signer_fingerprint"], str) or not re.fullmatch(
        r"[0-9A-F]{32}", valid["signer_fingerprint"]
    ):
        fail("valid join fingerprint is malformed")
    if not isinstance(valid["artifact_url"], str) or not valid["artifact_url"]:
        fail("valid join artifact URL is malformed")
    if (
        not isinstance(valid["encoded"], str)
        or not valid["encoded"].isascii()
        or len(valid["encoded"]) > 4096
    ):
        fail("valid join encoding is malformed")
    hostile = value["hostile"]
    if not isinstance(hostile, list) or not hostile:
        fail("join hostile corpus is empty")
    ids = [item.get("id") for item in hostile]
    if len(ids) != len(set(ids)):
        fail("join hostile vector IDs are not unique")
    for item in hostile:
        if set(item) != {"encoded", "id"}:
            fail(f"hostile join vector {item.get('id')} is not closed-world")
        if not all(isinstance(item[key], str) and item[key] for key in ("encoded", "id")):
            fail("hostile join vector has an empty value")
        if not item["encoded"].isascii() or len(item["encoded"]) > 4096:
            fail(f"hostile join vector {item['id']} is malformed")


if __name__ == "__main__":
    try:
        validate()
        validate_join()
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"protocol vector error: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("valid Protocol v1 conformance corpora")
