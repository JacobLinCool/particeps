#!/usr/bin/env python3
"""Validate the framing, hashes, and hostile coverage of shared Protocol v1 vectors."""

from __future__ import annotations

import hashlib
import json
import math
import re
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTORS = ROOT / "protocol/v1/conformance-vectors.json"
JOIN_VECTORS = ROOT / "protocol/v1/join-link-vectors.json"
EVENT_SOURCE_REGISTRY = ROOT / "protocol/v1/event-source-registry.json"
EVENT_SOURCE_REGISTRY_SHA256 = ROOT / "protocol/v1/generated/event-source-registry.sha256"

ROOT_DOCUMENT_KEYS = {
    "bundle_id",
    "bundle_kind",
    "configuration",
    "configuration_sha256",
    "configuration_signature",
    "event_source_registry_sha256",
    "experiment",
    "exported_at_utc_millis",
    "format",
    "producer",
}
EXPERIMENT_KEYS = {
    "assigned_participant_id",
    "commit_count",
    "commits",
    "configuration_id",
    "durable_through_commit",
    "evaluated_through_commit",
    "event_count",
    "experiment_id",
    "first_commit_sequence",
    "last_commit_sequence",
    "lifetime_data_event_count",
    "next_commit_sequence",
    "participant_instance_id",
    "retained_from_commit",
    "state",
    "uploaded_through_commit",
}
COMMIT_KEYS = {
    "commit_sequence",
    "previous_commit_sha256",
    "input_kind",
    "consumed_pending_input_sha256",
    "source_observations",
    "events",
    "mutations",
    "committed_at",
    "successor_projection",
    "resulting_checkpoint_sha256",
    "commit_sha256",
}
OBSERVATION_KEYS = {
    "observation_sequence",
    "source_id",
    "schema_version",
    "resource_generation",
    "admission_kind",
    "producer_ordinal",
    "condition_epoch_id",
    "event_count",
    "first_event_sequence",
    "last_event_sequence",
    "coverage",
    "encoded_sha256",
}
EVENT_KEYS = {
    "sequence_number",
    "source_id",
    "schema_version",
    "event_type",
    "observed_time",
    "condition_epoch_id",
    "fields",
}
MUTATION_KEYS = {"component_kind", "component_id", "operation", "canonical_value"}
PROJECTION_KEYS = {
    "state",
    "revision",
    "next_commit_sequence",
    "next_observation_sequence",
    "next_event_sequence",
    "source_checkpoints",
    "clock_checkpoint",
    "active_condition_epoch",
    "lifetime_data_event_count",
    "uploaded_through_commit",
    "evaluated_through_commit",
    "retained_from_commit",
}
TIME_KEYS = {"wall_time_utc_millis", "elapsed_realtime_nanos", "boot_session_id"}
COVERAGE_KEYS = {"clock_basis", "start_inclusive", "end_exclusive"}
SOURCE_CHECKPOINT_KEYS = {
    "source_id",
    "resource_generation",
    "next_producer_ordinal",
    "coverage",
    "cursor",
}
EPOCH_KEYS = {
    "id",
    "configuration_sha256",
    "applied_resource_vector_sha256",
    "activated_at",
}
CLOCK_KEYS = {
    "calendar_elapsed_nanos",
    "active_running_elapsed_nanos",
    "anchor",
    "deadline_utc_millis",
    "deadline_utc_trusted",
    "zone_id",
}
DECIMAL = re.compile(r"0|[1-9][0-9]*")
SIGNED_DECIMAL = re.compile(r"0|-?[1-9][0-9]*")
FLOAT_DECIMAL = re.compile(r"[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")
LOWER_SHA256 = re.compile(r"[0-9a-f]{64}")
UUID_V4 = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")


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


def require_exact_object(value: object, keys: set[str], label: str) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != keys:
        fail(f"{label} is not a closed-world object")
    return value


def decimal(value: object, label: str, *, positive: bool = False) -> int:
    if not isinstance(value, str) or DECIMAL.fullmatch(value) is None:
        fail(f"{label} is not a canonical decimal string")
    parsed = int(value)
    if parsed > 9_223_372_036_854_775_807 or (positive and parsed == 0):
        fail(f"{label} is outside signed int64")
    return parsed


class BinaryWriter:
    def __init__(self) -> None:
        self.parts: list[bytes] = []

    def int(self, value: int) -> None:
        self.parts.append(struct.pack(">i", value))

    def long(self, value: object) -> None:
        self.parts.append(struct.pack(">q", decimal(value, "binary int64")))

    def boolean(self, value: bool) -> None:
        if not isinstance(value, bool):
            fail("binary boolean has the wrong type")
        self.parts.append(b"\x01" if value else b"\x00")

    def string(self, value: object) -> None:
        if not isinstance(value, str):
            fail("binary string has the wrong type")
        encoded = value.encode("utf-8")
        self.int(len(encoded))
        self.parts.append(encoded)

    def nullable(self, value: object, encode: object) -> None:
        present = value is not None
        self.boolean(present)
        if present:
            encode(value)  # type: ignore[operator]

    def list(self, values: object, encode: object) -> None:
        if not isinstance(values, list):
            fail("binary list has the wrong type")
        self.int(len(values))
        for value in values:
            encode(value)  # type: ignore[operator]

    def digest(self) -> str:
        return hashlib.sha256(b"".join(self.parts)).hexdigest()


def write_time(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, TIME_KEYS, "ResearchTime")
    writer.long(item["wall_time_utc_millis"])
    writer.long(item["elapsed_realtime_nanos"])
    writer.string(item["boot_session_id"])


def write_coverage(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, COVERAGE_KEYS, "source coverage")
    writer.string(item["clock_basis"])
    writer.string(item["start_inclusive"])
    writer.string(item["end_exclusive"])


def write_event(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, EVENT_KEYS, "recorded event")
    writer.long(item["sequence_number"])
    writer.string(item["source_id"])
    if not isinstance(item["schema_version"], int):
        fail("event schema_version must be an integer")
    writer.int(item["schema_version"])
    writer.string(item["event_type"])
    write_time(writer, item["observed_time"])
    writer.nullable(item["condition_epoch_id"], writer.string)
    fields = item["fields"]
    if not isinstance(fields, dict):
        fail("event fields must be an object")
    writer.int(len(fields))
    for key in sorted(fields):
        writer.string(key)
        writer.string(fields[key])


def write_observation(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, OBSERVATION_KEYS, "source observation")
    writer.long(item["observation_sequence"])
    writer.string(item["source_id"])
    if not isinstance(item["schema_version"], int):
        fail("observation schema_version must be an integer")
    writer.int(item["schema_version"])
    writer.long(item["resource_generation"])
    writer.string(item["admission_kind"])
    writer.long(item["producer_ordinal"])
    writer.string(item["condition_epoch_id"])
    if not isinstance(item["event_count"], int):
        fail("observation event_count must be an integer")
    writer.int(item["event_count"])
    writer.nullable(item["first_event_sequence"], writer.long)
    writer.nullable(item["last_event_sequence"], writer.long)
    writer.nullable(item["coverage"], lambda nested: write_coverage(writer, nested))
    writer.string(item["encoded_sha256"])


def write_mutation(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, MUTATION_KEYS, "runtime mutation")
    writer.string(item["component_kind"])
    writer.string(item["component_id"])
    writer.string(item["operation"])
    writer.nullable(item["canonical_value"], writer.string)


def write_epoch(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, EPOCH_KEYS, "condition epoch")
    writer.string(item["id"])
    writer.string(item["configuration_sha256"])
    writer.string(item["applied_resource_vector_sha256"])
    write_time(writer, item["activated_at"])


def write_clock(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, CLOCK_KEYS, "clock checkpoint")
    writer.long(item["calendar_elapsed_nanos"])
    writer.long(item["active_running_elapsed_nanos"])
    write_time(writer, item["anchor"])
    writer.long(item["deadline_utc_millis"])
    writer.boolean(item["deadline_utc_trusted"])  # type: ignore[arg-type]
    writer.string(item["zone_id"])  # type: ignore[arg-type]


def write_projection(writer: BinaryWriter, value: object) -> None:
    item = require_exact_object(value, PROJECTION_KEYS, "successor projection")
    writer.string(item["state"])
    writer.long(item["revision"])
    writer.long(item["next_commit_sequence"])
    writer.long(item["next_observation_sequence"])
    writer.long(item["next_event_sequence"])
    checkpoints = item["source_checkpoints"]
    if not isinstance(checkpoints, dict):
        fail("source_checkpoints must be an object")
    writer.int(len(checkpoints))
    for source_id in sorted(checkpoints):
        checkpoint = require_exact_object(
            checkpoints[source_id], SOURCE_CHECKPOINT_KEYS, "source checkpoint"
        )
        writer.string(source_id)
        writer.string(checkpoint["source_id"])
        writer.long(checkpoint["resource_generation"])
        writer.long(checkpoint["next_producer_ordinal"])
        writer.nullable(checkpoint["coverage"], lambda nested: write_coverage(writer, nested))
        writer.nullable(checkpoint["cursor"], writer.string)
    writer.nullable(item["clock_checkpoint"], lambda nested: write_clock(writer, nested))
    writer.nullable(item["active_condition_epoch"], lambda nested: write_epoch(writer, nested))
    writer.long(item["lifetime_data_event_count"])
    writer.long(item["uploaded_through_commit"])
    writer.long(item["evaluated_through_commit"])
    writer.long(item["retained_from_commit"])


def commit_sha256(value: object) -> str:
    commit = require_exact_object(value, COMMIT_KEYS, "engine commit")
    writer = BinaryWriter()
    writer.string("particeps-engine-commit-v1")
    writer.long(commit["commit_sequence"])
    writer.string(commit["previous_commit_sha256"])
    writer.string(commit["input_kind"])
    writer.nullable(commit["consumed_pending_input_sha256"], writer.string)
    writer.list(commit["source_observations"], lambda item: write_observation(writer, item))
    writer.list(commit["events"], lambda item: write_event(writer, item))
    writer.list(commit["mutations"], lambda item: write_mutation(writer, item))
    write_time(writer, commit["committed_at"])
    write_projection(writer, commit["successor_projection"])
    writer.string(commit["resulting_checkpoint_sha256"])
    return writer.digest()


def observation_sha256(observation: object, events: list[object]) -> str:
    item = require_exact_object(observation, OBSERVATION_KEYS, "source observation")
    writer = BinaryWriter()
    writer.string("particeps-source-observation-v1")
    writer.string(item["source_id"])
    if not isinstance(item["schema_version"], int):
        fail("observation schema_version must be an integer")
    writer.int(item["schema_version"])
    writer.long(item["resource_generation"])
    writer.long(item["producer_ordinal"])
    writer.string(item["condition_epoch_id"])
    writer.boolean(item["coverage"] is not None)
    if item["coverage"] is not None:
        write_coverage(writer, item["coverage"])
    writer.int(len(events))
    for raw_event in events:
        event = require_exact_object(raw_event, EVENT_KEYS, "recorded event")
        writer.string(event["event_type"])
        observed_time = require_exact_object(event["observed_time"], TIME_KEYS, "ResearchTime")
        writer.long(observed_time["wall_time_utc_millis"])
        writer.long(observed_time["elapsed_realtime_nanos"])
        writer.string(observed_time["boot_session_id"])
        fields = event["fields"]
        if not isinstance(fields, dict):
            fail("event fields must be an object")
        writer.int(len(fields))
        for key in sorted(fields):
            writer.string(key)
            writer.string(fields[key])
    return writer.digest()


def validate_field(raw: object, definition: dict[str, object], label: str) -> None:
    if not isinstance(raw, str):
        fail(f"{label} is not a canonical string")
    wire_type = definition["wire_type"]
    minimum = definition.get("minimum")
    maximum = definition.get("maximum")
    if wire_type == "boolean":
        if raw not in {"false", "true"}:
            fail(f"{label} is not a canonical boolean")
    elif wire_type in {"int32", "int64"}:
        if SIGNED_DECIMAL.fullmatch(raw) is None:
            fail(f"{label} is not a canonical integer")
        parsed = int(raw)
        if wire_type == "int32" and not -(2**31) <= parsed < 2**31:
            fail(f"{label} exceeds int32")
        if wire_type == "int64" and not -(2**63) <= parsed < 2**63:
            fail(f"{label} exceeds int64")
        if isinstance(minimum, int) and parsed < minimum:
            fail(f"{label} is below its registry bound")
        if isinstance(maximum, int) and parsed > maximum:
            fail(f"{label} is above its registry bound")
    elif wire_type in {"float32", "float64"}:
        if FLOAT_DECIMAL.fullmatch(raw) is None or not math.isfinite(float(raw)):
            fail(f"{label} is not a finite canonical float")
    elif wire_type == "enum":
        values = definition.get("enum_values")
        if not isinstance(values, list) or raw not in values:
            fail(f"{label} is not a registry enum")
    elif wire_type == "sha256_hex":
        if LOWER_SHA256.fullmatch(raw) is None:
            fail(f"{label} is not a SHA-256 digest")
    elif wire_type == "uuid":
        if UUID_V4.fullmatch(raw) is None:
            fail(f"{label} is not a UUIDv4")
    elif wire_type == "json_string":
        parsed = json.loads(raw, object_pairs_hook=reject_duplicate_members)
        if canonical_json(parsed).decode("utf-8") != raw:
            fail(f"{label} is not canonical embedded JSON")
    minimum_length = definition.get("minimum_length")
    maximum_length = definition.get("maximum_length")
    if isinstance(minimum_length, int) and len(raw) < minimum_length:
        fail(f"{label} is shorter than its registry bound")
    if isinstance(maximum_length, int) and len(raw) > maximum_length:
        fail(f"{label} is longer than its registry bound")


def validate(path: Path = VECTORS) -> None:
    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicate_members,
        parse_float=lambda value: fail(f"non-integral corpus number: {value}"),
        parse_constant=lambda value: fail(f"invalid corpus number: {value}"),
    )
    if set(value) != {"corpus_format", "hostile", "schema_version", "valid"}:
        fail("corpus root is not closed-world")
    if value["corpus_format"] != "particeps-protocol-conformance-v1" or value["schema_version"] != 1:
        fail("corpus identity is wrong")
    valid = value["valid"]
    if set(valid) != {
        "bundle",
        "canonical_json",
        "signed_configuration",
        "upload_request",
        "upload_receipt",
    }:
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
    if envelope[:8] != b"PTCCFG01":
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
    if container[:8] != b"PTCEXP01" or hashlib.sha256(container).hexdigest() != bundle["sha256"]:
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
    document_bytes = raw(bundle["document_jcs_utf8_hex"], "bundle document")
    document = json.loads(document_bytes)
    if canonical_json(document) != document_bytes:
        fail("bundle document is not canonical JSON")
    document = require_exact_object(document, ROOT_DOCUMENT_KEYS, "bundle document")
    registry_digest = EVENT_SOURCE_REGISTRY_SHA256.read_text(encoding="utf-8").strip()
    if LOWER_SHA256.fullmatch(registry_digest) is None:
        fail("generated registry digest is malformed")
    if document["event_source_registry_sha256"] != registry_digest:
        fail("bundle is not bound to the generated event-source registry")
    if document["configuration_sha256"] != config["canonical_jcs_sha256"]:
        fail("bundle does not embed the signed configuration digest")
    if canonical_json(document["configuration"]) != canonical:
        fail("bundle configuration is not the signed configuration fixture")
    experiment = require_exact_object(document["experiment"], EXPERIMENT_KEYS, "experiment")
    commits = experiment["commits"]
    if not isinstance(commits, list) or not commits:
        fail("valid automatic-upload bundle has no commits")
    first_commit = decimal(experiment["first_commit_sequence"], "first commit", positive=True)
    last_commit = decimal(experiment["last_commit_sequence"], "last commit", positive=True)
    commit_count = decimal(experiment["commit_count"], "commit count", positive=True)
    if commit_count != len(commits) or last_commit - first_commit + 1 != commit_count:
        fail("bundle commit range/count is inconsistent")
    event_count = decimal(experiment["event_count"], "event count")
    all_events: list[dict[str, object]] = []
    active_epoch: str | None = None
    previous_commit_digest: str | None = None
    expected_event_sequence: int | None = None
    expected_observation_sequence = 1
    next_producer_ordinal: dict[str, int] = {}

    registry = json.loads(EVENT_SOURCE_REGISTRY.read_text(encoding="utf-8"))
    contracts: dict[tuple[str, int, str], dict[str, object]] = {}
    source_kinds: dict[tuple[str, int], str] = {}
    for source in registry["sources"]:
        source_kinds[(source["source_id"], source["schema_version"])] = source["source_kind"]
        for event_contract in source["events"]:
            contracts[(source["source_id"], source["schema_version"], event_contract["event_type"])] = event_contract

    for index, raw_commit in enumerate(commits):
        commit = require_exact_object(raw_commit, COMMIT_KEYS, "engine commit")
        sequence = decimal(commit["commit_sequence"], "commit sequence", positive=True)
        if sequence != first_commit + index:
            fail("commit sequence is not contiguous")
        if commit["commit_sha256"] != commit_sha256(commit):
            fail("engine commit digest mismatch")
        if previous_commit_digest is not None and commit["previous_commit_sha256"] != previous_commit_digest:
            fail("engine commit predecessor mismatch")
        previous_commit_digest = commit["commit_sha256"]  # type: ignore[assignment]
        projection = require_exact_object(
            commit["successor_projection"], PROJECTION_KEYS, "successor projection"
        )
        if decimal(projection["revision"], "projection revision") != sequence:
            fail("projection revision does not equal commit sequence")
        if decimal(projection["next_commit_sequence"], "next commit") != sequence + 1:
            fail("projection next commit does not follow its commit")
        commit_events = commit["events"]
        if not isinstance(commit_events, list):
            fail("commit events must be an array")
        typed_events: list[dict[str, object]] = []
        for raw_event in commit_events:
            event = require_exact_object(raw_event, EVENT_KEYS, "recorded event")
            event_sequence = decimal(event["sequence_number"], "event sequence", positive=True)
            if expected_event_sequence is not None and event_sequence != expected_event_sequence:
                fail("event sequence is not contiguous")
            expected_event_sequence = event_sequence + 1
            identity = (event["source_id"], event["schema_version"], event["event_type"])
            contract = contracts.get(identity)  # type: ignore[arg-type]
            if contract is None:
                fail(f"event identity is absent from registry: {identity}")
            fields = event["fields"]
            definitions = contract["fields"]
            if not isinstance(fields, dict) or not isinstance(definitions, dict):
                fail(f"event field set disagrees with registry: {identity}")
            required_fields = {
                field for field, definition in definitions.items()
                if definition.get("required") is True
            }
            if not required_fields.issubset(fields) or not set(fields).issubset(definitions):
                fail(f"event field set disagrees with registry: {identity}")
            for field, field_value in fields.items():
                validate_field(field_value, definitions[field], f"{identity}.{field}")
            event_epoch = event["condition_epoch_id"]
            if identity == ("study_condition.v1", 1, "CONDITION_EPOCH_ACTIVATED"):
                if active_epoch is not None or event_epoch != fields["condition_epoch_id"]:
                    fail("condition epoch activation is inconsistent")
                active_epoch = event_epoch  # type: ignore[assignment]
            elif identity == ("study_condition.v1", 1, "CONDITION_EPOCH_DEACTIVATED"):
                if active_epoch is None or event_epoch != active_epoch:
                    fail("condition epoch deactivation is inconsistent")
                active_epoch = None
            elif source_kinds[(identity[0], identity[1])] == "COLLECTOR":
                if event_epoch is None or event_epoch != active_epoch:
                    fail("data event has a missing or orphan condition epoch")
            elif event_epoch != active_epoch:
                fail("system event condition epoch disagrees with runtime state")
            typed_events.append(event)
            all_events.append(event)
        observations = commit["source_observations"]
        if not isinstance(observations, list):
            fail("source observations must be an array")
        for raw_observation in observations:
            observation = require_exact_object(raw_observation, OBSERVATION_KEYS, "source observation")
            observation_sequence = decimal(
                observation["observation_sequence"], "observation sequence", positive=True
            )
            if observation_sequence != expected_observation_sequence:
                fail("observation sequence is not contiguous")
            expected_observation_sequence += 1
            source_id = observation["source_id"]
            if not isinstance(source_id, str):
                fail("observation source ID is not a string")
            producer_ordinal = decimal(observation["producer_ordinal"], "producer ordinal")
            if producer_ordinal != next_producer_ordinal.get(source_id, 0):
                fail("source producer ordinal is not contiguous")
            next_producer_ordinal[source_id] = producer_ordinal + 1
            observed_count = observation["event_count"]
            if not isinstance(observed_count, int) or not 0 <= observed_count <= 4096:
                fail("observation event_count is invalid")
            if observed_count == 0:
                if observation["first_event_sequence"] is not None or observation["last_event_sequence"] is not None or observation["coverage"] is None:
                    fail("zero-event observation does not advance coverage")
                observation_events: list[object] = []
            else:
                first_event = decimal(
                    observation["first_event_sequence"], "observation first event", positive=True
                )
                last_event = decimal(
                    observation["last_event_sequence"], "observation last event", positive=True
                )
                observation_events = [
                    event
                    for event in typed_events
                    if first_event <= int(event["sequence_number"]) <= last_event
                ]
                if last_event - first_event + 1 != observed_count or len(observation_events) != observed_count:
                    fail("source observation does not cover an exact local event range")
                for event in observation_events:
                    if (
                        event["source_id"] != source_id
                        or event["schema_version"] != observation["schema_version"]
                        or event["condition_epoch_id"] != observation["condition_epoch_id"]
                    ):
                        fail("source observation identity disagrees with an event")
            if observation["encoded_sha256"] != observation_sha256(observation, observation_events):
                fail("source observation digest mismatch")
    if len(all_events) != event_count:
        fail("bundle event_count does not equal commit events")
    if decimal(experiment["durable_through_commit"], "durable through commit") != last_commit:
        fail("bundle durable watermark does not cover its final commit")
    final_projection = commits[-1]["successor_projection"]
    final_epoch = final_projection["active_condition_epoch"]
    if active_epoch is None or not isinstance(final_epoch, dict) or final_epoch.get("id") != active_epoch:
        fail("final active condition epoch diverges from replay")
    request = valid["upload_request"]
    if set(request) != {"bundle_format", "media_type", "routing_headers"}:
        fail("valid upload request fixture is not closed-world")
    headers = request["routing_headers"]
    if (
        not isinstance(headers, list)
        or len(headers) != 8
        or len(set(headers)) != 8
        or sorted(headers) != headers
    ):
        fail("upload routing headers must be eight sorted distinct names")
    if not all(isinstance(name, str) and name.startswith("X-Particeps-") for name in headers):
        fail("upload routing headers must all be X-Particeps-* names")
    receipt = valid["upload_receipt"]
    if set(receipt) != {"canonical_jcs_utf8_hex", "value"} or set(receipt["value"]) != {
        "bundle_id",
        "byte_count",
        "commit_count",
        "configuration_sha256",
        "event_count",
        "first_commit_sequence",
        "last_commit_sequence",
        "sha256",
    }:
        fail("valid receipt fixture is not closed-world")
    receipt_bytes = raw(receipt["canonical_jcs_utf8_hex"], "receipt")
    if receipt_bytes != canonical_json(receipt["value"]):
        fail("receipt is not canonical")
    if receipt["value"]["sha256"] != bundle["sha256"] or receipt["value"]["byte_count"] != str(len(container)):
        fail("receipt does not describe the valid bundle")
    if (
        receipt["value"]["commit_count"] != experiment["commit_count"]
        or receipt["value"]["event_count"] != experiment["event_count"]
        or receipt["value"]["first_commit_sequence"] != experiment["first_commit_sequence"]
        or receipt["value"]["last_commit_sequence"] != experiment["last_commit_sequence"]
    ):
        fail("receipt does not describe the exported commit range")
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
        "commit_chain",
        "condition_epoch",
        "event_contract",
        "hpke_context",
        "integral_bounds",
        "malformed_length",
        "observation_manifest",
        "old_v1",
        "outer_inner_identity",
        "raw_key_encoding",
        "registry_binding",
        "signature_input",
        "trailing_bytes",
        "unicode_jcs",
        "unknown_field",
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
    if value["corpus_format"] != "particeps-join-link-conformance-v1" or value["schema_version"] != 1:
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
