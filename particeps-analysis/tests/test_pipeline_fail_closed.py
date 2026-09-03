from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from particeps_analysis.errors import ValidationError
from particeps_analysis.models import InventoryObject
from particeps_analysis.pipeline import AnalysisPipeline
from particeps_analysis.registry import EventSourceRegistry


class _Inventory:
    def __init__(self, sources):
        self.sources = sources

    def load(self):
        return self.sources


class _Verifier:
    def verify(self, source):
        if source.source_uri.endswith("invalid.partexp"):
            raise ValidationError("invalid authenticated bundle")
        return object()


class _Sink:
    def __init__(self):
        self.called = False

    def write(self, result, destination, *, validation_failures=()):
        self.called = True
        return destination


class PipelineFailClosedTest(unittest.TestCase):
    def test_one_invalid_inventory_object_prevents_dataset_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = []
            for name in ("valid.partexp", "invalid.partexp"):
                path = root / name
                data = name.encode()
                path.write_bytes(data)
                sources.append(
                    InventoryObject(
                        path.as_uri(),
                        hashlib.sha256(data).hexdigest(),
                        len(data),
                        path,
                        None,
                    )
                )
            valid, invalid = sources
            sink = _Sink()
            pipeline = object.__new__(AnalysisPipeline)
            pipeline.workspace = root / "workspace"
            pipeline.inventory = _Inventory([valid, invalid])
            pipeline.verifier = _Verifier()
            pipeline.registry = EventSourceRegistry()
            pipeline.sink = sink

            with self.assertRaisesRegex(ValidationError, "dataset was not materialized"):
                pipeline.materialize(root / "dataset")

            self.assertFalse(sink.called)
            self.assertFalse((root / "dataset").exists())
            report = json.loads(
                (pipeline.workspace / "reports" / "validation-report.json").read_text()
            )
            self.assertEqual(1, len(report["validation_failures"]))
            self.assertEqual(
                "invalid authenticated bundle",
                report["validation_failures"][0]["reason"],
            )


if __name__ == "__main__":
    unittest.main()
