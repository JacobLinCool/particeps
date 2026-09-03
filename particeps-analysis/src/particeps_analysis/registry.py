"""Exact, generated event-source registry projection used by analysis.

The checked-in registry is the only event/configuration contract.  Analysis never
accepts a caller-provided alternate registry because doing so would make a bundle's
meaning depend on an out-of-band file.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import struct
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from .errors import ValidationError
from .generated.event_source_registry import (
    EVENT_SOURCE_CONTRACTS,
    EVENT_SOURCE_REGISTRY_SHA256,
)
from .jcs import canonicalize, parse_embedded_json

_SIGNED = re.compile(r"(?:0|-?[1-9][0-9]*)\Z")
_UNSIGNED = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_DECIMAL_FLOAT = re.compile(
    r"[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)\Z"
)
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


@dataclass(frozen=True, slots=True)
class EventSchema:
    source_id: str
    schema_version: int
    event_type: str
    source_kind: str
    maximum_encoded_event_bytes: int
    fields: Mapping[str, Mapping[str, Any]]
    trigger: Mapping[str, Any]
    clock: Mapping[str, Any]
    delivery: Mapping[str, Any]
    completeness: Mapping[str, Any]

    @property
    def primary_source_time_field(self) -> str | None:
        value = self.clock["primary_source_time_field"]
        return value if isinstance(value, str) else None

    @property
    def primary_source_basis(self) -> str:
        return str(self.clock["primary_source_basis"])


class EventSourceRegistry:
    """Closed-world registry with typed event and profile validation."""

    digest = EVENT_SOURCE_REGISTRY_SHA256

    def __init__(self) -> None:
        self._sources: dict[tuple[str, int], Mapping[str, Any]] = {}
        self._events: dict[tuple[str, int, str], EventSchema] = {}
        for source in EVENT_SOURCE_CONTRACTS:
            key = (str(source["source_id"]), int(source["schema_version"]))
            if key in self._sources:
                raise RuntimeError(f"duplicate generated source contract: {key}")
            self._sources[key] = source
            for event in source["events"]:
                event_key = (*key, str(event["event_type"]))
                if event_key in self._events:
                    raise RuntimeError(f"duplicate generated event contract: {event_key}")
                self._events[event_key] = EventSchema(
                    source_id=key[0],
                    schema_version=key[1],
                    event_type=event_key[2],
                    source_kind=str(source["source_kind"]),
                    maximum_encoded_event_bytes=int(
                        event["maximum_encoded_event_bytes"]
                    ),
                    fields=event["fields"],
                    trigger=event["trigger"],
                    clock=event["clock"],
                    delivery=event["delivery"],
                    completeness=event["completeness"],
                )

    @property
    def event_schemas(self) -> tuple[EventSchema, ...]:
        return tuple(self._events[key] for key in sorted(self._events))

    @property
    def source_ids(self) -> frozenset[str]:
        return frozenset(source_id for source_id, _ in self._sources)

    @property
    def collector_source_ids(self) -> frozenset[str]:
        return frozenset(
            source_id
            for (source_id, _), source in self._sources.items()
            if source["source_kind"] == "COLLECTOR"
        )

    @property
    def retrospective_collector_source_ids(self) -> frozenset[str]:
        """Collectors whose complete source truth is observed through bounded polling."""

        return frozenset(
            source_id
            for (source_id, _), source in self._sources.items()
            if source["source_kind"] == "COLLECTOR"
            and any(event["delivery"]["kind"] == "POLL" for event in source["events"])
        )

    @property
    def maximum_event_bytes(self) -> int:
        return max(schema.maximum_encoded_event_bytes for schema in self._events.values())

    @property
    def source_clock_fields(self) -> Mapping[tuple[str, int, str], str]:
        return {
            key: field
            for key, schema in self._events.items()
            if (field := schema.primary_source_time_field) is not None
            and schema.primary_source_basis == "CONTINUOUS_MONOTONIC_SINCE_BOOT"
        }

    def source(self, source_id: str, schema_version: int = 1) -> Mapping[str, Any]:
        try:
            return self._sources[(source_id, schema_version)]
        except KeyError as error:
            raise ValidationError(
                f"unknown event source: {source_id}/{schema_version}"
            ) from error

    def event(self, source_id: str, schema_version: int, event_type: str) -> EventSchema:
        try:
            return self._events[(source_id, schema_version, event_type)]
        except KeyError as error:
            raise ValidationError(
                f"unknown event: {source_id}/{schema_version}/{event_type}"
            ) from error

    def validate_profile(self, source_id: str, configuration: Any) -> dict[str, Any]:
        source = self.source(source_id)
        if source["source_kind"] != "COLLECTOR" or not source["selectable"]:
            raise ValidationError(f"source is not a selectable collector: {source_id}")
        descriptor = source["configuration"]
        if not isinstance(descriptor, Mapping):
            raise ValidationError(f"collector has no profile contract: {source_id}")
        if not isinstance(configuration, dict):
            raise ValidationError(f"collector profile must be an object: {source_id}")
        fields = descriptor["fields"]
        if set(configuration) != set(fields):
            raise ValidationError(f"collector profile keys mismatch: {source_id}")
        for name, field in fields.items():
            _validate_profile_value(source_id, name, configuration[name], field)
        for name, field in fields.items():
            maximum_field = field["less_than_or_equal_field"]
            if maximum_field is not None and configuration[name] > configuration[maximum_field]:
                raise ValidationError(f"{source_id}.{name} exceeds {maximum_field}")
        return configuration

    def typed_fields(self, schema: EventSchema, fields: Any) -> dict[str, Any]:
        if not isinstance(fields, dict) or not set(fields) <= set(schema.fields):
            raise ValidationError(f"event field set mismatch: {schema.event_type}")
        required = {
            name
            for name, descriptor in schema.fields.items()
            if descriptor["required"]
        }
        if not required <= set(fields):
            raise ValidationError(f"required event fields missing: {schema.event_type}")
        return {
            name: self.decode_event_field(
                name,
                fields[name],
                descriptor,
                schema.maximum_encoded_event_bytes,
            )
            for name, descriptor in schema.fields.items()
            if name in fields
        }

    def decode_event_field(
        self,
        name: str,
        value: Any,
        descriptor: Mapping[str, Any],
        maximum_encoded_bytes: int,
    ) -> Any:
        """Decode one field using the event-wire grammar."""

        return decode_event_wire_field(
            name, value, descriptor, maximum_encoded_bytes
        )

    def decode_predicate_literal(
        self,
        name: str,
        value: Any,
        descriptor: Mapping[str, Any],
        maximum_encoded_bytes: int,
    ) -> Any:
        """Decode one signature-covered matcher literal."""

        return decode_predicate_field(
            name, value, descriptor, maximum_encoded_bytes
        )

    def validate_event_size(self, event: Mapping[str, Any], schema: EventSchema) -> None:
        if len(canonicalize(event)) > schema.maximum_encoded_event_bytes:
            raise ValidationError("event exceeds registry maximum_encoded_event_bytes")


def verify_generated_registry_bytes(data: bytes) -> None:
    """Verify a release-supplied registry equals the compiled contract exactly."""

    digest = hashlib.sha256(canonicalize(json.loads(data))).hexdigest()
    if digest != EVENT_SOURCE_REGISTRY_SHA256:
        raise ValidationError("event-source registry digest mismatch")


def _validate_profile_value(
    source_id: str,
    name: str,
    value: Any,
    descriptor: Mapping[str, Any],
) -> None:
    kind = descriptor["type"]
    label = f"{source_id}.{name}"
    if kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValidationError(f"{label} must be an integer")
        if not int(descriptor["minimum"]) <= value <= int(descriptor["maximum"]):
            raise ValidationError(f"{label} is outside bounds")
    elif kind == "boolean":
        if not isinstance(value, bool):
            raise ValidationError(f"{label} must be boolean")
    elif kind == "enum":
        if not isinstance(value, str) or value not in descriptor["enum_values"]:
            raise ValidationError(f"{label} is an invalid enum")
    elif kind == "enum_array":
        if (
            not isinstance(value, list)
            or not value
            or any(not isinstance(item, str) for item in value)
            or any(item not in descriptor["enum_values"] for item in value)
            or value != sorted(set(value))
        ):
            raise ValidationError(f"{label} must be a sorted unique enum array")
    else:
        raise ValidationError(f"unknown registry profile type: {kind}")


def decode_event_wire_field(
    name: str,
    value: Any,
    descriptor: Mapping[str, Any],
    maximum_encoded_bytes: int,
) -> Any:
    """Validate and decode a registry field admitted from an event envelope."""

    if not isinstance(maximum_encoded_bytes, int) or maximum_encoded_bytes < 1:
        raise ValidationError("invalid event encoded-size bound")
    if not isinstance(value, str):
        raise ValidationError(f"event field {name} must use string encoding")
    if len(value.encode("utf-8")) > maximum_encoded_bytes:
        raise ValidationError(f"event field {name} exceeds its encoded-size bound")
    return _convert_event_wire_field(name, value, descriptor)


def decode_predicate_field(
    name: str,
    value: Any,
    descriptor: Mapping[str, Any],
    maximum_encoded_bytes: int,
) -> Any:
    """Decode a signed matcher literal with canonical float spelling."""

    decoded = decode_event_wire_field(
        name, value, descriptor, maximum_encoded_bytes
    )
    if (
        descriptor["wire_type"] in ("float32", "float64")
        and (
            not isinstance(decoded, float)
            or canonical_double_string(decoded) != value
        )
    ):
        raise ValidationError(
            f"predicate field {name} must use canonical Java double spelling"
        )
    return decoded


def _convert_event_wire_field(
    name: str, value: Any, descriptor: Mapping[str, Any]
) -> Any:
    kind = descriptor["wire_type"]
    if kind == "uint64_decimal":
        if not _UNSIGNED.fullmatch(value):
            raise ValidationError(f"{name} must be an unsigned decimal integer")
        number = int(value)
        if number >= 2**64:
            raise ValidationError(f"{name} is outside uint64")
        minimum, maximum = descriptor["minimum"], descriptor["maximum"]
        if minimum is not None and number < int(minimum):
            raise ValidationError(f"{name} is below minimum")
        if maximum is not None and number > int(maximum):
            raise ValidationError(f"{name} is above maximum")
        return number
    if kind == "int32":
        if not _SIGNED.fullmatch(value):
            raise ValidationError(f"{name} must be a signed decimal integer")
        number = int(value)
        if not -(2**31) <= number < 2**31:
            raise ValidationError(f"{name} is outside int32")
        minimum, maximum = descriptor["minimum"], descriptor["maximum"]
        if minimum is not None and number < int(minimum):
            raise ValidationError(f"{name} is below minimum")
        if maximum is not None and number > int(maximum):
            raise ValidationError(f"{name} is above maximum")
        return number
    if kind == "int64_decimal":
        if not _SIGNED.fullmatch(value):
            raise ValidationError(f"{name} must be a signed decimal integer")
        number = int(value)
        if not -(2**63) <= number < 2**63:
            raise ValidationError(f"{name} is outside int64")
        minimum, maximum = descriptor["minimum"], descriptor["maximum"]
        if minimum is not None and number < int(minimum):
            raise ValidationError(f"{name} is below minimum")
        if maximum is not None and number > int(maximum):
            raise ValidationError(f"{name} is above maximum")
        return number
    if kind == "boolean":
        if value not in ("true", "false"):
            raise ValidationError(f"{name} must be true or false")
        return value == "true"
    if kind == "enum":
        if value not in descriptor["enum_values"]:
            raise ValidationError(f"{name} is an invalid enum")
        return value
    if kind in ("float32", "float64"):
        if not _DECIMAL_FLOAT.fullmatch(value):
            raise ValidationError(f"{name} is not a finite decimal float")
        number = float(value)
        if not math.isfinite(number):
            raise ValidationError(f"{name} must be finite")
        if kind == "float32":
            try:
                narrowed = struct.unpack(">f", struct.pack(">f", number))[0]
            except OverflowError as error:
                raise ValidationError(f"{name} is outside float32") from error
            if not math.isfinite(narrowed):
                raise ValidationError(f"{name} is outside float32")
        minimum, maximum = descriptor["minimum"], descriptor["maximum"]
        if minimum is not None and number < float(minimum):
            raise ValidationError(f"{name} is below minimum")
        if maximum is not None and number > float(maximum):
            raise ValidationError(f"{name} is above maximum")
        return number
    if kind == "uuid":
        try:
            parsed = uuid.UUID(value)
        except (ValueError, AttributeError) as error:
            raise ValidationError(f"{name} must be a UUID") from error
        if (
            str(parsed) != value
            or parsed.variant != uuid.RFC_4122
            or parsed.version not in range(1, 6)
        ):
            raise ValidationError(
                f"{name} must use canonical RFC 4122 UUID version 1-5 encoding"
            )
        return value
    if kind == "sha256_hex":
        if not _SHA256.fullmatch(value):
            raise ValidationError(f"{name} must be lowercase SHA-256 hex")
        return value
    if kind == "json_string":
        return parse_embedded_json(value)
    if kind == "string":
        units = len(value.encode("utf-16-le")) // 2
        minimum, maximum = descriptor["minimum_length"], descriptor["maximum_length"]
        if minimum is not None and units < int(minimum):
            raise ValidationError(f"{name} is too short")
        if maximum is not None and units > int(maximum):
            raise ValidationError(f"{name} is too long")
        return value
    raise ValidationError(f"unknown registry event wire type: {kind}")


def canonical_double_string(value: float) -> str:
    """Format a finite binary64 value exactly like Java Double.toString."""

    if not math.isfinite(value):
        raise ValidationError("canonical double must be finite")
    if value == 0:
        return "-0.0" if math.copysign(1.0, value) < 0 else "0.0"
    raw = repr(value).lower()
    mantissa, separator, exponent_text = raw.partition("e")
    negative = mantissa.startswith("-")
    unsigned = mantissa.removeprefix("-")
    whole, _, fraction = unsigned.partition(".")
    if separator:
        digits = (whole + fraction).lstrip("0").rstrip("0") or "0"
        decimal_exponent = int(exponent_text) + len(whole.lstrip("0")) - 1
    elif whole.lstrip("0"):
        significant_whole = whole.lstrip("0")
        digits = (significant_whole + fraction).rstrip("0") or "0"
        decimal_exponent = len(significant_whole) - 1
    else:
        first = next(
            (
                index
                for index, character in enumerate(fraction)
                if character != "0"
            ),
            len(fraction),
        )
        digits = fraction[first:].rstrip("0") or "0"
        decimal_exponent = -first - 1
    sign = "-" if negative else ""
    if -3 <= decimal_exponent < 7:
        point = decimal_exponent + 1
        if point <= 0:
            plain = "0." + "0" * (-point) + digits
        elif point >= len(digits):
            plain = digits + "0" * (point - len(digits)) + ".0"
        else:
            plain = digits[:point] + "." + digits[point:]
        return sign + plain
    tail = digits[1:] or "0"
    return f"{sign}{digits[0]}.{tail}E{decimal_exponent}"
