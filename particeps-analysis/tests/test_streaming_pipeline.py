from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from streaming_fixture import KEY_ID, PRIVATE_KEY, write_bundle

from particeps_analysis.bundle import BundleVerifier
from particeps_analysis.commit_store import CommitSpool
from particeps_analysis.errors import ValidationError
from particeps_analysis.models import InventoryObject
from particeps_analysis.pipeline import AnalysisPipeline
from particeps_analysis.registry import EventSourceRegistry
from particeps_analysis.sink import ParquetSink
from particeps_analysis.sources import LocalBundleSource


def source(path: Path) -> InventoryObject:
    with path.open("rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()
    return InventoryObject(path.as_uri(), digest, path.stat().st_size, path, None)


def pipeline(root: Path, path: Path) -> AnalysisPipeline:
    registry = EventSourceRegistry()
    value = AnalysisPipeline(root / "workspace", registry, {KEY_ID: PRIVATE_KEY}, ParquetSink(registry))
    value.inventory.ingest([LocalBundleSource([path])])
    return value


class StreamingPipelineTest(unittest.TestCase):
    def test_authenticated_oversized_numbers_fail_without_native_crash_and_clean_plaintext(self):
        for variant in ("positive", "negative", "exponent"):
            with self.subTest(variant=variant):
                completed = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve()), "--malformed-number", variant],
                    capture_output=True, text=True, timeout=30, check=False,
                    env={**os.environ, "PYTHONPATH": os.pathsep.join(sys.path)},
                )
                self.assertEqual(0, completed.returncode, completed.stderr)
                result = json.loads(completed.stdout)
                self.assertFalse(result["published"])
                self.assertEqual(0, result["remaining_staging_files"])
                self.assertIn("protocol", result["reason"].lower())

    def test_verified_commits_are_private_reiterable_disk_data_and_plaintext_is_removed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "input.partexp"
            write_bundle(path, 64)
            verifier = BundleVerifier(EventSourceRegistry(), {KEY_ID: PRIVATE_KEY}, root / "staging")
            read_bytes = Path.read_bytes

            def refuse_whole_plaintext(path: Path):
                if path.name.startswith("particeps-plaintext-"):
                    self.fail("bundle verification read the entire plaintext into memory")
                return read_bytes(path)

            with patch.object(Path, "read_bytes", refuse_whole_plaintext):
                bundle = verifier.verify(source(path))
            try:
                self.assertIsInstance(bundle.commits, CommitSpool)
                self.assertIsNone(bundle.commits.connection)
                self.assertEqual(64, len(bundle.commits))
                self.assertEqual(list(range(1, 65)), [c.commit_sequence for c in bundle.commits])
                self.assertEqual(64, sum(1 for _ in bundle.commits))
                self.assertFalse(list((root / "staging").glob("*.json")))
                self.assertEqual(0o600, bundle.commits.path.stat().st_mode & 0o777)
            finally:
                bundle.commits.close()
            self.assertEqual([], list((root / "staging").iterdir()))

    def test_workspace_uri_characters_and_multiple_participants_preserve_every_event(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "research #1?"
            root.mkdir()
            paths = []
            for number in range(3):
                path = root / f"participant-{number}.partexp"
                write_bundle(path, 32, eventful=True)
                paths.append(path)
            value = pipeline(root, paths[0])
            value.inventory.ingest([LocalBundleSource(paths)])
            value.materialize(root / "dataset")
            quality_path = root / "dataset" / "quality-summary.json"
            quality = json.loads(quality_path.read_text())
            participants = quality["commit_chain_verification"]["participants"]
            self.assertEqual(3, len(participants))
            self.assertEqual(102, sum(int(p["replayed_event_count"]) for p in participants))
            manifest = json.loads((root / "dataset" / "dataset-manifest.json").read_text())
            self.assertEqual(hashlib.sha256(quality_path.read_bytes()).hexdigest(), manifest["quality_summary_sha256"])
            self.assertEqual([], [p for p in (root / "workspace" / "staging").rglob("*") if p.is_file()])

    def test_authenticated_noncanonical_suffix_prevents_publication_and_cleans_all_spools(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "input.partexp"
            write_bundle(path, 64, suffix=b" ")
            value = pipeline(root, path)
            with self.assertRaisesRegex(ValidationError, "dataset was not materialized"):
                value.materialize(root / "dataset")
            self.assertFalse((root / "dataset").exists())
            self.assertEqual([], [p for p in (root / "workspace" / "staging").rglob("*") if p.is_file()])

    def test_duplicate_exports_keep_one_commit_chain_and_deterministic_provenance(self):
        import pyarrow.parquet as pq

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first, second = root / "a.partexp", root / "z.partexp"
            write_bundle(first, 32, eventful=True)
            shutil.copyfile(first, second)
            value = pipeline(root, first)
            value.inventory.ingest([LocalBundleSource([second, first])])
            value.materialize(root / "dataset")
            quality = json.loads((root / "dataset" / "quality-summary.json").read_text())
            self.assertEqual("32", quality["commit_chain_verification"]["identical_commit_duplicates"])
            self.assertEqual("34", quality["commit_chain_verification"]["participants"][0]["replayed_event_count"])
            tables = [pq.read_table(path) for path in (root / "dataset").rglob("*.parquet")]
            self.assertEqual(34, sum(table.num_rows for table in tables))
            for table in tables:
                self.assertEqual({first.resolve().as_uri()}, set(table.column("source_object").to_pylist()))

    def test_publication_failure_releases_bundle_and_replay_plaintext(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "input.partexp"
            write_bundle(path, 64)
            value = pipeline(root, path)
            with (
                patch.object(value.sink, "write", side_effect=OSError("injected destination failure")),
                self.assertRaisesRegex(OSError, "destination failure"),
            ):
                value.materialize(root / "dataset")
            self.assertFalse((root / "dataset").exists())
            self.assertEqual([], [p for p in (root / "workspace" / "staging").rglob("*") if p.is_file()])

    @unittest.skipUnless(sys.platform in {"darwin", "linux"}, "RSS units are defined for macOS/Linux")
    def test_valid_chain_materialization_does_not_retain_payloads_in_proportion_to_file_size(self):
        results = []
        for count in (256, 4096):
            completed = subprocess.run(
                [sys.executable, str(Path(__file__).resolve()), "--measure-running", str(count)],
                capture_output=True, text=True, check=True,
                env={**os.environ, "PYTHONPATH": os.pathsep.join(sys.path)},
            )
            results.append(json.loads(completed.stdout))
        small, large = results
        self.assertGreater(large["ciphertext_bytes"], 8 * 1024 * 1024)
        self.assertEqual(4096, large["replayed_commits"])
        self.assertEqual(4098, large["replayed_events"])
        # Payload grows by more than 15x. Allow allocator/platform noise, while catching
        # the former full JSON object tree plus duplicate canonical commit byte strings.
        self.assertLess(large["peak_rss_bytes"] - small["peak_rss_bytes"], 32 * 1024 * 1024)


def measure(count: int, *, eventful: bool = False) -> None:
    import resource
    import threading
    import time

    import pyarrow.parquet as pq

    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        path = root / "input.partexp"
        write_bundle(path, count, eventful=eventful)
        stopped = threading.Event()
        disk_samples = []

        def sample_disk() -> None:
            while not stopped.is_set():
                total = 0
                for directory, _, names in os.walk(root):
                    for name in names:
                        try:
                            total += (Path(directory) / name).stat().st_size
                        except FileNotFoundError:
                            pass  # A completed stage may remove its owned spool during sampling.
                disk_samples.append(total)
                stopped.wait(1)

        monitor = threading.Thread(target=sample_disk, daemon=True)
        monitor.start()
        started = time.monotonic()
        try:
            pipeline(root, path).materialize(root / "dataset")
        finally:
            stopped.set()
            monitor.join()
        quality = json.loads((root / "dataset" / "quality-summary.json").read_text())
        participant = quality["commit_chain_verification"]["participants"][0]
        parquet_rows = sum(pq.ParquetFile(file).metadata.num_rows for file in (root / "dataset").rglob("*.parquet"))
        staging_files = [p for p in (root / "workspace" / "staging").rglob("*") if p.is_file()]
        if staging_files or parquet_rows != int(participant["replayed_event_count"]):
            raise AssertionError("materialization left plaintext spools or lost Parquet rows")
        peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        print(json.dumps({
            "ciphertext_bytes": path.stat().st_size,
            "replayed_commits": int(participant["durable_through_commit"]),
            "replayed_events": int(participant["replayed_event_count"]),
            "peak_rss_bytes": peak if sys.platform == "darwin" else peak * 1024,
            "sampled_peak_total_disk_bytes": max(disk_samples),
            "disk_sample_interval_seconds": 1,
            "remaining_staging_files": len(staging_files),
            "parquet_rows": parquet_rows,
            "materialize_seconds": time.monotonic() - started,
        }))


def malformed_number(variant: str) -> None:
    token = {"positive": b"", "negative": b"-", "exponent": b"1e"}[variant] + b"9" * 5000
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        path = root / "input.partexp"
        write_bundle(path, 64, raw_producer_number=token)
        try:
            pipeline(root, path).materialize(root / "dataset")
        except ValidationError:
            pass
        else:
            raise AssertionError("malformed numeric token was accepted")
        report = json.loads((root / "workspace" / "reports" / "validation-report.json").read_text())
        print(json.dumps({
            "published": (root / "dataset").exists(),
            "remaining_staging_files": sum(p.is_file() for p in (root / "workspace" / "staging").rglob("*")),
            "reason": report["validation_failures"][0]["reason"],
        }))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] in {"--measure", "--measure-running"}:
        measure(int(sys.argv[2]), eventful=sys.argv[1] == "--measure-running")
    elif len(sys.argv) == 3 and sys.argv[1] == "--malformed-number":
        malformed_number(sys.argv[2])
    else:
        unittest.main()
