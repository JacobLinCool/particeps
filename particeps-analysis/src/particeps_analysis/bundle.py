"""Whole-bundle decryption, authentication, and EngineCommit verification."""

from __future__ import annotations

import hashlib
import os
import tempfile
import uuid
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from .configuration import validate_configuration
from .crypto import open_base, public_key
from .encoding import base64url_decode, protocol_id, uuid4_text, uuid_text
from .engine import EngineCommit, EngineCommitParser
from .errors import ValidationError
from .filesystem import private_directory
from .jcs import canonicalize, exact_object, parse
from .limits import AUTOMATIC_UPLOAD_MAX_BYTES, MANUAL_EXPORT_MAX_BYTES
from .models import InventoryObject, VerifiedBundle
from .registry import EventSourceRegistry

MAGIC = b"PTCEXP01"
ROOT_KEYS = {
    "bundle_id", "bundle_kind", "configuration", "configuration_sha256",
    "configuration_signature", "event_source_registry_sha256", "experiment",
    "exported_at_utc_millis", "format", "producer",
}
EXPERIMENT_KEYS = {
    "assigned_participant_id", "commit_count", "commits", "configuration_id",
    "durable_through_commit", "evaluated_through_commit", "event_count",
    "experiment_id", "first_commit_sequence", "last_commit_sequence",
    "lifetime_data_event_count", "next_commit_sequence", "participant_instance_id",
    "retained_from_commit", "state", "uploaded_through_commit",
}
STATES = {
    "IMPORTED", "CONFIG_VERIFIED", "CONSENT_PENDING", "ACCESS_SETUP", "READY",
    "ACTIVATING", "RUNNING", "PAUSING", "PAUSED", "COMPLETED", "WITHDRAWN",
}


class BundleVerifier:
    def __init__(
        self,
        registry: EventSourceRegistry,
        researcher_private_keys: Mapping[str, bytes],
        staging_directory: Path,
    ):
        self.registry = registry
        self.keys = dict(researcher_private_keys)
        if not self.keys:
            raise ValidationError("at least one researcher key is required")
        for key_id, key in self.keys.items():
            protocol_id(key_id, "researcher key ID")
            if not isinstance(key, bytes) or len(key) != 32:
                raise ValidationError("researcher private keys must be raw 32-byte values")
        self.staging = private_directory(staging_directory)

    def verify(self, source: InventoryObject) -> VerifiedBundle:
        maximum = AUTOMATIC_UPLOAD_MAX_BYTES if source.source_kind == "receiver" else MANUAL_EXPORT_MAX_BYTES
        if not 1 <= source.byte_count <= maximum:
            raise ValidationError("ciphertext size is outside its source bound")
        fd, name = tempfile.mkstemp(prefix="particeps-plaintext-", suffix=".json", dir=self.staging)
        path = Path(name)
        try:
            os.fchmod(fd, 0o600)
            with source.cache_path.open("rb", buffering=0) as encoded, os.fdopen(fd, "wb", buffering=0) as plaintext:
                outer, private_key, digest, byte_count = self._decrypt_to(encoded, plaintext, source)
                plaintext.flush()
                os.fsync(plaintext.fileno())
            if path.stat().st_mode & 0o077:
                raise ValidationError("plaintext staging permissions are not private")
            return self._validate_document(path, digest, byte_count, outer, source, private_key)
        finally:
            path.unlink(missing_ok=True)

    def _decrypt_to(self, encoded, plaintext, source: InventoryObject):
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
        bundle_id = uuid4_text(str(uuid.UUID(bytes=fixed[8:24])), "outer bundle ID")
        key_length = int.from_bytes(fixed[56:58], "big")
        if not 3 <= key_length <= 64 or source.byte_count <= 150 + key_length + 16:
            raise ValidationError("bundle container size is invalid")
        key_and_wrapped = read_exact(key_length + 80)
        try:
            researcher_key_id = key_and_wrapped[:key_length].decode(errors="strict")
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
        decryptor = Cipher(algorithms.AES(content_key), modes.GCM(outer["content_nonce"])).decryptor()
        decryptor.authenticate_additional_data(context)
        tail = b""
        plaintext_digest = hashlib.sha256()
        plaintext_count = 0
        while chunk := encoded.read(1024 * 1024):
            count += len(chunk)
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
        if count != source.byte_count or ciphertext_digest.hexdigest() != source.sha256:
            raise ValidationError("ciphertext changed after inventory")
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

    def _validate_document(
        self,
        path: Path,
        plaintext_sha256: str,
        plaintext_bytes: int,
        outer: Mapping[str, Any],
        source: InventoryObject,
        private_key: bytes,
    ) -> VerifiedBundle:
        data = path.read_bytes()
        if len(data) != plaintext_bytes or hashlib.sha256(data).hexdigest() != plaintext_sha256:
            raise ValidationError("plaintext staging integrity changed")
        root = exact_object(parse(data), ROOT_KEYS, "research bundle")
        if root["format"] != "particeps-research-bundle-v1":
            raise ValidationError("unsupported research bundle format")
        if uuid4_text(root["bundle_id"], "bundle ID") != outer["bundle_id"]:
            raise ValidationError("inner and outer bundle IDs differ")
        kind = root["bundle_kind"]
        if kind not in {"manual_export", "automatic_upload"}:
            raise ValidationError("unknown bundle kind")
        configuration_bytes = canonicalize(root["configuration"])
        configuration_sha256 = hashlib.sha256(configuration_bytes).hexdigest()
        if configuration_sha256 != outer["configuration_sha256"] or root["configuration_sha256"] != configuration_sha256:
            raise ValidationError("configuration digest differs across bundle layers")
        configuration = validate_configuration(root["configuration"], self.registry)
        if configuration["export"]["researcher_key_id"] != outer["researcher_key_id"]:
            raise ValidationError("bundle researcher key differs from configuration")
        if public_key(private_key) != base64url_decode(configuration["export"]["hpke_public_key"], 32, "researcher public key"):
            raise ValidationError("researcher private key does not match configuration")
        signature = exact_object(root["configuration_signature"], {"signature", "signer_key_id"}, "configuration signature")
        if signature["signer_key_id"] != configuration["signer"]["key_id"]:
            raise ValidationError("configuration signer provenance mismatch")
        try:
            Ed25519PublicKey.from_public_bytes(
                base64url_decode(configuration["signer"]["public_key"], 32, "signer public key")
            ).verify(base64url_decode(signature["signature"], 64, "configuration signature"), configuration_bytes)
        except (ValueError, InvalidSignature) as error:
            raise ValidationError("configuration signature verification failed") from error
        if root["event_source_registry_sha256"] != self.registry.digest:
            raise ValidationError("event-source registry digest mismatch")
        producer = exact_object(root["producer"], {"client_version", "platform"}, "producer")
        if producer["platform"] != "android":
            raise ValidationError("unsupported bundle producer platform")
        client_version = _decimal(producer["client_version"], "client version")
        if client_version < int(configuration["minimum_client_version"]):
            raise ValidationError("producer client version is too old")
        exported = _decimal(root["exported_at_utc_millis"], "export time")
        experiment = self._experiment(root["experiment"], configuration, configuration_sha256, kind)
        self._validate_receiver_metadata(source, outer, experiment)
        return VerifiedBundle(
            outer["bundle_id"], kind, configuration_sha256, self.registry.digest,
            configuration, experiment["experiment_id"], experiment["configuration_id"],
            experiment["participant_instance_id"], experiment["assigned_participant_id"],
            exported, experiment["first_commit_sequence"], experiment["last_commit_sequence"],
            experiment["commit_count"], experiment["event_count"],
            experiment["retained_from_commit"], experiment["uploaded_through_commit"],
            experiment["evaluated_through_commit"], experiment["durable_through_commit"],
            experiment["next_commit_sequence"], experiment["lifetime_data_event_count"],
            experiment["state"], experiment["commits"], source,
        )

    def _experiment(self, value: Any, configuration: Mapping[str, Any], configuration_sha256: str, kind: str) -> dict[str, Any]:
        root = exact_object(value, EXPERIMENT_KEYS, "experiment snapshot")
        if root["experiment_id"] != configuration["experiment_id"] or root["configuration_id"] != configuration["configuration_id"]:
            raise ValidationError("experiment identity differs from configuration")
        if root["assigned_participant_id"] != configuration["assigned_participant_id"]:
            raise ValidationError("assigned participant ID differs from configuration")
        participant = uuid_text(root["participant_instance_id"], "participant instance ID")
        state = root["state"]
        if state not in STATES:
            raise ValidationError("experiment state is invalid")
        first = _decimal(root["first_commit_sequence"], "first commit")
        last = _decimal(root["last_commit_sequence"], "last commit", minimum=0)
        count = _decimal(root["commit_count"], "commit count", minimum=0)
        event_count = _decimal(root["event_count"], "event count", minimum=0)
        durable = _decimal(root["durable_through_commit"], "durable commit", minimum=0)
        evaluated = _decimal(root["evaluated_through_commit"], "evaluated commit", minimum=0)
        uploaded = _decimal(root["uploaded_through_commit"], "uploaded commit", minimum=0)
        retained = _decimal(root["retained_from_commit"], "retained commit")
        next_commit = _decimal(root["next_commit_sequence"], "next commit")
        lifetime = _decimal(root["lifetime_data_event_count"], "lifetime event count", minimum=0)
        if durable != next_commit - 1 or evaluated > durable or uploaded > durable:
            raise ValidationError("experiment commit watermarks are inconsistent")
        if not 1 <= retained <= next_commit or retained > min(uploaded, evaluated) + 1:
            raise ValidationError("retained commit floor exceeds safe watermark")
        if not retained <= first <= next_commit or last > durable:
            raise ValidationError("bundle commit range is outside retained data")
        expected_last = first - 1 if count == 0 else first + count - 1
        if last != expected_last:
            raise ValidationError("bundle commit range/count mismatch")
        commits_value = root["commits"]
        if not isinstance(commits_value, list) or len(commits_value) != count:
            raise ValidationError("bundle commit array count mismatch")
        parser = EngineCommitParser(self.registry)
        commits = tuple(parser.parse(item) for item in commits_value)
        configured = {item["id"] for item in configuration["collectors"]}
        prior: EngineCommit | None = None
        actual_events = 0
        collector_events = 0
        for commit in commits:
            if prior is None:
                if commit.commit_sequence != first:
                    raise ValidationError("first exported commit does not match range")
                if commit.commit_sequence == 1 and commit.previous_commit_sha256 != "0" * 64:
                    raise ValidationError("genesis commit has a predecessor")
            else:
                if commit.commit_sequence != prior.commit_sequence + 1 or commit.previous_commit_sha256 != prior.commit_sha256:
                    raise ValidationError("exported commit chain is broken")
                _verify_projection_continuity(prior, commit)
            if commit.successor_projection["evaluated_through_commit"] != commit.commit_sequence:
                raise ValidationError("commit was not durably reducer-evaluated")
            for event in commit.events:
                source = self.registry.source(event.source_id, event.schema_version)
                contract = next(item for item in source["events"] if item["event_type"] == event.event_type)
                if not contract["privacy"]["exported"]:
                    raise ValidationError("non-exportable event appears in bundle")
                if source["source_kind"] == "COLLECTOR":
                    if event.source_id not in configured:
                        raise ValidationError("collector was not signed into study")
                    collector_events += 1
                if event.source_id == "traffic_shaping.v1" and not configuration["traffic_shaping"]:
                    raise ValidationError("traffic audit appears in unshaped study")
            for observation in commit.source_observations:
                if observation.source_id not in configured:
                    raise ValidationError("observation source was not signed into study")
                source = self.registry.source(observation.source_id, observation.schema_version)
                retrospective = all(
                    event["delivery"]["kind"] == "POLL" for event in source["events"]
                )
                if (observation.coverage is not None) != retrospective:
                    raise ValidationError("observation coverage disagrees with delivery contract")
            actual_events += len(commit.events)
            prior = commit
        if actual_events != event_count:
            raise ValidationError("bundle event count mismatch")
        if lifetime < collector_events:
            raise ValidationError("lifetime data count is smaller than exported collector data")
        if commits and commits[-1].commit_sequence != last:
            raise ValidationError("last exported commit does not match range")
        if last == durable and commits:
            projection = commits[-1].successor_projection
            if (
                projection["state"] != state
                or projection["next_commit_sequence"] != next_commit
                or projection["lifetime_data_event_count"] != lifetime
                or projection["uploaded_through_commit"] != uploaded
                or projection["evaluated_through_commit"] != evaluated
                or projection["retained_from_commit"] != retained
            ):
                raise ValidationError("snapshot diverges from final exported projection")
        if kind == "automatic_upload" and (count == 0 or first != uploaded + 1):
            raise ValidationError("automatic upload range does not follow watermark")
        return {
            "experiment_id": root["experiment_id"], "configuration_id": root["configuration_id"],
            "participant_instance_id": participant, "assigned_participant_id": root["assigned_participant_id"],
            "state": state, "first_commit_sequence": first, "last_commit_sequence": last,
            "commit_count": count, "event_count": event_count, "durable_through_commit": durable,
            "evaluated_through_commit": evaluated, "uploaded_through_commit": uploaded,
            "retained_from_commit": retained, "next_commit_sequence": next_commit,
            "lifetime_data_event_count": lifetime, "commits": commits,
        }

    def _validate_receiver_metadata(self, source: InventoryObject, outer: Mapping[str, Any], experiment: Mapping[str, Any]) -> None:
        if source.source_kind != "receiver":
            return
        if source.metadata is None:
            raise ValidationError("receiver object has no authenticated receipt metadata")
        expected = {
            "configuration_sha256": outer["configuration_sha256"],
            "researcher_key_id": outer["researcher_key_id"],
            "first_commit_sequence": str(experiment["first_commit_sequence"]),
            "last_commit_sequence": str(experiment["last_commit_sequence"]),
            "commit_count": str(experiment["commit_count"]), "event_count": str(experiment["event_count"]),
            "sha256": source.sha256, "byte_count": str(source.byte_count),
        }
        for key, expected_value in expected.items():
            if source.metadata.get(key) != expected_value:
                raise ValidationError(f"receiver receipt metadata mismatch: {key}")


def _verify_projection_continuity(previous: EngineCommit, current: EngineCommit) -> None:
    before, after = previous.successor_projection, current.successor_projection
    if before["next_commit_sequence"] != current.commit_sequence:
        raise ValidationError("commit does not follow preceding projection")
    if current.events and current.events[0].sequence_number != before["next_event_sequence"]:
        raise ValidationError("event range does not follow preceding projection")
    if current.source_observations and current.source_observations[0].observation_sequence != before["next_observation_sequence"]:
        raise ValidationError("observation range does not follow preceding projection")
    if after["lifetime_data_event_count"] < before["lifetime_data_event_count"]:
        raise ValidationError("lifetime data count moved backwards")
    if after["uploaded_through_commit"] < before["uploaded_through_commit"] or after["retained_from_commit"] < before["retained_from_commit"]:
        raise ValidationError("runtime watermark moved backwards")


def _decimal(value: Any, name: str, minimum: int = 1) -> int:
    if not isinstance(value, str) or not value.isascii() or not value.isdecimal() or (len(value) > 1 and value.startswith("0")):
        raise ValidationError(f"{name} must be a canonical decimal string")
    number = int(value)
    if not minimum <= number <= 2**63 - 1:
        raise ValidationError(f"{name} is outside int64")
    return number


def _bundle_context(outer: Mapping[str, Any]) -> bytes:
    return (
        '{"bundle_format":"particeps-research-bundle-v1","bundle_id":"'
        + outer["bundle_id"] + '","configuration_sha256":"'
        + outer["configuration_sha256"] + '","researcher_key_id":"'
        + outer["researcher_key_id"] + '"}'
    ).encode()
