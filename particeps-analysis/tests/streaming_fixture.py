"""Generate signed, encrypted, valid setup or eventful running commit chains.

All keys and records here are synthetic. Generation itself is streaming so a benchmark's
peak RSS includes no large in-memory fixture construction.
"""

from __future__ import annotations

import base64
import hashlib
import os
import uuid
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from factories import (
    commit_document,
    configuration,
    empty_commit_document,
    event_document,
    observation_document,
)

from particeps_analysis.crypto import _extract_and_expand, _key_schedule
from particeps_analysis.engine import GENESIS_DIGEST
from particeps_analysis.jcs import canonicalize
from particeps_analysis.registry import EventSourceRegistry

PRIVATE_KEY = bytes([2]) * 32
KEY_ID = "researcher-one"


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def write_bundle(
    path: Path, commit_count: int, *, suffix: bytes = b"", eventful: bool = False,
    raw_producer_number: bytes | None = None,
) -> None:
    signing = Ed25519PrivateKey.from_private_bytes(bytes([3]) * 32)
    recipient = X25519PrivateKey.from_private_bytes(PRIVATE_KEY)
    if eventful:
        from test_engine import battery_configuration

        config = battery_configuration()
    else:
        config = configuration()
    config["signer"]["public_key"] = _b64(signing.public_key().public_bytes_raw())
    config["export"]["hpke_public_key"] = _b64(recipient.public_key().public_bytes_raw())
    config["storage"]["maximum_local_bytes"] = 8 * 1024 * 1024 * 1024
    config_bytes = canonicalize(config)
    digest = hashlib.sha256(config_bytes).hexdigest()
    bundle_id = uuid.uuid4()
    context = canonicalize({
        "bundle_format": "particeps-research-bundle-v1", "bundle_id": str(bundle_id),
        "configuration_sha256": digest, "researcher_key_id": KEY_ID,
    })
    ephemeral = X25519PrivateKey.generate()
    enc = ephemeral.public_key().public_bytes_raw()
    shared = _extract_and_expand(
        ephemeral.exchange(recipient.public_key()), enc + recipient.public_key().public_bytes_raw()
    )
    wrapping_key, wrapping_nonce = _key_schedule(shared, context)
    content_key, nonce = os.urandom(32), os.urandom(12)
    wrapped = enc + AESGCM(wrapping_key).encrypt(wrapping_nonce, content_key, b"")
    encryptor = Cipher(algorithms.AES(content_key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(context)
    experiment = {
        "assigned_participant_id": None, "commit_count": str(commit_count), "commits": None,
        "configuration_id": config["configuration_id"], "durable_through_commit": str(commit_count),
        "evaluated_through_commit": str(commit_count),
        "event_count": str(commit_count + 2) if eventful else "0",
        "experiment_id": config["experiment_id"], "first_commit_sequence": "1",
        "last_commit_sequence": str(commit_count),
        "lifetime_data_event_count": str(commit_count - 2) if eventful else "0",
        "next_commit_sequence": str(commit_count + 1), "participant_instance_id": str(uuid.uuid4()),
        "retained_from_commit": "1", "state": "RUNNING" if eventful else "READY", "uploaded_through_commit": "0",
    }
    document = {
        "bundle_id": str(bundle_id), "bundle_kind": "manual_export", "configuration": config,
        "configuration_sha256": digest,
        "configuration_signature": {
            "signature": _b64(signing.sign(config_bytes)), "signer_key_id": config["signer"]["key_id"],
        },
        "event_source_registry_sha256": EventSourceRegistry().digest,
        "experiment": experiment, "exported_at_utc_millis": "1",
        "format": "particeps-research-bundle-v1", "producer": {"client_version": "1", "platform": "android"},
    }
    with path.open("wb") as output:
        output.write(
            b"PTCEXP01" + bundle_id.bytes + bytes.fromhex(digest)
            + len(KEY_ID).to_bytes(2, "big") + nonce + KEY_ID.encode() + wrapped
        )

        def write(value: bytes) -> None:
            output.write(encryptor.update(value))

        def write_object(value: dict, *, root: bool) -> None:
            write(b"{")
            for position, (key, item) in enumerate(sorted(value.items())):
                if position:
                    write(b",")
                write(canonicalize(key) + b":")
                if root and key == "experiment":
                    write_object(experiment, root=False)
                elif root and key == "producer" and raw_producer_number is not None:
                    # Keep ciphertext authentication valid while exercising the raw
                    # parser before producer schema validation can reject its type.
                    write(b'{"client_version":' + raw_producer_number + b',"platform":"android"}')
                elif not root and key == "commits":
                    write(b"[")
                    documents = running_commits(commit_count, digest) if eventful else setup_commits(commit_count)
                    for position, commit in enumerate(documents):
                        if position:
                            write(b",")
                        write(canonicalize(commit))
                    write(b"]")
                else:
                    write(canonicalize(item))
            write(b"}")

        write_object(document, root=True)
        write(suffix)
        output.write(encryptor.finalize())
        output.write(encryptor.tag)


def setup_commits(count: int):
    previous = GENESIS_DIGEST
    for sequence in range(1, count + 1):
        commit = empty_commit_document(sequence, previous)
        yield commit
        previous = commit["commit_sha256"]


def running_commits(count: int, digest: str):
    import factories
    import test_engine

    if count < 3:
        raise ValueError("running fixture needs its activation and a collector observation")
    # Existing builders bind deadline/epoch identities to this digest; use the freshly
    # signed configuration's digest throughout every synthetic commit.
    with patch.object(factories, "CONFIGURATION_SHA256", digest), patch.object(test_engine, "CONFIGURATION_SHA256", digest):
        initial, epoch = test_engine.active_chain()
        yield initial[0]
        yield initial[1]
        previous = initial[1]["commit_sha256"]
        for sequence in range(3, count + 1):
            event = event_document(
                sequence + 1, "battery_state.v1", "BATTERY_STATE",
                {"charging_source": "USB", "charging_state": "CHARGING", "percentage": "50", "power_save_enabled": "false"},
                epoch_id=epoch["id"], wall=sequence * 1000, monotonic=sequence * 10,
            )
            observation = observation_document(
                sequence=sequence - 2, source_id="battery_state.v1", generation=1,
                producer_ordinal=sequence - 3, epoch_id=epoch["id"], events=[event],
            )
            commit = commit_document(
                sequence=sequence, previous=previous, events=[event], observations=[observation],
                state="RUNNING", next_event_sequence=sequence + 2, next_observation_sequence=sequence - 1,
                lifetime_data_event_count=sequence - 2, checkpoint_evaluated=sequence,
                checkpoint_lifecycle="RUNNING", checkpoint_start=1000,
                desired_resources=(("COLLECTOR", "battery_state.v1", 1, "continuous"),),
                active_epoch=epoch,
                source_checkpoints={"battery_state.v1": {
                    "coverage": None, "cursor": None, "next_producer_ordinal": str(sequence - 2),
                    "resource_generation": "1", "source_id": "battery_state.v1",
                }},
            )
            yield commit
            previous = commit["commit_sha256"]
