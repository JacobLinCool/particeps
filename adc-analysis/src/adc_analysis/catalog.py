"""Closed-world collector catalog loading and payload conversion."""

from __future__ import annotations

import math
import re
import struct
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .errors import ValidationError
from .jcs import (
    canonical_decimal,
    canonicalize,
    exact_object,
    parse,
    parse_embedded_json,
)

_SIGNED = re.compile(r"(?:0|-?[1-9][0-9]*)\Z")
_FLOAT = re.compile(r"[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)\Z")
_COLLECTOR_ID = re.compile(r"[a-z][a-z0-9_.-]{2,63}\Z")
_PAYLOAD_TYPE = re.compile(r"[A-Z][A-Z0-9_]{1,63}\Z")


@dataclass(frozen=True, slots=True)
class PayloadSchema:
    collector_id: str
    schema_version: int
    payload_type: str
    maximum_encoded_event_bytes: int
    fields: Mapping[str, Mapping[str, Any]]


class CollectorCatalog:
    """The one catalog used for validation and Parquet schema generation."""

    def __init__(self, path: Path):
        root = parse(path.read_bytes(), require_canonical=False)
        exact_object(
            root,
            {
                "catalog_format",
                "catalog_version",
                "protocol_schema_version",
                "collectors",
            },
            "catalog",
        )
        if (
            root["catalog_format"] != "adc-collector-catalog-v1"
            or isinstance(root["catalog_version"], bool)
            or root["catalog_version"] != 1
            or isinstance(root["protocol_schema_version"], bool)
            or root["protocol_schema_version"] != 1
        ):
            raise ValidationError("unsupported collector catalog")
        if not isinstance(root["collectors"], list):
            raise ValidationError("catalog collectors must be an array")
        self._collectors: dict[str, Mapping[str, Any]] = {}
        self._payloads: dict[tuple[str, int, str], PayloadSchema] = {}
        self._sampling_clock_fields: dict[tuple[str, int, str], str] = {}
        for collector in root["collectors"]:
            self._load_collector(collector)

    @property
    def collector_ids(self) -> frozenset[str]:
        return frozenset(self._collectors)

    @property
    def payload_schemas(self) -> tuple[PayloadSchema, ...]:
        return tuple(self._payloads[key] for key in sorted(self._payloads))

    @property
    def maximum_event_bytes(self) -> int:
        return max(
            schema.maximum_encoded_event_bytes for schema in self._payloads.values()
        )

    @property
    def sampling_clock_fields(self) -> Mapping[tuple[str, int, str], str]:
        """Catalog-selected source clocks for achieved-rate summaries."""

        return dict(self._sampling_clock_fields)

    def collector_configuration(self, collector_id: str) -> Mapping[str, Any]:
        try:
            configuration = self._collectors[collector_id]["configuration"]
        except KeyError as error:
            raise ValidationError(f"unknown collector: {collector_id}") from error
        if configuration is None:
            raise ValidationError(f"collector is not configurable: {collector_id}")
        return configuration

    def payload(
        self, collector_id: str, schema_version: int, payload_type: str
    ) -> PayloadSchema:
        try:
            return self._payloads[(collector_id, schema_version, payload_type)]
        except KeyError as error:
            raise ValidationError(
                f"unknown payload: {collector_id}/{schema_version}/{payload_type}"
            ) from error

    def validate_collector_config(self, collector: Any) -> str:
        root = exact_object(collector, {"id", "required", "config"}, "collector")
        collector_id = _string(root["id"], "collector id")
        if not isinstance(root["required"], bool):
            raise ValidationError("collector required must be boolean")
        schema = self.collector_configuration(collector_id)
        config = root["config"]
        if not isinstance(config, dict):
            raise ValidationError("collector config must be an object")
        fields = schema["fields"]
        required = set(schema["required"])
        if set(config) != set(fields) or set(fields) != required:
            raise ValidationError(f"collector config keys mismatch: {collector_id}")
        for name, descriptor in fields.items():
            value = config[name]
            kind = descriptor["type"]
            if kind == "integer":
                if isinstance(value, bool) or not isinstance(value, int):
                    raise ValidationError(f"{collector_id}.{name} must be an integer")
                if not descriptor["minimum"] <= value <= descriptor["maximum"]:
                    raise ValidationError(f"{collector_id}.{name} is outside bounds")
            elif kind == "boolean":
                if not isinstance(value, bool):
                    raise ValidationError(f"{collector_id}.{name} must be boolean")
            elif kind == "enum_array":
                allowed = descriptor["items_enum"]
                if (
                    not isinstance(value, list)
                    or not value
                    or any(
                        not isinstance(item, str) or item not in allowed
                        for item in value
                    )
                    or value != sorted(set(value))
                ):
                    raise ValidationError(
                        f"{collector_id}.{name} must be a sorted unique enum array"
                    )
            elif kind == "enum":
                if not isinstance(value, str) or value not in descriptor["enum"]:
                    raise ValidationError(f"{collector_id}.{name} is an invalid enum")
            else:
                raise ValidationError(f"unknown catalog configuration type: {kind}")
        for name, descriptor in fields.items():
            maximum_field = descriptor.get("maximum_field")
            if maximum_field and config[name] > config[maximum_field]:
                raise ValidationError(f"{collector_id}.{name} exceeds {maximum_field}")
        return collector_id

    def typed_fields(self, schema: PayloadSchema, fields: Any) -> dict[str, Any]:
        if not isinstance(fields, dict) or not set(fields) <= set(schema.fields):
            raise ValidationError(f"payload field set mismatch: {schema.payload_type}")
        required = {
            name for name, descriptor in schema.fields.items() if descriptor["required"]
        }
        if not required <= set(fields):
            raise ValidationError(
                f"required payload fields missing: {schema.payload_type}"
            )
        return {
            name: _convert_field(name, fields[name], descriptor)
            for name, descriptor in schema.fields.items()
            if name in fields
        }

    def validate_event_size(
        self, event: Mapping[str, Any], schema: PayloadSchema
    ) -> None:
        if len(canonicalize(event)) > schema.maximum_encoded_event_bytes:
            raise ValidationError("event exceeds catalog maximum_encoded_event_bytes")

    def _load_collector(self, collector: Any) -> None:
        if not isinstance(collector, dict):
            raise ValidationError("catalog collector must be an object")
        collector_id = _string(collector.get("id"), "catalog collector id")
        if not _COLLECTOR_ID.fullmatch(collector_id):
            raise ValidationError("catalog collector ID is invalid")
        if collector_id in self._collectors:
            raise ValidationError(f"duplicate catalog collector: {collector_id}")
        version = collector.get("payload_schema_version")
        maximum = collector.get("maximum_encoded_event_bytes")
        if isinstance(version, bool) or not isinstance(version, int) or version != 1:
            raise ValidationError("unsupported payload schema version")
        if (
            isinstance(maximum, bool)
            or not isinstance(maximum, int)
            or not 128 <= maximum <= 65_536
        ):
            raise ValidationError("invalid catalog event bound")
        self._collectors[collector_id] = collector
        for payload in collector.get("payloads", []):
            if not isinstance(payload, dict) or not isinstance(
                payload.get("fields"), dict
            ):
                raise ValidationError("invalid catalog payload")
            source_clocks = [
                name
                for name, descriptor in payload["fields"].items()
                if descriptor.get("clock_basis") == "continuous_monotonic_since_boot"
            ]
            if len(source_clocks) > 1:
                raise ValidationError("payload has multiple continuous source clocks")
            source_clock = source_clocks[0] if source_clocks else None
            if source_clock is not None:
                descriptor = payload["fields"][source_clock]
                if (
                    descriptor.get("type") != "decimal_string"
                    or descriptor.get("unit") != "nanosecond"
                ):
                    raise ValidationError(
                        "continuous source clock must be decimal nanoseconds"
                    )
            for payload_type in payload.get("types", []):
                payload_type = _string(payload_type, "payload type")
                if not _PAYLOAD_TYPE.fullmatch(payload_type):
                    raise ValidationError("catalog payload type is invalid")
                key = (collector_id, version, payload_type)
                if key in self._payloads:
                    raise ValidationError(f"duplicate catalog payload: {key}")
                self._payloads[key] = PayloadSchema(
                    collector_id, version, payload_type, maximum, payload["fields"]
                )
                if source_clock is not None:
                    self._sampling_clock_fields[key] = source_clock


def _convert_field(name: str, value: Any, descriptor: Mapping[str, Any]) -> Any:
    if not isinstance(value, str):
        raise ValidationError(f"payload field {name} must use its string wire encoding")
    kind = descriptor["type"]
    if kind == "decimal_string":
        return canonical_decimal(value, name)
    if kind == "int32":
        if not _SIGNED.fullmatch(value):
            raise ValidationError(f"{name} must be a signed decimal integer")
        number = int(value)
        if not -(2**31) <= number < 2**31:
            raise ValidationError(f"{name} is outside int32")
        if "minimum" in descriptor and number < descriptor["minimum"]:
            raise ValidationError(f"{name} is below minimum")
        if "maximum" in descriptor and number > descriptor["maximum"]:
            raise ValidationError(f"{name} is above maximum")
        return number
    if kind == "boolean":
        if value not in ("true", "false"):
            raise ValidationError(f"{name} must be true or false")
        return value == "true"
    if kind == "enum":
        if value not in descriptor["enum"]:
            raise ValidationError(f"{name} is an invalid enum")
        return value
    if kind in ("float32", "float64"):
        if not _FLOAT.fullmatch(value):
            raise ValidationError(f"{name} is not a finite decimal float")
        number = float(value)
        if not math.isfinite(number):
            raise ValidationError(f"{name} must be finite")
        if "minimum" in descriptor and number < descriptor["minimum"]:
            raise ValidationError(f"{name} is below minimum")
        if "maximum" in descriptor and number > descriptor["maximum"]:
            raise ValidationError(f"{name} is above maximum")
        if kind == "float32":
            try:
                number = struct.unpack(">f", struct.pack(">f", number))[0]
            except OverflowError as error:
                raise ValidationError(f"{name} is outside float32") from error
            if not math.isfinite(number):
                raise ValidationError(f"{name} is outside float32")
        return number
    if kind == "string":
        if len(value.encode("utf-16-le")) // 2 > descriptor.get(
            "maximum_length", 2**31
        ):
            raise ValidationError(f"{name} is too long")
        return value
    if kind == "json_string":
        if len(value.encode("utf-16-le")) // 2 > descriptor.get(
            "maximum_length", 2**31
        ):
            raise ValidationError(f"{name} JSON is too large")
        parse_embedded_json(value)
        return value
    raise ValidationError(f"unknown catalog payload type: {kind}")


def _string(value: Any, name: str) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{name} must be a string")
    return value
