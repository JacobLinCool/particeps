from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Callable

from tools.validate_protocol_vectors import VECTORS, canonical_json, commit_sha256, validate


class ProtocolVectorTest(unittest.TestCase):
    def test_checked_in_corpus_is_self_consistent(self) -> None:
        validate()

    def test_registry_binding_is_not_advisory(self) -> None:
        self.assert_rejected(
            lambda document: document.__setitem__(
                "event_source_registry_sha256", "00" * 32
            ),
            "event-source registry",
        )

    def test_commit_digest_is_recomputed(self) -> None:
        self.assert_rejected(
            lambda document: document["experiment"]["commits"][2].__setitem__(
                "commit_sha256", "00" * 32
            ),
            "commit digest",
        )

    def test_observation_digest_is_recomputed(self) -> None:
        def mutate(document: dict[str, object]) -> None:
            commit = document["experiment"]["commits"][2]
            commit["source_observations"][0]["encoded_sha256"] = "00" * 32
            commit["commit_sha256"] = commit_sha256(commit)

        self.assert_rejected(mutate, "observation digest")

    def test_flat_event_document_is_not_a_protocol_dialect(self) -> None:
        def mutate(document: dict[str, object]) -> None:
            experiment = document["experiment"]
            experiment["events"] = experiment["commits"][2]["events"]
            del experiment["commits"]

        self.assert_rejected(mutate, "closed-world")

    def assert_rejected(
        self, mutate: Callable[[dict[str, object]], None], message: str
    ) -> None:
        corpus = json.loads(VECTORS.read_text(encoding="utf-8"))
        document_bytes = bytes.fromhex(
            corpus["valid"]["bundle"]["document_jcs_utf8_hex"]
        )
        document = json.loads(document_bytes)
        mutate(document)
        corpus["valid"]["bundle"]["document_jcs_utf8_hex"] = canonical_json(
            document
        ).hex()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vectors.json"
            path.write_text(json.dumps(corpus), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, message):
                validate(path)


if __name__ == "__main__":
    unittest.main()
