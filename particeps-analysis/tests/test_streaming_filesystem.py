from __future__ import annotations

import hashlib
import io
import os
import tempfile
import unittest
from pathlib import Path

from particeps_analysis.errors import ValidationError
from particeps_analysis.filesystem import private_directory, rename_noreplace
from particeps_analysis.streaming_json import CanonicalJsonEvents, _BoundedJsonReader


class StreamingAndFilesystemTest(unittest.TestCase):
    def test_numeric_tokens_are_bounded_before_native_parsing_across_read_boundaries(self):
        for number in (b"9" * 5000, b"-" + b"9" * 5000, b"1e" + b"9" * 5000, b"1.0"):
            with self.subTest(number=number[:24]):
                reader = _BoundedJsonReader(io.BytesIO(b"[" + number + b"]"))
                with self.assertRaises(ValidationError):
                    while reader.read(7):
                        pass

    def test_signed_int64_numeric_boundaries_and_digit_strings_remain_valid(self):
        canonical = b'[-9223372036854775808,0,9223372036854775807,"' + b"9" * 5000 + b'"]'
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "document.json"
            path.write_bytes(canonical)
            events = list(CanonicalJsonEvents(path, hashlib.sha256(canonical).hexdigest(), len(canonical)))
            self.assertEqual([-9223372036854775808, 0, 9223372036854775807], [v for _, e, v in events if e == "number"])

    def test_streaming_jcs_accepts_exact_bytes_and_rejects_other_encodings(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "document.json"
            canonical = b'{"a":[true,"value"],"z":1}'
            path.write_bytes(canonical)
            events = list(
                CanonicalJsonEvents(
                    path, hashlib.sha256(canonical).hexdigest(), len(canonical)
                )
            )
            self.assertEqual(("", "start_map", None), events[0])

            noncanonical = b'{"z":1, "a":[true,"value"]}'
            path.write_bytes(noncanonical)
            with self.assertRaises(ValidationError):
                list(
                    CanonicalJsonEvents(
                        path,
                        hashlib.sha256(noncanonical).hexdigest(),
                        len(noncanonical),
                    )
                )

            floating = b'{"value":1.0}'
            path.write_bytes(floating)
            with self.assertRaises(ValidationError):
                list(
                    CanonicalJsonEvents(
                        path, hashlib.sha256(floating).hexdigest(), len(floating)
                    )
                )

    def test_private_directory_is_tightened_and_publish_is_create_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staging = root / "staging"
            staging.mkdir(mode=0o755)
            self.assertEqual(private_directory(staging), staging.resolve())
            if os.name == "posix":
                self.assertEqual(0o700, staging.stat().st_mode & 0o777)

            source = root / "source"
            destination = root / "destination"
            source.mkdir()
            destination.mkdir()
            (source / "new").write_text("new")
            (destination / "existing").write_text("existing")
            with self.assertRaises(ValidationError):
                rename_noreplace(source, destination)
            self.assertTrue((source / "new").is_file())
            self.assertTrue((destination / "existing").is_file())


if __name__ == "__main__":
    unittest.main()
