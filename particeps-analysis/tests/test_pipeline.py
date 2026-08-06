from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

import pyarrow.parquet as pq
from fakes import FakeS3Client

from particeps_analysis.catalog import CollectorCatalog
from particeps_analysis.encoding import base64url_decode
from particeps_analysis.errors import ValidationError
from particeps_analysis.inventory import CiphertextInventory
from particeps_analysis.pipeline import AnalysisPipeline, load_private_keys
from particeps_analysis.sink import ParquetSink
from particeps_analysis.sources import LocalBundleSource, S3BundleSource

REPOSITORY = Path(__file__).resolve().parents[2]
PROTOCOL = REPOSITORY / "protocol" / "v1"


class PipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.corpus = json.loads((PROTOCOL / "conformance-vectors.json").read_text())
        cls.bundle = cls.corpus["valid"]["bundle"]
        cls.catalog = CollectorCatalog(PROTOCOL / "collector-catalog.json")
        cls.keys = {
            "vector-hpke": base64url_decode(
                cls.bundle["researcher_private_key_base64url"], 32, "private key"
            )
        }

    def test_valid_bundle_materializes_typed_parquet_with_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "valid.partexp").write_bytes(
                bytes.fromhex(self.bundle["container_hex"])
            )
            workspace = root / "workspace"
            CiphertextInventory(workspace).ingest([LocalBundleSource([source])])
            destination = AnalysisPipeline(
                workspace, self.catalog, self.keys, ParquetSink(self.catalog)
            ).materialize(root / "dataset")
            parquet = next(destination.rglob("*.parquet"))
            table = pq.read_table(parquet)
            row = table.to_pylist()[0]
            self.assertEqual(1, row["sequence_number"])
            self.assertEqual(self.bundle["bundle_id"], row["source_bundle_id"])
            self.assertEqual("vector-study", row["experiment_id"])
            manifest = json.loads((destination / "dataset-manifest.json").read_text())
            self.assertEqual("particeps-parquet-dataset-v1", manifest["dataset_format"])
            self.assertEqual([], manifest["validation_failures"])

            second_workspace = root / "workspace-second"
            CiphertextInventory(second_workspace).ingest([LocalBundleSource([source])])
            second = AnalysisPipeline(
                second_workspace, self.catalog, self.keys, ParquetSink(self.catalog)
            ).materialize(root / "dataset-second")
            self.assertEqual(
                parquet.read_bytes(), next(second.rglob("*.parquet")).read_bytes()
            )
            self.assertEqual(
                [], list((workspace / "staging" / "reassembly").glob("*.sqlite3"))
            )

    def test_s3_and_local_sources_produce_the_same_authenticated_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            encoded = bytes.fromhex(self.bundle["container_hex"])
            local_file = root / "bundle.partexp"
            local_file.write_bytes(encoded)
            receipt = self.corpus["valid"]["upload_receipt"]["value"]
            metadata = {
                "sha256": receipt["sha256"],
                "byte_count": receipt["byte_count"],
                "configuration_sha256": receipt["configuration_sha256"],
                "researcher_key_id": "vector-hpke",
                "first_sequence_number": receipt["first_sequence_number"],
                "last_sequence_number": receipt["last_sequence_number"],
                "event_count": receipt["event_count"],
                "received_at_utc": "2026-08-04T00:00:00.000Z",
            }
            local_workspace = root / "local-workspace"
            r2_workspace = root / "r2-workspace"
            CiphertextInventory(local_workspace).ingest(
                [LocalBundleSource([local_file])]
            )
            CiphertextInventory(r2_workspace).ingest(
                [
                    S3BundleSource(
                        "bucket",
                        client=FakeS3Client(
                            {self.bundle["bundle_id"]: (encoded, metadata)}
                        ),
                    )
                ]
            )
            local_output = AnalysisPipeline(
                local_workspace, self.catalog, self.keys, ParquetSink(self.catalog)
            ).materialize(root / "local-dataset")
            r2_output = AnalysisPipeline(
                r2_workspace, self.catalog, self.keys, ParquetSink(self.catalog)
            ).materialize(root / "r2-dataset")
            local_row = pq.read_table(
                next(local_output.rglob("*.parquet"))
            ).to_pylist()[0]
            r2_row = pq.read_table(next(r2_output.rglob("*.parquet"))).to_pylist()[0]
            local_row.pop("source_object")
            r2_row.pop("source_object")
            self.assertEqual(local_row, r2_row)

    def test_invalid_bundle_is_quarantined_without_partial_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            encoded = bytearray.fromhex(self.bundle["container_hex"])
            encoded[-1] ^= 1
            (source / "invalid.partexp").write_bytes(encoded)
            workspace = root / "workspace"
            CiphertextInventory(workspace).ingest([LocalBundleSource([source])])
            with self.assertRaises(ValidationError):
                AnalysisPipeline(
                    workspace, self.catalog, self.keys, ParquetSink(self.catalog)
                ).materialize(root / "dataset")
            self.assertFalse((root / "dataset").exists())
            self.assertEqual(1, len(list((workspace / "quarantine").rglob("*.partexp"))))
            self.assertEqual([], list((workspace / "staging" / "plaintext").glob("*")))

    def test_invalid_bundle_does_not_contribute_rows_when_valid_data_remains(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            valid = bytes.fromhex(self.bundle["container_hex"])
            invalid = bytearray(valid)
            invalid[-1] ^= 1
            (source / "valid.partexp").write_bytes(valid)
            (source / "invalid.partexp").write_bytes(invalid)
            workspace = root / "workspace"
            CiphertextInventory(workspace).ingest([LocalBundleSource([source])])
            output = AnalysisPipeline(
                workspace, self.catalog, self.keys, ParquetSink(self.catalog)
            ).materialize(root / "dataset")
            self.assertEqual(1, pq.read_table(next(output.rglob("*.parquet"))).num_rows)
            manifest = json.loads((output / "dataset-manifest.json").read_text())
            self.assertEqual(1, len(manifest["validation_failures"]))

    def test_private_key_file_requires_private_permissions_and_exact_shape(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "keys.json"
            path.write_text(
                json.dumps(
                    {
                        "format": "particeps-analysis-keys-v1",
                        "keys": {
                            "vector-hpke": self.bundle[
                                "researcher_private_key_base64url"
                            ]
                        },
                    }
                )
            )
            os.chmod(path, 0o600)
            self.assertEqual(self.keys, load_private_keys(path))
            os.chmod(path, 0o644)
            with self.assertRaises(ValidationError):
                load_private_keys(path)


if __name__ == "__main__":
    unittest.main()
