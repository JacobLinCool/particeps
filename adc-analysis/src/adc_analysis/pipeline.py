"""One-way orchestration from immutable inventory to typed Parquet."""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from collections.abc import Mapping
from pathlib import Path

from .bundle import BundleVerifier
from .catalog import CollectorCatalog
from .encoding import base64url_decode, protocol_id
from .errors import ValidationError
from .inventory import CiphertextInventory
from .jcs import canonicalize, exact_object, parse
from .reassembly import Reassembler
from .sink import DatasetSink


class AnalysisPipeline:
    def __init__(
        self,
        workspace: Path,
        catalog: CollectorCatalog,
        researcher_private_keys: Mapping[str, bytes],
        sink: DatasetSink,
    ):
        self.workspace = Path(workspace).resolve()
        self.inventory = CiphertextInventory(self.workspace)
        self.verifier = BundleVerifier(
            catalog, researcher_private_keys, self.workspace / "staging" / "plaintext"
        )
        self.sink = sink

    def materialize(self, destination: Path) -> Path:
        bundles = []
        result = None
        failures: list[dict[str, str]] = []
        try:
            for source in self.inventory.load():
                try:
                    bundles.append(self.verifier.verify(source))
                except ValidationError as error:
                    failures.append(self._quarantine(source, str(error)))
            if not bundles:
                self._write_report(
                    {
                        "format": "adc-validation-report-v1",
                        "validation_failures": failures,
                    }
                )
                raise ValidationError("no valid bundles remain after verification")
            result = Reassembler(
                self.workspace / "staging" / "reassembly"
            ).reassemble(bundles)
            self._write_report(
                {
                    "format": "adc-validation-report-v1",
                    "quality": result.quality,
                    "validation_failures": failures,
                }
            )
            return self.sink.write(
                result, destination, validation_failures=tuple(failures)
            )
        finally:
            for bundle in bundles:
                bundle.events.close()
            if result is not None:
                result.events.close()

    def _quarantine(self, source, reason: str) -> dict[str, str]:
        directory = self.workspace / "quarantine" / source.sha256[:2]
        directory.mkdir(parents=True, exist_ok=True)
        ciphertext = directory / f"{source.sha256}.adcexp"
        if ciphertext.exists():
            if (
                ciphertext.stat().st_size != source.byte_count
                or _sha256(ciphertext) != source.sha256
            ):
                raise ValidationError("quarantine ciphertext collision")
        else:
            fd, name = tempfile.mkstemp(prefix=".quarantine-", dir=directory)
            temporary = Path(name)
            try:
                os.fchmod(fd, 0o600)
                with (
                    os.fdopen(fd, "wb") as output,
                    source.cache_path.open("rb") as input_stream,
                ):
                    shutil.copyfileobj(input_stream, output, 1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
                if _sha256(temporary) != source.sha256:
                    raise ValidationError("quarantine copy digest mismatch")
                os.replace(temporary, ciphertext)
            finally:
                temporary.unlink(missing_ok=True)
        record = {
            "ciphertext": str(ciphertext.relative_to(self.workspace)),
            "reason": reason,
            "sha256": source.sha256,
            "source_object": source.source_uri,
        }
        _atomic_write(directory / f"{source.sha256}.reason.json", canonicalize(record))
        return record

    def _write_report(self, document: dict) -> None:
        _atomic_write(
            self.workspace / "reports" / "validation-report.json",
            canonicalize(document),
        )


def load_private_keys(path: Path) -> dict[str, bytes]:
    path = Path(path).resolve()
    if os.name == "posix" and path.stat().st_mode & 0o077:
        raise ValidationError(
            "researcher key file must not be group- or world-accessible"
        )
    root = parse(path.read_bytes(), require_canonical=False)
    exact_object(root, {"format", "keys"}, "researcher key file")
    if root["format"] != "adc-analysis-keys-v1" or not isinstance(root["keys"], dict):
        raise ValidationError("unsupported researcher key file")
    keys = {}
    for key_id, encoded in root["keys"].items():
        protocol_id(key_id, "researcher key ID")
        keys[key_id] = base64url_decode(encoded, 32, f"private key {key_id}")
    if not keys:
        raise ValidationError("researcher key file is empty")
    return keys


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    temporary = Path(name)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()
