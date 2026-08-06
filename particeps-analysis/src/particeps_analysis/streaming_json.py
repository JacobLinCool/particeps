"""Bounded streaming validation for Protocol v1 canonical JSON documents."""

from __future__ import annotations

import hashlib
from collections.abc import Iterator
from decimal import Decimal
from pathlib import Path
from typing import Any, BinaryIO

import ijson
from ijson.common import JSONError, ObjectBuilder

from .errors import ValidationError
from .jcs import canonicalize
from .limits import JSON_MAX_DEPTH, JSON_STRING_TOKEN_MAX_BYTES


class CanonicalJsonEvents:
    """Yield parser events and prove that the source bytes are exact integral JCS."""

    def __init__(self, path: Path, expected_sha256: str, expected_bytes: int):
        self.path = path
        self.expected_sha256 = expected_sha256
        self.expected_bytes = expected_bytes

    def __iter__(self) -> Iterator[tuple[str, str, Any]]:
        encoder = _CanonicalEncoder()
        try:
            with self.path.open("rb") as source:
                reader = _BoundedJsonReader(source)
                for prefix, event, value in ijson.parse(reader, use_float=False):
                    encoder.feed(event, value)
                    yield prefix, event, value
        except ValidationError:
            raise
        except (JSONError, UnicodeError, ValueError, OverflowError) as error:
            raise ValidationError("malformed JSON") from error
        canonical_digest, canonical_count = encoder.finish()
        if reader.count != self.expected_bytes or reader.digest.hexdigest() != self.expected_sha256:
            raise ValidationError("plaintext changed while it was being parsed")
        if (
            canonical_count != reader.count
            or canonical_digest.hexdigest() != reader.digest.hexdigest()
        ):
            raise ValidationError("JSON is not canonical JCS")


class BoundedObjectBuilder:
    """Materialize one already-streamed subtree under an explicit byte bound."""

    def __init__(self, maximum_bytes: int):
        self.builder = ObjectBuilder()
        self.encoder = _CanonicalEncoder(maximum_bytes=maximum_bytes)
        self.depth = 0
        self.complete = False

    def feed(self, event: str, value: Any) -> None:
        if self.complete:
            raise ValidationError("JSON subtree has trailing values")
        self.encoder.feed(event, value)
        self.builder.event(event, value)
        if event in {"start_map", "start_array"}:
            self.depth += 1
        elif event in {"end_map", "end_array"}:
            self.depth -= 1
            if self.depth == 0:
                self.complete = True
        elif self.depth == 0:
            self.complete = True

    @property
    def value(self) -> Any:
        if not self.complete:
            raise ValidationError("JSON subtree is incomplete")
        self.encoder.finish()
        return self.builder.value


class _BoundedJsonReader:
    def __init__(self, source: BinaryIO):
        self.source = source
        self.in_string = False
        self.escaped = False
        self.string_bytes = 0
        self.digest = hashlib.sha256()
        self.count = 0

    def read(self, size: int = -1) -> bytes:
        data = self.source.read(size)
        self.digest.update(data)
        self.count += len(data)
        self._scan(data)
        return data

    def _scan(self, data: bytes) -> None:
        for byte in data:
            if not self.in_string:
                if byte == 0x22:
                    self.in_string = True
                    self.escaped = False
                    self.string_bytes = 0
                continue
            if self.escaped:
                self.escaped = False
            elif byte == 0x5C:
                self.escaped = True
            elif byte == 0x22:
                self.in_string = False
                continue
            self.string_bytes += 1
            if self.string_bytes > JSON_STRING_TOKEN_MAX_BYTES:
                raise ValidationError("JSON string exceeds the protocol bound")


class _CanonicalEncoder:
    def __init__(self, *, maximum_bytes: int | None = None):
        self.digest = hashlib.sha256()
        self.count = 0
        self.maximum_bytes = maximum_bytes
        self.stack: list[dict[str, Any]] = []
        self.root_seen = False

    def feed(self, event: str, value: Any) -> None:
        if event == "map_key":
            self._map_key(value)
            return
        if event in {"start_map", "start_array"}:
            self._before_value()
            self._write(b"{" if event == "start_map" else b"[")
            self.stack.append(
                {
                    "kind": "map" if event == "start_map" else "array",
                    "count": 0,
                    "awaiting": False,
                    "last_key": None,
                }
            )
            if len(self.stack) > JSON_MAX_DEPTH:
                raise ValidationError("JSON nesting exceeds the protocol bound")
            return
        if event in {"end_map", "end_array"}:
            expected = "map" if event == "end_map" else "array"
            if not self.stack or self.stack[-1]["kind"] != expected:
                raise ValidationError("malformed JSON container")
            context = self.stack.pop()
            if context["awaiting"]:
                raise ValidationError("JSON object member has no value")
            self._write(b"}" if event == "end_map" else b"]")
            return
        if event not in {"null", "boolean", "integer", "number", "string"}:
            raise ValidationError(f"unsupported JSON token: {event}")
        if event == "number" and (isinstance(value, Decimal) or not isinstance(value, int)):
            raise ValidationError("floating-point JSON numbers are forbidden")
        self._before_value()
        self._write(canonicalize(value))

    def finish(self):
        if self.stack or not self.root_seen:
            raise ValidationError("JSON document is incomplete")
        return self.digest, self.count

    def _before_value(self) -> None:
        if not self.stack:
            if self.root_seen:
                raise ValidationError("JSON document has multiple root values")
            self.root_seen = True
            return
        context = self.stack[-1]
        if context["kind"] == "array":
            if context["count"]:
                self._write(b",")
            context["count"] += 1
            return
        if not context["awaiting"]:
            raise ValidationError("JSON object value has no member name")
        context["awaiting"] = False

    def _map_key(self, value: Any) -> None:
        if not self.stack or self.stack[-1]["kind"] != "map":
            raise ValidationError("JSON member name is outside an object")
        if not isinstance(value, str):
            raise ValidationError("JSON member name must be a string")
        context = self.stack[-1]
        if context["awaiting"]:
            raise ValidationError("JSON object member has no value")
        encoded_key = value.encode("utf-16-be", errors="strict")
        if context["last_key"] is not None and encoded_key <= context["last_key"]:
            raise ValidationError("JSON object members are duplicate or not JCS-sorted")
        if context["count"]:
            self._write(b",")
        self._write(canonicalize(value))
        self._write(b":")
        context["count"] += 1
        context["awaiting"] = True
        context["last_key"] = encoded_key

    def _write(self, data: bytes) -> None:
        self.digest.update(data)
        self.count += len(data)
        if self.maximum_bytes is not None and self.count > self.maximum_bytes:
            raise ValidationError("JSON subtree exceeds its protocol bound")
