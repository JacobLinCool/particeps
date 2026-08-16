"""Streaming whole-bundle Protocol v1 authentication and semantic verification."""

from __future__ import annotations

import hashlib
import os
import tempfile
import uuid
from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from .catalog import CollectorCatalog
from .configuration import validate_configuration
from .crypto import open_base, public_key
from .encoding import base64url_decode, protocol_id, sha256_hex, uuid4_text, uuid_text
from .errors import ValidationError
from .filesystem import private_directory
from .jcs import canonical_decimal, canonicalize, exact_object, parse
from .limits import (
    AUTOMATIC_UPLOAD_MAX_BYTES,
    MANUAL_EXPORT_MAX_BYTES,
    SIGNED_CONFIGURATION_MAX_BYTES,
)
from .models import EventProvenance, InventoryObject, VerifiedBundle, VerifiedEvent
from .streaming_json import BoundedObjectBuilder, CanonicalJsonEvents

MAGIC = b"PTCEXP01"
ROOT_KEYS = {
    "bundle_id",
    "bundle_kind",
    "configuration",
    "configuration_sha256",
    "configuration_signature",
    "experiment",
    "exported_at_utc_millis",
    "format",
    "producer",
}
EXPERIMENT_KEYS = {
    "assigned_participant_id",
    "configuration_id",
    "durable_through_sequence",
    "event_count",
    "events",
    "experiment_id",
    "first_sequence_number",
    "last_sequence_number",
    "next_sequence_number",
    "participant_instance_id",
    "retained_from_sequence",
    "state",
    "transitions",
    "uploaded_through_sequence",
}
EVENT_KEYS = {
    "sequence_number",
    "collector_id",
    "payload_schema_version",
    "observed_time",
    "payload_type",
    "fields",
}
TIME_KEYS = {"boot_session_id", "monotonic_time_nanos", "wall_time_utc_millis"}
ROOT_SCALARS = ROOT_KEYS - {
    "configuration",
    "configuration_signature",
    "experiment",
    "producer",
}
EXPERIMENT_SCALARS = EXPERIMENT_KEYS - {"events", "transitions"}
SCALAR_EVENTS = {"null", "boolean", "integer", "number", "string"}
STATES = {
    "IMPORTED",
    "CONFIG_VERIFIED",
    "CONSENT_PENDING",
    "ACCESS_SETUP",
    "READY",
    "RUNNING",
    "PAUSED",
    "COMPLETED",
    "WITHDRAWN",
}
REASON_DESTINATION = {
    "CONFIGURATION_SIGNATURE_VERIFIED": "CONFIG_VERIFIED",
    "CONSENT_REVIEW_OPENED": "CONSENT_PENDING",
    "CONSENT_ACCEPTED": "ACCESS_SETUP",
    "ACCESS_PREFLIGHT_PASSED": "READY",
    "PARTICIPANT_STARTED": "RUNNING",
    "PARTICIPANT_PAUSED": "PAUSED",
    "DEVICE_REBOOT": "PAUSED",
    "AUTOMATIC_RECOVERY": "RUNNING",
    "PARTICIPANT_RESUMED": "RUNNING",
    "STUDY_DURATION_ELAPSED": "COMPLETED",
    "PARTICIPANT_WITHDREW": "WITHDRAWN",
    "COLLECTION_HOST_FAILURE": "PAUSED",
    "COLLECTION_TEARDOWN_FAILURE": "PAUSED",
    "REQUIRED_ACCESS_MISSING": "PAUSED",
    "STORAGE_FAILURE": "PAUSED",
    "WORK_SCHEDULING_FAILURE": "PAUSED",
}
ALLOWED_TRANSITIONS = {
    "IMPORTED": {"CONFIG_VERIFIED", "WITHDRAWN"},
    "CONFIG_VERIFIED": {"CONSENT_PENDING", "WITHDRAWN"},
    "CONSENT_PENDING": {"ACCESS_SETUP", "WITHDRAWN"},
    "ACCESS_SETUP": {"READY", "WITHDRAWN"},
    "READY": {"RUNNING", "WITHDRAWN"},
    "RUNNING": {"PAUSED", "COMPLETED", "WITHDRAWN"},
    "PAUSED": {"RUNNING", "COMPLETED", "WITHDRAWN"},
    "COMPLETED": {"WITHDRAWN"},
    "WITHDRAWN": set(),
}


@dataclass(frozen=True, slots=True)
class _EventContext:
    configuration: Mapping[str, Any]
    participant_id: str
    configured_collectors: frozenset[str]
    provenance: EventProvenance
    first_sequence: int
    event_count: int


class _VerifiedEventSpool:
    """Owner-only, integrity-checked event stream for every verified bundle."""

    def __init__(
        self,
        path: Path,
        verifier: BundleVerifier,
        context: _EventContext,
        sha256: str,
        byte_count: int,
    ):
        self.path = path
        self.verifier = verifier
        self.context = context
        self.sha256 = sha256
        self.byte_count = byte_count
        self.closed = False

    def __iter__(self) -> Iterator[VerifiedEvent]:
        if self.closed:
            raise ValidationError("verified event spool is closed")
        yield from self.verifier._iter_event_file(
            self.path, self.context, self.sha256, self.byte_count
        )

    def __len__(self) -> int:
        return self.context.event_count

    def close(self) -> None:
        if not self.closed:
            self.path.unlink(missing_ok=True)
            self.closed = True

    def __del__(self) -> None:
        try:
            self.close()
        except OSError:
            pass


class BundleVerifier:
    def __init__(
        self,
        catalog: CollectorCatalog,
        researcher_private_keys: Mapping[str, bytes],
        staging_directory: Path,
    ):
        self.catalog = catalog
        self.keys = dict(researcher_private_keys)
        if not self.keys:
            raise ValidationError("at least one researcher key is required")
        for key_id, key in self.keys.items():
            protocol_id(key_id, "researcher key ID")
            if not isinstance(key, bytes) or len(key) != 32:
                raise ValidationError(
                    "researcher private keys must be raw 32-byte values"
                )
        self.staging = private_directory(staging_directory)

    def verify(self, source: InventoryObject) -> VerifiedBundle:
        maximum = (
            AUTOMATIC_UPLOAD_MAX_BYTES
            if source.source_kind == "receiver"
            else MANUAL_EXPORT_MAX_BYTES
        )
        if not 1 <= source.byte_count <= maximum:
            raise ValidationError("ciphertext size is outside its source bound")
        fd, name = tempfile.mkstemp(
            prefix="particeps-plaintext-", suffix=".json", dir=self.staging
        )
        plaintext_path = Path(name)
        try:
            os.fchmod(fd, 0o600)
            with (
                source.cache_path.open("rb", buffering=0) as encoded,
                os.fdopen(fd, "wb", buffering=0) as plaintext,
            ):
                outer, private_key, plaintext_sha256, plaintext_bytes = (
                    self._decrypt_to(encoded, plaintext, source)
                )
                plaintext.flush()
                os.fsync(plaintext.fileno())
            if plaintext_path.stat().st_mode & 0o077:
                raise ValidationError("plaintext staging permissions are not private")
            return self._validate_staged(
                plaintext_path,
                plaintext_sha256,
                plaintext_bytes,
                outer,
                source,
                private_key,
            )
        finally:
            plaintext_path.unlink(missing_ok=True)

    def _decrypt_to(
        self, encoded, plaintext, source: InventoryObject
    ) -> tuple[dict[str, Any], bytes, str, int]:
        if os.fstat(encoded.fileno()).st_size != source.byte_count:
            raise ValidationError("ciphertext size does not match inventory")
        ciphertext_digest = hashlib.sha256()
        count = 0

        def read_exact(length: int) -> bytes:
            nonlocal count
            chunks = bytearray()
            while len(chunks) < length:
                chunk = encoded.read(length - len(chunks))
                if not chunk:
                    raise ValidationError("bundle container is truncated")
                chunks.extend(chunk)
                count += len(chunk)
                ciphertext_digest.update(chunk)
            return bytes(chunks)

        fixed = read_exact(70)
        if fixed[:8] != MAGIC:
            raise ValidationError("unsupported bundle container format")
        bundle_id = uuid4_text(
            str(uuid.UUID(bytes=fixed[8:24])), "outer bundle ID"
        )
        key_length = int.from_bytes(fixed[56:58], "big")
        if not 3 <= key_length <= 64:
            raise ValidationError("researcher key ID length is invalid")
        if source.byte_count <= 150 + key_length + 16:
            raise ValidationError("bundle container size is invalid")
        key_and_wrapped = read_exact(key_length + 80)
        try:
            researcher_key_id = key_and_wrapped[:key_length].decode(
                "utf-8", errors="strict"
            )
        except UnicodeDecodeError as error:
            raise ValidationError("researcher key ID is malformed UTF-8") from error
        protocol_id(researcher_key_id, "researcher key ID")
        try:
            private_key = self.keys[researcher_key_id]
        except KeyError as error:
            raise ValidationError("no private key for researcher key ID") from error
        outer = {
            "bundle_id": bundle_id,
            "configuration_sha256": fixed[24:56].hex(),
            "content_nonce": fixed[58:70],
            "researcher_key_id": researcher_key_id,
            "wrapped_key": key_and_wrapped[key_length:],
        }
        context = _bundle_context(outer)
        content_key = open_base(private_key, outer["wrapped_key"], context)
        if len(content_key) != 32:
            raise ValidationError("HPKE content key length is invalid")

        decryptor = Cipher(
            algorithms.AES(content_key), modes.GCM(outer["content_nonce"])
        ).decryptor()
        decryptor.authenticate_additional_data(context)
        tail = b""
        plaintext_digest = hashlib.sha256()
        plaintext_count = 0
        while chunk := encoded.read(1024 * 1024):
            count += len(chunk)
            if count > source.byte_count:
                raise ValidationError("ciphertext grew while it was being read")
            ciphertext_digest.update(chunk)
            buffered = tail + chunk
            if len(buffered) <= 16:
                tail = buffered
                continue
            body, tail = buffered[:-16], buffered[-16:]
            decoded = decryptor.update(body)
            plaintext.write(decoded)
            plaintext_digest.update(decoded)
            plaintext_count += len(decoded)
        if count != source.byte_count:
            raise ValidationError("ciphertext size does not match inventory")
        if ciphertext_digest.hexdigest() != source.sha256:
            raise ValidationError("ciphertext digest does not match inventory")
        if len(tail) != 16:
            raise ValidationError("bundle content authentication tag is missing")
        try:
            decoded = decryptor.finalize_with_tag(tail)
        except (ValueError, InvalidTag) as error:
            raise ValidationError("bundle content authentication failed") from error
        plaintext.write(decoded)
        plaintext_digest.update(decoded)
        plaintext_count += len(decoded)
        return outer, private_key, plaintext_digest.hexdigest(), plaintext_count

    def _validate_staged(
        self,
        path: Path,
        plaintext_sha256: str,
        plaintext_bytes: int,
        outer: Mapping[str, Any],
        source: InventoryObject,
        private_key: bytes,
    ) -> VerifiedBundle:
        fd, name = tempfile.mkstemp(
            prefix="particeps-events-", suffix=".jsonl", dir=self.staging
        )
        event_path = Path(name)
        keep_event_path = False
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as event_stream:
                parsed = self._stream_document(
                    path,
                    plaintext_sha256,
                    plaintext_bytes,
                    event_stream,
                    outer,
                    source,
                    private_key,
                )
                event_stream.flush()
                os.fsync(event_stream.fileno())
            bundle = self._finish_document(parsed, event_path, outer, source, private_key)
            keep_event_path = isinstance(bundle.events, _VerifiedEventSpool)
            return bundle
        finally:
            if not keep_event_path:
                event_path.unlink(missing_ok=True)

    def _stream_document(
        self,
        path: Path,
        plaintext_sha256: str,
        plaintext_bytes: int,
        event_stream,
        outer: Mapping[str, Any],
        source: InventoryObject,
        private_key: bytes,
    ) -> dict[str, Any]:
        root_keys: set[str] = set()
        experiment_keys: set[str] = set()
        root_values: dict[str, Any] = {}
        experiment_values: dict[str, Any] = {}
        configuration = None
        signature = None
        producer = None
        active_name: str | None = None
        active: BoundedObjectBuilder | None = None
        root_started = False
        experiment_started = False
        events_started = False
        transitions_started = False
        parsed_events = 0
        transition_previous = "IMPORTED"

        for prefix, event, value in CanonicalJsonEvents(
            path, plaintext_sha256, plaintext_bytes
        ):
            if active is not None:
                active.feed(event, value)
                if active.complete:
                    built = active.value
                    if active_name == "configuration":
                        configuration = validate_configuration(built, self.catalog)
                    elif active_name == "configuration_signature":
                        signature = built
                    elif active_name == "producer":
                        producer = built
                    elif active_name == "event":
                        encoded_event = canonicalize(built)
                        event_stream.write(encoded_event)
                        event_stream.write(b"\n")
                        parsed_events += 1
                    elif active_name == "transition":
                        transition_previous = _validate_transition(
                            built, transition_previous
                        )
                    active_name = None
                    active = None
                continue

            if prefix == "" and event == "start_map":
                root_started = True
                continue
            if prefix == "" and event == "map_key":
                if value not in ROOT_KEYS:
                    raise ValidationError(f"unknown bundle document member: {value}")
                root_keys.add(value)
                continue
            if prefix == "experiment" and event == "start_map":
                experiment_started = True
                continue
            if prefix == "experiment" and event == "map_key":
                if value not in EXPERIMENT_KEYS:
                    raise ValidationError(f"unknown experiment snapshot member: {value}")
                experiment_keys.add(value)
                continue
            if prefix == "experiment.events" and event == "start_array":
                events_started = True
                continue
            if prefix == "experiment.transitions" and event == "start_array":
                transitions_started = True
                continue

            subtree = _subtree_target(prefix, event, self.catalog.maximum_event_bytes)
            if subtree is not None:
                active_name, bound = subtree
                active = BoundedObjectBuilder(bound)
                active.feed(event, value)
                continue
            if prefix in ROOT_SCALARS and event in SCALAR_EVENTS:
                root_values[prefix] = value
                continue
            if prefix.startswith("experiment."):
                name = prefix.removeprefix("experiment.")
                if name in EXPERIMENT_SCALARS and event in SCALAR_EVENTS:
                    experiment_values[name] = value
                    continue
            if prefix == "experiment.events.item":
                raise ValidationError("event array items must be objects")
            if prefix == "experiment.transitions.item":
                raise ValidationError("transition array items must be objects")

        if active is not None:
            raise ValidationError("bundle document contains an incomplete subtree")
        if not root_started or not experiment_started:
            raise ValidationError("bundle document root/experiment must be objects")
        if not events_started or not transitions_started:
            raise ValidationError("events and transitions must be arrays")
        if root_keys != ROOT_KEYS:
            raise ValidationError(f"bundle document keys mismatch: {sorted(root_keys)}")
        if experiment_keys != EXPERIMENT_KEYS:
            raise ValidationError(
                f"experiment snapshot keys mismatch: {sorted(experiment_keys)}"
            )
        if configuration is None or signature is None or producer is None:
            raise ValidationError("bundle document object member has the wrong type")
        return {
            "configuration": configuration,
            "configuration_signature": signature,
            "experiment": experiment_values,
            "parsed_events": parsed_events,
            "producer": producer,
            "root": root_values,
            "transition_final_state": transition_previous,
        }

    def _finish_document(
        self,
        parsed: Mapping[str, Any],
        event_path: Path,
        outer: Mapping[str, Any],
        source: InventoryObject,
        private_key: bytes,
    ) -> VerifiedBundle:
        root = parsed["root"]
        if root.get("format") != "particeps-research-bundle-v1":
            raise ValidationError("unsupported bundle document format")
        if uuid4_text(root.get("bundle_id"), "bundle ID") != outer["bundle_id"]:
            raise ValidationError("outer and inner bundle IDs differ")
        bundle_kind = root.get("bundle_kind")
        if bundle_kind not in {"manual_export", "automatic_upload"}:
            raise ValidationError("unknown bundle kind")
        if source.source_kind == "receiver" and bundle_kind != "automatic_upload":
            raise ValidationError("receiver inventory object is not an automatic upload")
        configuration_digest = sha256_hex(
            root.get("configuration_sha256"), "configuration digest"
        )
        if configuration_digest != outer["configuration_sha256"]:
            raise ValidationError("outer and inner configuration digests differ")

        configuration = parsed["configuration"]
        configuration_bytes = canonicalize(configuration)
        if len(configuration_bytes) > SIGNED_CONFIGURATION_MAX_BYTES:
            raise ValidationError("embedded configuration exceeds Protocol v1 bound")
        if hashlib.sha256(configuration_bytes).hexdigest() != configuration_digest:
            raise ValidationError("embedded configuration digest mismatch")
        signature = exact_object(
            parsed["configuration_signature"],
            {"signer_key_id", "signature"},
            "configuration signature",
        )
        if signature["signer_key_id"] != configuration["signer"]["key_id"]:
            raise ValidationError("configuration signer provenance mismatch")
        signature_bytes = base64url_decode(
            signature["signature"], 64, "configuration signature"
        )
        signing_key = base64url_decode(
            configuration["signer"]["public_key"], 32, "signer public key"
        )
        try:
            Ed25519PublicKey.from_public_bytes(signing_key).verify(
                signature_bytes, configuration_bytes
            )
        except (ValueError, InvalidSignature) as error:
            raise ValidationError(
                "configuration signature verification failed"
            ) from error
        export = configuration["export"]
        if export["researcher_key_id"] != outer["researcher_key_id"]:
            raise ValidationError("researcher key ID differs from configuration")
        expected_public = base64url_decode(
            export["hpke_public_key"], 32, "researcher public key"
        )
        if public_key(private_key) != expected_public:
            raise ValidationError("researcher private key does not match configuration")

        if bundle_kind == "automatic_upload":
            if source.byte_count > AUTOMATIC_UPLOAD_MAX_BYTES:
                raise ValidationError("automatic upload exceeds 32 MiB")
        elif source.byte_count > configuration["storage"]["maximum_local_bytes"]:
            raise ValidationError("manual export exceeds its signed local storage quota")

        producer = exact_object(
            parsed["producer"], {"client_version", "platform"}, "producer"
        )
        if producer["platform"] != configuration["platform"]:
            raise ValidationError("producer platform differs from configuration")
        client_version = canonical_decimal(
            producer["client_version"], "producer client version"
        )
        minimum_version = canonical_decimal(
            configuration["minimum_client_version"], "minimum client version"
        )
        if client_version < minimum_version:
            raise ValidationError("producer client version is too old")
        exported_at = canonical_decimal(
            root.get("exported_at_utc_millis"), "exported_at_utc_millis"
        )
        return self._finish_experiment(
            parsed,
            event_path,
            configuration,
            outer,
            source,
            bundle_kind,
            exported_at,
        )

    def _finish_experiment(
        self,
        parsed: Mapping[str, Any],
        event_path: Path,
        configuration: Mapping[str, Any],
        outer: Mapping[str, Any],
        source: InventoryObject,
        bundle_kind: str,
        exported_at: int,
    ) -> VerifiedBundle:
        root = parsed["experiment"]
        if root.get("experiment_id") != configuration["experiment_id"]:
            raise ValidationError("experiment ID differs from configuration")
        if root.get("configuration_id") != configuration["configuration_id"]:
            raise ValidationError("configuration ID differs from configuration")
        if root.get("assigned_participant_id") != configuration[
            "assigned_participant_id"
        ]:
            raise ValidationError("assigned participant ID differs from configuration")
        participant_id = uuid_text(
            root.get("participant_instance_id"), "participant instance ID"
        )
        state = root.get("state")
        if state not in STATES:
            raise ValidationError("experiment state is invalid")
        if parsed["transition_final_state"] != state:
            raise ValidationError("final transition does not match experiment state")

        first = canonical_decimal(root.get("first_sequence_number"), "first_sequence_number")
        last = canonical_decimal(root.get("last_sequence_number"), "last_sequence_number")
        count = canonical_decimal(root.get("event_count"), "event_count")
        durable = canonical_decimal(
            root.get("durable_through_sequence"), "durable_through_sequence"
        )
        next_sequence = canonical_decimal(
            root.get("next_sequence_number"), "next_sequence_number"
        )
        retained = canonical_decimal(
            root.get("retained_from_sequence"), "retained_from_sequence"
        )
        uploaded = canonical_decimal(
            root.get("uploaded_through_sequence"), "uploaded_through_sequence"
        )
        if (
            first < 1
            or next_sequence != durable + 1
            or not 1 <= retained <= next_sequence
            or not retained <= first <= next_sequence
        ):
            raise ValidationError("experiment sequence boundaries are inconsistent")
        if uploaded > durable or retained > uploaded + 1:
            raise ValidationError("retained/uploaded boundaries are inconsistent")
        expected_last = first - 1 if count == 0 else first + count - 1
        if last != expected_last or last > durable:
            raise ValidationError("bundle range/count is inconsistent")
        if parsed["parsed_events"] != count:
            raise ValidationError("event array count mismatch")
        if bundle_kind == "automatic_upload" and count == 0:
            raise ValidationError("automatic upload cannot be empty")
        if bundle_kind == "automatic_upload" and first != uploaded + 1:
            raise ValidationError("automatic upload does not start after its watermark")

        configured_collectors = {item["id"] for item in configuration["collectors"]}
        if configuration["interventions"]:
            configured_collectors.add("interventions.v1")
        context = _EventContext(
            configuration,
            participant_id,
            frozenset(configured_collectors),
            EventProvenance(
                source.sha256,
                outer["bundle_id"],
                outer["configuration_sha256"],
                source.source_uri,
            ),
            first,
            count,
        )
        event_digest = hashlib.sha256()
        event_bytes = 0
        for event in self._iter_event_file(event_path, context):
            encoded_size = len(event.canonical_bytes) + 1
            event_bytes += encoded_size
            event_digest.update(event.canonical_bytes)
            event_digest.update(b"\n")
        self._validate_receiver_metadata(source, outer, first, last, count)
        os.chmod(event_path, 0o400)
        verified_events = _VerifiedEventSpool(
            event_path,
            self,
            context,
            event_digest.hexdigest(),
            event_bytes,
        )
        return VerifiedBundle(
            outer["bundle_id"],
            bundle_kind,
            outer["configuration_sha256"],
            configuration["experiment_id"],
            configuration["configuration_id"],
            participant_id,
            exported_at,
            first,
            last,
            count,
            retained,
            uploaded,
            durable,
            next_sequence,
            verified_events,
            source,
        )

    def _iter_event_file(
        self,
        path: Path,
        context: _EventContext,
        expected_sha256: str | None = None,
        expected_bytes: int | None = None,
    ) -> Iterator[VerifiedEvent]:
        digest = hashlib.sha256()
        byte_count = 0
        index = 0
        with path.open("rb") as stream:
            while line := stream.readline(self.catalog.maximum_event_bytes + 2):
                byte_count += len(line)
                digest.update(line)
                if (
                    len(line) > self.catalog.maximum_event_bytes + 1
                    or not line.endswith(b"\n")
                ):
                    raise ValidationError("event spool record exceeds its bound")
                event = parse(line[:-1])
                yield self._validate_event(
                    event,
                    context.first_sequence + index,
                    context.configuration,
                    context.participant_id,
                    context.configured_collectors,
                    context.provenance,
                )
                index += 1
        if index != context.event_count:
            raise ValidationError("event spool count changed after authentication")
        if expected_bytes is not None and byte_count != expected_bytes:
            raise ValidationError("event spool size changed after authentication")
        if expected_sha256 is not None and digest.hexdigest() != expected_sha256:
            raise ValidationError("event spool digest changed after authentication")

    def _validate_event(
        self,
        value: Any,
        expected_sequence: int,
        configuration: Mapping[str, Any],
        participant_id: str,
        configured_collectors: frozenset[str],
        provenance: EventProvenance,
    ) -> VerifiedEvent:
        root = exact_object(value, EVENT_KEYS, "event")
        sequence = canonical_decimal(root["sequence_number"], "event sequence_number")
        if sequence != expected_sequence or sequence < 1:
            raise ValidationError("event sequence is not contiguous")
        collector_id = root["collector_id"]
        if (
            not isinstance(collector_id, str)
            or collector_id not in configured_collectors
        ):
            raise ValidationError("event collector is not enabled by configuration")
        schema_version = root["payload_schema_version"]
        if isinstance(schema_version, bool) or not isinstance(schema_version, int):
            raise ValidationError("payload_schema_version must be an integer")
        payload_type = root["payload_type"]
        if not isinstance(payload_type, str):
            raise ValidationError("payload_type must be a string")
        schema = self.catalog.payload(collector_id, schema_version, payload_type)
        typed_fields = self.catalog.typed_fields(schema, root["fields"])
        self.catalog.validate_event_size(root, schema)
        boot, monotonic, wall = _research_time(root["observed_time"])
        return VerifiedEvent(
            configuration["experiment_id"],
            configuration["configuration_id"],
            participant_id,
            configuration["assigned_participant_id"],
            sequence,
            collector_id,
            schema_version,
            payload_type,
            boot,
            monotonic,
            wall,
            typed_fields,
            canonicalize(root),
            provenance,
        )

    def _validate_receiver_metadata(
        self,
        source: InventoryObject,
        outer: Mapping[str, Any],
        first: int,
        last: int,
        count: int,
    ) -> None:
        metadata = source.metadata
        if metadata is None:
            return
        expected = {
            "sha256": source.sha256,
            "byte_count": str(source.byte_count),
            "configuration_sha256": outer["configuration_sha256"],
            "researcher_key_id": outer["researcher_key_id"],
            "first_sequence_number": str(first),
            "last_sequence_number": str(last),
            "event_count": str(count),
        }
        for key, value in expected.items():
            if metadata.get(key) != value:
                raise ValidationError(f"receiver metadata mismatch: {key}")


def _subtree_target(
    prefix: str, event: str, maximum_event_bytes: int
) -> tuple[str, int] | None:
    if event != "start_map":
        if prefix in {
            "configuration",
            "configuration_signature",
            "producer",
        }:
            raise ValidationError(f"{prefix} must be an object")
        return None
    if prefix == "configuration":
        return "configuration", SIGNED_CONFIGURATION_MAX_BYTES
    if prefix == "configuration_signature":
        return "configuration_signature", 4096
    if prefix == "producer":
        return "producer", 4096
    if prefix == "experiment.events.item":
        return "event", maximum_event_bytes
    if prefix == "experiment.transitions.item":
        return "transition", 4096
    return None


def _validate_transition(value: Any, previous_to: str) -> str:
    item = exact_object(value, {"from", "reason", "time", "to"}, "transition")
    if item["from"] not in STATES or item["to"] not in STATES:
        raise ValidationError("transition state is invalid")
    if (
        item["reason"] not in REASON_DESTINATION
        or REASON_DESTINATION[item["reason"]] != item["to"]
    ):
        raise ValidationError("transition reason/destination mismatch")
    if (
        item["from"] != previous_to
        or item["to"] not in ALLOWED_TRANSITIONS[item["from"]]
    ):
        raise ValidationError("transition history is discontinuous")
    _research_time(item["time"])
    return item["to"]


def _bundle_context(outer: Mapping[str, Any]) -> bytes:
    return canonicalize(
        {
            "bundle_format": "particeps-research-bundle-v1",
            "bundle_id": outer["bundle_id"],
            "configuration_sha256": outer["configuration_sha256"],
            "researcher_key_id": outer["researcher_key_id"],
        }
    )


def _research_time(value: Any) -> tuple[str, int, int]:
    root = exact_object(value, TIME_KEYS, "research time")
    boot = root["boot_session_id"]
    if (
        not isinstance(boot, str)
        or not boot.strip()
        or not 1 <= len(boot.encode("utf-8")) <= 128
    ):
        raise ValidationError("boot_session_id is invalid")
    monotonic = canonical_decimal(root["monotonic_time_nanos"], "monotonic_time_nanos")
    wall = canonical_decimal(root["wall_time_utc_millis"], "wall_time_utc_millis")
    return boot, monotonic, wall
