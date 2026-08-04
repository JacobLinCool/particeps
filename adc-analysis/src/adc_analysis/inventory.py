"""Immutable, content-addressed ciphertext inventory."""

from __future__ import annotations

import hashlib
import os
import re
import tempfile
from collections.abc import Iterable
from datetime import datetime
from pathlib import Path

from .errors import ValidationError
from .jcs import canonical_decimal, canonicalize, parse
from .limits import AUTOMATIC_UPLOAD_MAX_BYTES, MANUAL_EXPORT_MAX_BYTES
from .models import InventoryObject
from .sources import BundleSource

METADATA_KEYS = {
    "sha256",
    "byte_count",
    "configuration_sha256",
    "researcher_key_id",
    "first_sequence_number",
    "last_sequence_number",
    "event_count",
    "received_at_utc",
}
_HEX = re.compile(r"[0-9a-f]{64}\Z")
_RECEIVE_TIME = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z\Z"
)


class CiphertextInventory:
    def __init__(self, workspace: Path):
        self.workspace = Path(workspace).resolve()
        self.cache = self.workspace / "cache" / "objects"
        self.manifest = self.workspace / "inventory.json"

    def ingest(self, sources: Iterable[BundleSource]) -> tuple[InventoryObject, ...]:
        self.cache.mkdir(parents=True, exist_ok=True)
        if self.cache.resolve() != self.cache:
            raise ValidationError("ciphertext cache must not traverse symbolic links")
        objects: list[InventoryObject] = []
        for source in sources:
            for item in source.objects():
                objects.append(self._ingest_object(item))
        objects.sort(key=lambda item: (item.sha256, item.source_uri))
        document = {
            "format": "adc-ciphertext-inventory-v1",
            "objects": [
                {
                    "byte_count": str(item.byte_count),
                    "cache_path": str(item.cache_path.relative_to(self.workspace)),
                    "metadata": dict(sorted(item.metadata.items()))
                    if item.metadata is not None
                    else None,
                    "sha256": item.sha256,
                    "source_kind": item.source_kind,
                    "source_uri": item.source_uri,
                }
                for item in objects
            ],
        }
        _atomic_write(self.manifest, canonicalize(document), 0o600)
        return tuple(objects)

    def load(self) -> tuple[InventoryObject, ...]:
        try:
            document = parse(self.manifest.read_bytes())
        except (OSError, ValidationError) as error:
            raise ValidationError("inventory manifest is unreadable") from error
        if not isinstance(document, dict) or set(document) != {"format", "objects"}:
            raise ValidationError("inventory manifest keys mismatch")
        if document["format"] != "adc-ciphertext-inventory-v1" or not isinstance(
            document["objects"], list
        ):
            raise ValidationError("unsupported inventory manifest")
        result = []
        for raw in document["objects"]:
            if not isinstance(raw, dict) or set(raw) != {
                "byte_count",
                "cache_path",
                "metadata",
                "sha256",
                "source_kind",
                "source_uri",
            }:
                raise ValidationError("inventory object keys mismatch")
            digest = raw["sha256"]
            if not isinstance(digest, str) or not _HEX.fullmatch(digest):
                raise ValidationError("inventory digest is invalid")
            byte_count = canonical_decimal(
                raw["byte_count"],
                "inventory byte_count",
                maximum=MANUAL_EXPORT_MAX_BYTES,
            )
            if raw["source_kind"] not in {"local", "receiver"}:
                raise ValidationError("inventory source kind is invalid")
            if not isinstance(raw["source_uri"], str) or not raw["source_uri"]:
                raise ValidationError("inventory source URI is invalid")
            expected_relative = (
                Path("cache") / "objects" / digest[:2] / f"{digest}.adcexp"
            )
            if raw["cache_path"] != str(expected_relative):
                raise ValidationError("inventory cache path is invalid")
            candidate = self.workspace / expected_relative
            cache_path = candidate.resolve()
            if cache_path != candidate or not cache_path.is_file():
                raise ValidationError("inventory cache object is missing")
            metadata = raw["metadata"]
            if raw["source_kind"] == "receiver":
                _validate_metadata(metadata, digest, byte_count)
            elif metadata is not None:
                raise ValidationError("local inventory objects cannot have metadata")
            if cache_path.stat().st_size != byte_count or _sha256(cache_path) != digest:
                raise ValidationError("cached ciphertext does not match inventory")
            result.append(
                InventoryObject(
                    raw["source_uri"],
                    digest,
                    byte_count,
                    cache_path,
                    metadata,
                    raw["source_kind"],
                )
            )
        return tuple(result)

    def _ingest_object(self, item) -> InventoryObject:
        if item.source_kind not in {"local", "receiver"}:
            raise ValidationError("source object kind is invalid")
        maximum = (
            AUTOMATIC_UPLOAD_MAX_BYTES
            if item.source_kind == "receiver"
            else MANUAL_EXPORT_MAX_BYTES
        )
        if item.size < 1 or item.size > maximum:
            raise ValidationError(
                f"source object size outside protocol bound: {item.source_uri}"
            )
        temporary = None
        digest = hashlib.sha256()
        count = 0
        try:
            fd, temporary_name = tempfile.mkstemp(prefix=".adc-object-", dir=self.cache)
            temporary = Path(temporary_name)
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as output, item.open() as source:
                while chunk := source.read(1024 * 1024):
                    count += len(chunk)
                    if count > maximum:
                        raise ValidationError("source object exceeds its source bound")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if count != item.size:
                raise ValidationError("source object changed while reading")
            sha256 = digest.hexdigest()
            if item.source_kind == "receiver":
                _validate_metadata(item.metadata, sha256, count)
            elif item.metadata is not None:
                raise ValidationError("local source objects cannot have metadata")
            destination = self.cache / sha256[:2] / f"{sha256}.adcexp"
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.parent.resolve() != destination.parent:
                raise ValidationError(
                    "ciphertext cache must not traverse symbolic links"
                )
            if destination.exists():
                if (
                    destination.stat().st_size != count
                    or _sha256(destination) != sha256
                ):
                    raise ValidationError("content-addressed cache collision")
            else:
                try:
                    os.link(temporary, destination)
                except FileExistsError:
                    if (
                        destination.stat().st_size != count
                        or _sha256(destination) != sha256
                    ):
                        raise ValidationError("content-addressed cache collision")
            return InventoryObject(
                item.source_uri,
                sha256,
                count,
                destination,
                item.metadata,
                item.source_kind,
            )
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def _validate_metadata(metadata: object, digest: str, byte_count: int) -> None:
    if not isinstance(metadata, dict) or set(metadata) != METADATA_KEYS:
        raise ValidationError(
            "receiver metadata must have the exact Protocol v1 key set"
        )
    if any(not isinstance(value, str) for value in metadata.values()):
        raise ValidationError("receiver metadata values must be strings")
    if metadata["sha256"] != digest:
        raise ValidationError("receiver metadata digest mismatch")
    if (
        canonical_decimal(
            metadata["byte_count"],
            "metadata byte_count",
            maximum=AUTOMATIC_UPLOAD_MAX_BYTES,
        )
        != byte_count
    ):
        raise ValidationError("receiver metadata byte count mismatch")
    if not _HEX.fullmatch(metadata["configuration_sha256"]):
        raise ValidationError("receiver metadata configuration digest is invalid")
    first = canonical_decimal(
        metadata["first_sequence_number"], "metadata first_sequence_number"
    )
    last = canonical_decimal(
        metadata["last_sequence_number"], "metadata last_sequence_number"
    )
    count = canonical_decimal(metadata["event_count"], "metadata event_count")
    if first < 1 or last != (first - 1 if count == 0 else first + count - 1):
        raise ValidationError("receiver metadata sequence range is inconsistent")
    key_id = metadata["researcher_key_id"]
    if not isinstance(key_id, str) or not re.fullmatch(
        r"[a-z0-9][a-z0-9-]{2,63}", key_id
    ):
        raise ValidationError("receiver metadata researcher key ID is invalid")
    received = metadata["received_at_utc"]
    if not _RECEIVE_TIME.fullmatch(received):
        raise ValidationError("receiver metadata receive time is invalid")
    try:
        datetime.fromisoformat(received)
    except ValueError as error:
        raise ValidationError("receiver metadata receive time is invalid") from error


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _atomic_write(path: Path, data: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    temporary = Path(name)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
