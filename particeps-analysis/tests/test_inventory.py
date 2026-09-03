from __future__ import annotations

import hashlib
import io
import tempfile
import unittest
from pathlib import Path

from fakes import FakeS3Client

from particeps_analysis.errors import ValidationError
from particeps_analysis.inventory import CiphertextInventory
from particeps_analysis.jcs import canonicalize, parse
from particeps_analysis.models import SourceObject
from particeps_analysis.sources import LocalBundleSource, S3BundleSource


class _Source:
    def __init__(self, objects):
        self._objects = objects

    def objects(self):
        return iter(self._objects)


class InventoryTest(unittest.TestCase):
    def test_local_inventory_is_content_addressed_and_reloadable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "b.partexp").write_bytes(b"PTCEXP01-b")
            (source / "a.partexp").write_bytes(b"PTCEXP01-a")
            (source / "ignored.txt").write_text("not a bundle")
            inventory = CiphertextInventory(root / "workspace")
            first = inventory.ingest([LocalBundleSource([source])])
            second = inventory.load()
            self.assertEqual(first, second)
            self.assertEqual(2, len(first))
            for item in first:
                self.assertEqual(item.sha256, item.cache_path.stem)
                self.assertEqual("local", item.source_kind)
                self.assertEqual(0, item.cache_path.stat().st_mode & 0o077)

    def test_receiver_metadata_is_closed_world_and_checked(self) -> None:
        data = b"PTCEXP01-ciphertext"
        digest = hashlib.sha256(data).hexdigest()
        metadata = {
            "sha256": digest,
            "byte_count": str(len(data)),
            "configuration_sha256": "1" * 64,
            "researcher_key_id": "researcher-key",
            "first_commit_sequence": "1",
            "last_commit_sequence": "1",
            "commit_count": "1",
            "event_count": "1",
            "received_at_utc": "2026-08-04T00:00:00.000Z",
        }
        with tempfile.TemporaryDirectory() as temporary:
            inventory = CiphertextInventory(Path(temporary) / "workspace")
            objects = inventory.ingest(
                [
                    S3BundleSource(
                        "bucket",
                        prefix="prefix/",
                        client=FakeS3Client(
                            {
                                "prefix/b.partexp": (data, metadata),
                                "prefix/a.partexp": (data, metadata),
                            }
                        ),
                    )
                ]
            )
            self.assertEqual(
                ["s3://bucket/prefix/a.partexp", "s3://bucket/prefix/b.partexp"],
                [o.source_uri for o in objects],
            )
            self.assertTrue(all(o.source_kind == "receiver" for o in objects))
            invalid = dict(metadata, unexpected="value")
            with self.assertRaises(ValidationError):
                inventory.ingest(
                    [
                        S3BundleSource(
                            "bucket",
                            client=FakeS3Client({"invalid": (data, invalid)}),
                        )
                    ]
                )
            invalid_time = dict(metadata, received_at_utc="2026-99-04T00:00:00.000Z")
            with self.assertRaises(ValidationError):
                inventory.ingest(
                    [
                        S3BundleSource(
                            "bucket",
                            client=FakeS3Client({"invalid-time": (data, invalid_time)}),
                        )
                    ]
                )

    def test_inventory_manifest_cannot_redirect_a_cache_entry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.partexp"
            source.write_bytes(b"PTCEXP01-content")
            inventory = CiphertextInventory(root / "workspace")
            inventory.ingest([LocalBundleSource([source])])
            document = parse(inventory.manifest.read_bytes())
            document["objects"][0]["cache_path"] = "another-file"
            inventory.manifest.write_bytes(canonicalize(document))
            with self.assertRaises(ValidationError):
                inventory.load()

    def test_source_size_and_digest_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inventory = CiphertextInventory(Path(temporary) / "workspace")
            oversized = SourceObject(
                "memory:large", 33_554_433, None, lambda: io.BytesIO(b"")
            )
            with self.assertRaisesRegex(ValidationError, "changed while reading"):
                inventory.ingest([_Source([oversized])])
            receiver_oversized = SourceObject(
                "memory:receiver-large",
                33_554_433,
                {},
                lambda: io.BytesIO(b""),
                "receiver",
            )
            with self.assertRaisesRegex(ValidationError, "outside protocol bound"):
                inventory.ingest([_Source([receiver_oversized])])
            changing = SourceObject(
                "memory:changing", 3, None, lambda: io.BytesIO(b"four")
            )
            with self.assertRaises(ValidationError):
                inventory.ingest([_Source([changing])])
            with self.assertRaises(ValidationError):
                S3BundleSource(
                    "bucket",
                    endpoint_url="http://example.test",
                    client=FakeS3Client({}),
                )


if __name__ == "__main__":
    unittest.main()
