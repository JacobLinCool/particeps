from __future__ import annotations

import copy
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

from factories import (
    CONFIGURATION_SHA256,
    configuration,
    empty_commit_document,
)

from particeps_analysis.bundle import BundleVerifier
from particeps_analysis.encoding import base64url_decode
from particeps_analysis.engine import EngineReplayVerifier
from particeps_analysis.errors import ValidationError
from particeps_analysis.models import InventoryObject
from particeps_analysis.registry import EventSourceRegistry


def experiment_document() -> dict:
    return {
        "assigned_participant_id": None,
        "commit_count": "1",
        "commits": [empty_commit_document()],
        "configuration_id": "config-one",
        "durable_through_commit": "1",
        "evaluated_through_commit": "1",
        "event_count": "0",
        "experiment_id": "study-one",
        "first_commit_sequence": "1",
        "last_commit_sequence": "1",
        "lifetime_data_event_count": "0",
        "next_commit_sequence": "2",
        "participant_instance_id": "95a484e3-2ba5-4d35-9b2f-03ae394235e7",
        "retained_from_commit": "1",
        "state": "READY",
        "uploaded_through_commit": "0",
    }


class BundleContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.verifier = object.__new__(BundleVerifier)
        self.verifier.registry = EventSourceRegistry()

    def test_experiment_contains_only_commit_ranges_and_engine_commits(self) -> None:
        result = self.verifier._experiment(
            experiment_document(),
            configuration(),
            CONFIGURATION_SHA256,
            "manual_export",
        )
        self.assertEqual(1, result["commit_count"])
        self.assertEqual(0, result["event_count"])
        self.assertEqual(1, result["commits"][0].commit_sequence)

    def test_flat_event_and_old_sequence_documents_are_rejected(self) -> None:
        flat = copy.deepcopy(experiment_document())
        flat["events"] = []
        with self.assertRaises(ValidationError):
            self.verifier._experiment(
                flat,
                configuration(),
                CONFIGURATION_SHA256,
                "manual_export",
            )

        old_range = copy.deepcopy(experiment_document())
        old_range["first_sequence_number"] = old_range.pop(
            "first_commit_sequence"
        )
        with self.assertRaises(ValidationError):
            self.verifier._experiment(
                old_range,
                configuration(),
                CONFIGURATION_SHA256,
                "manual_export",
            )

    def test_shared_encrypted_bundle_decrypts_and_replays_through_python(self) -> None:
        corpus = json.loads(
            (
                Path(__file__).parents[2]
                / "protocol/v1/conformance-vectors.json"
            ).read_text()
        )
        fixture = corpus["valid"]["bundle"]
        encoded = bytes.fromhex(fixture["container_hex"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ciphertext = root / "bundle.ptc"
            ciphertext.write_bytes(encoded)
            verifier = BundleVerifier(
                EventSourceRegistry(),
                {
                    "vector-hpke": base64url_decode(
                        fixture["researcher_private_key_base64url"],
                        32,
                        "fixture private key",
                    )
                },
                root / "staging",
            )
            bundle = verifier.verify(
                InventoryObject(
                    ciphertext.as_uri(),
                    hashlib.sha256(encoded).hexdigest(),
                    len(encoded),
                    ciphertext,
                    None,
                )
            )
        events = EngineReplayVerifier(
            EventSourceRegistry(),
            bundle.configuration,
            bundle.configuration_sha256,
        ).replay(bundle.commits)
        self.assertEqual(5, len(events))
        self.assertEqual("battery_state.v1", events[-1].source_id)

    def test_kotlin_exported_encrypted_bundle_decrypts_and_replays(self) -> None:
        fixture_directory = os.environ.get(
            "PARTICEPS_KOTLIN_EXPORT_INTEROP_DIR"
        )
        if fixture_directory is None:
            self.skipTest("Kotlin export interop fixture was not explicitly requested")
        root = Path(fixture_directory)
        expected = json.loads((root / "expected.json").read_text())
        self.assertEqual(
            {
                "bundle_sha256",
                "byte_count",
                "event_count",
                "last_source_id",
                "researcher_key_id",
            },
            set(expected),
        )
        encoded = (root / "kotlin-export.partexp").read_bytes()
        self.assertEqual(int(expected["byte_count"]), len(encoded))
        self.assertEqual(expected["bundle_sha256"], hashlib.sha256(encoded).hexdigest())
        private_key = base64url_decode(
            (root / "researcher-private-key.base64url").read_text(),
            32,
            "Kotlin interop researcher private key",
        )
        with tempfile.TemporaryDirectory() as directory:
            bundle = BundleVerifier(
                EventSourceRegistry(),
                {expected["researcher_key_id"]: private_key},
                Path(directory) / "staging",
            ).verify(
                InventoryObject(
                    (root / "kotlin-export.partexp").as_uri(),
                    expected["bundle_sha256"],
                    len(encoded),
                    root / "kotlin-export.partexp",
                    None,
                )
            )
        events = EngineReplayVerifier(
            EventSourceRegistry(),
            bundle.configuration,
            bundle.configuration_sha256,
        ).replay(bundle.commits)
        self.assertEqual(int(expected["event_count"]), len(events))
        self.assertEqual(expected["last_source_id"], events[-1].source_id)


if __name__ == "__main__":
    unittest.main()
