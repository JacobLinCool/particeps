from __future__ import annotations

import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

from fakes import FakeS3Client

from adc_analysis.cli import main
from adc_analysis.inventory import CiphertextInventory
from adc_analysis.sources import S3BundleSource

REPOSITORY = Path(__file__).resolve().parents[2]
PROTOCOL = REPOSITORY / "protocol" / "v1"


class CliTest(unittest.TestCase):
    def test_local_inventory_then_materialize(self) -> None:
        corpus = json.loads((PROTOCOL / "conformance-vectors.json").read_text())
        bundle = corpus["valid"]["bundle"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "bundle.adcexp"
            source.write_bytes(bytes.fromhex(bundle["container_hex"]))
            workspace = root / "workspace"
            output = StringIO()
            with redirect_stdout(output):
                result = main(
                    [
                        "inventory",
                        "--workspace",
                        str(workspace),
                        "--local",
                        str(source),
                    ]
                )
            self.assertEqual(0, result)
            self.assertIn("inventoried 1", output.getvalue())

            keys = root / "keys.json"
            keys.write_text(
                json.dumps(
                    {
                        "format": "adc-analysis-keys-v1",
                        "keys": {
                            "vector-hpke": bundle["researcher_private_key_base64url"]
                        },
                    }
                )
            )
            os.chmod(keys, 0o600)
            dataset = root / "dataset"
            with redirect_stdout(output):
                result = main(
                    [
                        "materialize",
                        "--workspace",
                        str(workspace),
                        "--keys",
                        str(keys),
                        "--output",
                        str(dataset),
                        "--catalog",
                        str(PROTOCOL / "collector-catalog.json"),
                    ]
                )
            self.assertEqual(0, result)
            self.assertTrue((dataset / "dataset-manifest.json").is_file())

    def test_one_inventory_snapshot_can_combine_local_and_receiver_sources(
        self,
    ) -> None:
        corpus = json.loads((PROTOCOL / "conformance-vectors.json").read_text())
        bundle = corpus["valid"]["bundle"]
        encoded = bytes.fromhex(bundle["container_hex"])
        receipt = corpus["valid"]["upload_receipt"]["value"]
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
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            local = root / "manual.adcexp"
            local.write_bytes(encoded)
            workspace = root / "workspace"
            receiver = S3BundleSource(
                "bucket",
                client=FakeS3Client({"automatic.adcexp": (encoded, metadata)}),
            )
            with (
                patch("adc_analysis.cli.S3BundleSource", return_value=receiver),
                redirect_stdout(StringIO()),
            ):
                result = main(
                    [
                        "inventory",
                        "--workspace",
                        str(workspace),
                        "--local",
                        str(local),
                        "--s3-bucket",
                        "bucket",
                    ]
                )
            self.assertEqual(0, result)
            objects = CiphertextInventory(workspace).load()
            self.assertEqual(2, len(objects))
            self.assertEqual({"local", "receiver"}, {item.source_kind for item in objects})


if __name__ == "__main__":
    unittest.main()
