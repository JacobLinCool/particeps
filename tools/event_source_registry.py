#!/usr/bin/env python3
"""Validate and generate the closed-world Particeps event-source registry."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pprint
import re
import subprocess
import sys
import tempfile
from collections.abc import Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
REGISTRY_PATH = ROOT / "protocol/v1/event-source-registry.json"
SCHEMA_PATH = ROOT / "protocol/v1/event-source-registry.schema.json"
AUTOMATION_REDUCER_CORPUS_PATH = ROOT / "protocol/v1/automation-reducer-vectors.json"
GENERATED_PATHS = {
    "kotlin_events": ROOT / "core/collector-api/src/main/kotlin/cool/jacoblin/particeps/core/collector/ProtocolEventSourceRegistry.kt",
    "kotlin_profiles": ROOT / "core/study-definition/src/main/kotlin/cool/jacoblin/particeps/core/definition/GeneratedCollectorProfileContracts.kt",
    "typescript": ROOT / "web/src/lib/particeps/generated/event-source-registry.ts",
    "python": ROOT / "particeps-analysis/src/particeps_analysis/generated/event_source_registry.py",
    "python_init": ROOT / "particeps-analysis/src/particeps_analysis/generated/__init__.py",
    "registry_digest": ROOT / "protocol/v1/generated/event-source-registry.sha256",
    "android_registry_digest": ROOT / "app/src/main/assets/particeps/event-source-registry.sha256",
    "contract_digests": ROOT / "protocol/v1/generated/event-source-contract-digests.json",
    "registry_docs": ROOT / "docs/generated/event-source-registry.md",
    "platform_docs": ROOT / "docs/generated/platform-capabilities.md",
    "conformance_jcs": ROOT / "protocol/v1/conformance/event-source-registry/current-registry.jcs",
    "conformance_manifest": ROOT / "protocol/v1/conformance/event-source-registry/manifest.json",
    "automation_reducer_registry_binding": AUTOMATION_REDUCER_CORPUS_PATH,
}

SOURCE_ID = re.compile(r"[a-z][a-z0-9_]*\.v[1-9][0-9]*\Z")
MODULE = re.compile(r":[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)*\Z")
UNITS = frozenset(
    {
        "android_sensor_accuracy", "android_tool_type", "byte", "centimeter", "degree",
        "device_relative", "fraction", "hertz", "kilobit_per_second", "lux", "meter",
        "meter_per_second", "meter_per_second_squared", "microsecond", "millilux",
        "millimeter", "millisecond", "minute", "nanosecond", "none", "packet", "percent",
        "radian", "radian_per_second", "second",
    }
)
NUMERIC_TYPES = frozenset({"float32", "float64", "int32", "int64_decimal", "uint64_decimal"})
INTEGER_TYPES = frozenset({"int32", "int64_decimal", "uint64_decimal"})
EQUALITY_TYPES = frozenset({"boolean", "enum", "sha256_hex", "string", "uuid"})
ORDERED_OPERATORS = frozenset({"eq", "gt", "gte", "in", "lt", "lte", "ne"})
EQUALITY_OPERATORS = frozenset({"eq", "in", "ne"})
REQUIRED_SYSTEM_SOURCES = frozenset(
    {"automation_runtime.v1", "interventions.v1", "study_condition.v1", "study_runtime.v1", "timer.v1", "traffic_shaping.v1"}
)
AUDIT_ONLY_STUDY_LIFECYCLE_EVENTS = frozenset(
    {"STUDY_RESUMED", "STUDY_RUNNING", "STUDY_STARTED"}
)


class RegistryError(ValueError):
    """The registry or one of its deterministic projections is invalid."""


def _reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise RegistryError(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def _reject_non_integral(value: str) -> None:
    raise RegistryError(f"non-integral JSON number is forbidden: {value}")


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate,
            parse_float=_reject_non_integral,
            parse_constant=_reject_non_integral,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise RegistryError(f"{path}: {error}") from error
    if not isinstance(value, dict):
        raise RegistryError(f"{path}: root must be an object")
    return value


def load_registry(path: Path = REGISTRY_PATH) -> dict[str, Any]:
    """Load strict integral JSON while rejecting duplicate members and non-finite numbers."""
    return _load_json(path)


def canonical_json(value: Any) -> bytes:
    """Encode the integral Protocol v1 subset of RFC 8785 JCS."""
    if value is None or isinstance(value, (bool, int, str)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if isinstance(value, list):
        return b"[" + b",".join(canonical_json(item) for item in value) + b"]"
    if isinstance(value, dict):
        keys = sorted(value, key=lambda item: item.encode("utf-16-be", "surrogatepass"))
        return b"{" + b",".join(canonical_json(key) + b":" + canonical_json(value[key]) for key in keys) + b"}"
    raise RegistryError(f"unsupported canonical JSON value: {type(value).__name__}")


def _sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


class _SchemaValidator:
    """The deterministic Draft 2020-12 subset used by the checked-in registry schema."""

    def __init__(self, schema: Mapping[str, Any]):
        self.schema = schema

    def validate(self, value: Any) -> None:
        self._visit(value, self.schema, "$")

    def _resolve(self, reference: str) -> Mapping[str, Any]:
        if not reference.startswith("#/"):
            raise RegistryError(f"schema uses unsupported non-local $ref: {reference}")
        current: Any = self.schema
        for component in reference[2:].split("/"):
            component = component.replace("~1", "/").replace("~0", "~")
            if not isinstance(current, dict) or component not in current:
                raise RegistryError(f"schema has unresolved $ref: {reference}")
            current = current[component]
        if not isinstance(current, dict):
            raise RegistryError(f"schema $ref does not resolve to an object: {reference}")
        return current

    def _visit(self, value: Any, schema: Mapping[str, Any], path: str) -> None:
        if "$ref" in schema:
            self._visit(value, self._resolve(schema["$ref"]), path)
            return
        if "oneOf" in schema:
            matches = 0
            failures: list[str] = []
            for candidate in schema["oneOf"]:
                try:
                    self._visit(value, candidate, path)
                    matches += 1
                except RegistryError as error:
                    failures.append(str(error))
            if matches != 1:
                raise RegistryError(f"{path}: expected one schema variant, matched {matches}; " + " | ".join(failures[:3]))
            return
        if "const" in schema and value != schema["const"]:
            raise RegistryError(f"{path}: expected constant {schema['const']!r}")
        if "enum" in schema and value not in schema["enum"]:
            raise RegistryError(f"{path}: value is outside the closed enum")
        expected_type = schema.get("type")
        if expected_type is not None and not self._has_type(value, expected_type):
            raise RegistryError(f"{path}: expected JSON {expected_type}")
        if isinstance(value, dict):
            missing = set(schema.get("required", [])) - set(value)
            if missing:
                raise RegistryError(f"{path}: missing members {sorted(missing)}")
            properties = schema.get("properties", {})
            property_names = schema.get("propertyNames")
            pattern_properties = schema.get("patternProperties", {})
            additional = schema.get("additionalProperties", True)
            for key, item in value.items():
                if property_names is not None:
                    self._visit(key, property_names, f"{path}.<member-name>")
                if key in properties:
                    self._visit(item, properties[key], f"{path}.{key}")
                    continue
                matched = False
                for pattern, candidate in pattern_properties.items():
                    if re.search(pattern, key) is not None:
                        self._visit(item, candidate, f"{path}.{key}")
                        matched = True
                if not matched:
                    if additional is False:
                        raise RegistryError(f"{path}: unknown member {key!r}")
                    if isinstance(additional, dict):
                        self._visit(item, additional, f"{path}.{key}")
        if isinstance(value, list):
            if len(value) < schema.get("minItems", 0):
                raise RegistryError(f"{path}: array is shorter than minItems")
            if "maxItems" in schema and len(value) > schema["maxItems"]:
                raise RegistryError(f"{path}: array is longer than maxItems")
            item_schema = schema.get("items")
            if item_schema is not None:
                for index, item in enumerate(value):
                    self._visit(item, item_schema, f"{path}[{index}]")
        if isinstance(value, str):
            if len(value) < schema.get("minLength", 0):
                raise RegistryError(f"{path}: string is shorter than minLength")
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                raise RegistryError(f"{path}: string is longer than maxLength")
            if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
                raise RegistryError(f"{path}: string does not match {schema['pattern']}")
        if isinstance(value, int) and not isinstance(value, bool):
            if "minimum" in schema and value < schema["minimum"]:
                raise RegistryError(f"{path}: integer is below minimum")
            if "maximum" in schema and value > schema["maximum"]:
                raise RegistryError(f"{path}: integer exceeds maximum")

    @staticmethod
    def _has_type(value: Any, expected: str) -> bool:
        return {
            "array": isinstance(value, list),
            "boolean": isinstance(value, bool),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "null": value is None,
            "object": isinstance(value, dict),
            "string": isinstance(value, str),
        }.get(expected, False)


def _sorted_unique(values: Sequence[Any], path: str, *, key: Any = None) -> None:
    comparable = [key(item) if key else item for item in values]
    if list(values) != sorted(values, key=key) or len(comparable) != len(set(comparable)):
        raise RegistryError(f"{path} must be sorted and unique")


def _config_string_length(descriptor: Mapping[str, Any], value: str) -> int:
    return len(value.encode("utf-8")) if descriptor["length_unit"] == "UTF8_BYTES" else len(value.encode("utf-16-le")) // 2


def _validate_config_value(descriptor: Mapping[str, Any], value: Any, path: str) -> None:
    kind = descriptor["type"]
    if kind == "boolean":
        if not isinstance(value, bool):
            raise RegistryError(f"{path} must be boolean")
    elif kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            raise RegistryError(f"{path} must be an integer")
        if not descriptor["minimum"] <= value <= descriptor["maximum"]:
            raise RegistryError(f"{path} is outside its integer bounds")
    elif kind == "string":
        if not isinstance(value, str):
            raise RegistryError(f"{path} must be a string")
        if not descriptor["minimum_length"] <= _config_string_length(descriptor, value) <= descriptor["maximum_length"]:
            raise RegistryError(f"{path} is outside its string bounds")
    elif kind == "enum":
        if value not in descriptor["enum_values"]:
            raise RegistryError(f"{path} is outside its enum")
    elif kind == "enum_array":
        if not isinstance(value, list):
            raise RegistryError(f"{path} must be an enum array")
        _sorted_unique(value, path)
        if not descriptor["minimum_items"] <= len(value) <= descriptor["maximum_items"]:
            raise RegistryError(f"{path} is outside its item bounds")
        if any(item not in descriptor["enum_values"] for item in value):
            raise RegistryError(f"{path} contains a value outside its enum")
    elif kind == "object":
        if not isinstance(value, dict):
            raise RegistryError(f"{path} must be an object")
        _validate_config_object(descriptor["fields"], value, path)
    else:
        raise RegistryError(f"{path}: unsupported configuration field type {kind}")


def _validate_config_object(descriptors: Mapping[str, Any], value: Mapping[str, Any], path: str) -> None:
    unknown = set(value) - set(descriptors)
    missing = {name for name, descriptor in descriptors.items() if descriptor["required"] and name not in value}
    if unknown or missing:
        raise RegistryError(f"{path}: configuration members differ; missing={sorted(missing)}, unknown={sorted(unknown)}")
    for name, item in value.items():
        _validate_config_value(descriptors[name], item, f"{path}.{name}")
    for name, descriptor in descriptors.items():
        upper = descriptor.get("less_than_or_equal_field")
        if upper is not None and name in value and upper in value and value[name] > value[upper]:
            raise RegistryError(f"{path}.{name} exceeds {upper}")


def _validate_configuration(configuration: Mapping[str, Any], path: str) -> None:
    fields = configuration["fields"]
    if list(fields) != sorted(fields):
        raise RegistryError(f"{path}.fields must be sorted")
    for name, descriptor in fields.items():
        field_path = f"{path}.fields.{name}"
        if descriptor["unit"] not in UNITS:
            raise RegistryError(f"{field_path}.unit is not registered")
        kind = descriptor["type"]
        if kind in {"enum", "enum_array"}:
            _sorted_unique(descriptor["enum_values"], f"{field_path}.enum_values")
        if kind == "integer":
            if descriptor["minimum"] > descriptor["maximum"]:
                raise RegistryError(f"{field_path}: minimum exceeds maximum")
            related = descriptor["less_than_or_equal_field"]
            if related is not None:
                target = fields.get(related)
                if related == name or target is None or target["type"] != "integer":
                    raise RegistryError(f"{field_path}: invalid less_than_or_equal_field")
        elif kind == "string" and descriptor["minimum_length"] > descriptor["maximum_length"]:
            raise RegistryError(f"{field_path}: minimum_length exceeds maximum_length")
        elif kind == "enum_array" and descriptor["minimum_items"] > descriptor["maximum_items"]:
            raise RegistryError(f"{field_path}: minimum_items exceeds maximum_items")
        _validate_config_value(descriptor, descriptor["authoring_default"], f"{field_path}.authoring_default")


def _validate_event_field(descriptor: Mapping[str, Any], path: str, researcher: bool) -> None:
    wire_type = descriptor["wire_type"]
    if descriptor["unit"] not in UNITS:
        raise RegistryError(f"{path}.unit is not registered")
    if descriptor["required"] and descriptor["nullable"]:
        raise RegistryError(f"{path}: required nullable fields are not supported by the v1 string map")
    enum_values = descriptor["enum_values"]
    if wire_type == "enum":
        _sorted_unique(enum_values, f"{path}.enum_values")
        if not enum_values:
            raise RegistryError(f"{path}: enum must declare values")
    elif enum_values:
        raise RegistryError(f"{path}: non-enum field declares enum values")
    minimum, maximum = descriptor["minimum"], descriptor["maximum"]
    if wire_type in NUMERIC_TYPES:
        if minimum is not None and maximum is not None and minimum > maximum:
            raise RegistryError(f"{path}: minimum exceeds maximum")
        if wire_type == "int32" and ((minimum is not None and minimum < -(2**31)) or (maximum is not None and maximum > 2**31 - 1)):
            raise RegistryError(f"{path}: bound exceeds int32")
    elif minimum is not None or maximum is not None:
        raise RegistryError(f"{path}: nonnumeric field declares numeric bounds")
    min_length, max_length = descriptor["minimum_length"], descriptor["maximum_length"]
    if wire_type in {"json_string", "string"}:
        if min_length is None or max_length is None or min_length > max_length or descriptor["length_unit"] is None:
            raise RegistryError(f"{path}: string field needs coherent length bounds")
    elif min_length is not None or max_length is not None or descriptor["length_unit"] is not None:
        raise RegistryError(f"{path}: nonstring field declares length bounds")
    operators = descriptor["operators"]
    _sorted_unique(operators, f"{path}.operators")
    allowed = ORDERED_OPERATORS if wire_type in NUMERIC_TYPES else EQUALITY_OPERATORS if wire_type in EQUALITY_TYPES else frozenset()
    if not set(operators) <= allowed:
        raise RegistryError(f"{path}: incompatible trigger operator")
    if not researcher and (operators or descriptor["keyed_presence_key"] or descriptor["window_sum"]):
        raise RegistryError(f"{path}: non-researcher event exposes trigger metadata")
    if descriptor["keyed_presence_key"] and wire_type == "json_string":
        raise RegistryError(f"{path}: JSON cannot be a presence key")
    if descriptor["window_sum"] and wire_type not in INTEGER_TYPES:
        raise RegistryError(f"{path}: v1 window sum requires an integer field")


def _validate_event(source: Mapping[str, Any], event: Mapping[str, Any], path: str) -> None:
    trigger = event["trigger"]
    researcher = trigger["scope"] == "RESEARCHER"
    fields = event["fields"]
    if list(fields) != sorted(fields):
        raise RegistryError(f"{path}.fields must be sorted")
    for name, descriptor in fields.items():
        _validate_event_field(descriptor, f"{path}.fields.{name}", researcher)
    privacy = event["privacy"]
    _sorted_unique(privacy["prohibited_inferences"], f"{path}.privacy.prohibited_inferences")
    if researcher != (privacy["trigger_exposure"] == "DECLARED_FIELDS_ONLY"):
        raise RegistryError(f"{path}: trigger scope and privacy exposure disagree")
    kinds = trigger["condition_kinds"]
    _sorted_unique(kinds, f"{path}.trigger.condition_kinds")
    if researcher and not kinds:
        raise RegistryError(f"{path}: researcher event has no condition kind")
    if not researcher and (kinds or trigger["presence"] is not None):
        raise RegistryError(f"{path}: non-researcher event exposes condition metadata")
    presence = trigger["presence"]
    if presence is not None:
        _sorted_unique(presence["key_fields"], f"{path}.trigger.presence.key_fields")
        if f"KEYED_PRESENCE_{presence['role']}" not in kinds:
            raise RegistryError(f"{path}: presence role is absent from condition_kinds")
        for name in presence["key_fields"]:
            descriptor = fields.get(name)
            if descriptor is None or not descriptor["required"] or not descriptor["keyed_presence_key"]:
                raise RegistryError(f"{path}: invalid presence key {name}")
    clock = event["clock"]
    _sorted_unique(clock["automation_time_inputs"], f"{path}.clock.automation_time_inputs")
    primary, basis = clock["primary_source_time_field"], clock["primary_source_basis"]
    if (primary is None) != (basis == "NONE"):
        raise RegistryError(f"{path}: primary source clock field/basis mismatch")
    if primary is not None:
        descriptor = fields.get(primary)
        if descriptor is None or not descriptor["required"] or descriptor["clock_basis"] != basis:
            raise RegistryError(f"{path}: primary source clock is not a matching required field")
    latency_field = event["delivery"]["latency_configuration_field"]
    if latency_field is not None and (source["configuration"] is None or latency_field not in source["configuration"]["fields"]):
        raise RegistryError(f"{path}: delivery references unknown configuration field")
    completeness = event["completeness"]
    if completeness["may_have_quality_gaps"] == (completeness["quality_gap_policy"] == "NOT_APPLICABLE"):
        raise RegistryError(f"{path}: completeness gap policy is contradictory")
    rate = event["rate_bound"]
    finite = rate["kind"] in {"CONFIGURATION_DERIVED", "HARD"}
    values = (rate["maximum_events_per_batch"], rate["maximum_events_per_hour"], rate["maximum_events_per_period"], rate["period_seconds"])
    if finite != all(item is not None for item in values) or (not finite and any(item is not None for item in values)):
        raise RegistryError(f"{path}: rate contract is falsely bounded or incomplete")


def validate_registry(registry: Mapping[str, Any], *, project_root: Path = ROOT) -> None:
    """Validate Draft 2020-12 structure, closed-world semantics, and compiled module claims."""
    schema = _load_json(SCHEMA_PATH)
    if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise RegistryError("registry schema must declare JSON Schema Draft 2020-12")
    _SchemaValidator(schema).validate(registry)
    sources = registry["sources"]
    _sorted_unique(sources, "$.sources", key=lambda source: (source["source_id"], source["schema_version"]))
    identities: set[tuple[str, int, str]] = set()
    presence_groups: dict[str, list[tuple[Mapping[str, Any], Mapping[str, Any]]]] = {}
    system_ids: set[str] = set()
    for index, source in enumerate(sources):
        path = f"$.sources[{index}]"
        source_id = source["source_id"]
        if not SOURCE_ID.fullmatch(source_id):
            raise RegistryError(f"{path}.source_id is invalid")
        system = source["source_kind"] == "SYSTEM"
        if system:
            system_ids.add(source_id)
            if source["emission_authority"] != "RUNTIME_ONLY" or source["selectable"] or source["configuration"] is not None:
                raise RegistryError(f"{path}: invalid system-source authority/configuration")
            if source["access"] or source["disclosure_key"] is not None:
                raise RegistryError(f"{path}: system source cannot disclose or request access")
        else:
            if source["emission_authority"] != "SOURCE_PLUGIN_ONLY" or not source["selectable"] or source["configuration"] is None:
                raise RegistryError(f"{path}: invalid collector-source authority/configuration")
            if source["disclosure_key"] is None:
                raise RegistryError(f"{path}: collector needs a data-category disclosure")
            _validate_configuration(source["configuration"], f"{path}.configuration")
        _sorted_unique(source["platforms"], f"{path}.platforms")
        _sorted_unique(source["access"], f"{path}.access", key=lambda item: (item["kind"], item["mode"], item["absence_policy"]))
        implementation = source["implementation"]
        module = implementation["owner_module"]
        if not MODULE.fullmatch(module):
            raise RegistryError(f"{path}.implementation.owner_module is invalid")
        statuses = implementation["statuses"]
        _sorted_unique(statuses, f"{path}.implementation.statuses", key=lambda item: item["platform"])
        if [item["platform"] for item in statuses] != source["platforms"]:
            raise RegistryError(f"{path}: implementation statuses do not cover producer platforms")
        if any(item["status"] == "IMPLEMENTED" for item in statuses):
            module_path = project_root.joinpath(*module.removeprefix(":").split(":"))
            if not module_path.is_dir():
                raise RegistryError(f"{path}: implemented owner module is absent: {module}")
        events = source["events"]
        _sorted_unique(events, f"{path}.events", key=lambda event: event["event_type"])
        for event_index, event in enumerate(events):
            event_path = f"{path}.events[{event_index}]"
            identity = (source_id, source["schema_version"], event["event_type"])
            if identity in identities:
                raise RegistryError(f"{event_path}: duplicate event identity {identity}")
            identities.add(identity)
            _validate_event(source, event, event_path)
            if (
                source_id == "study_runtime.v1"
                and event["event_type"] in AUDIT_ONLY_STUDY_LIFECYCLE_EVENTS
                and event["trigger"]["scope"] != "AUDIT_ONLY"
            ):
                raise RegistryError(
                    f"{event_path}: lifecycle audit event must remain AUDIT_ONLY"
                )
            presence = event["trigger"]["presence"]
            if presence is not None:
                presence_groups.setdefault(presence["group_id"], []).append((event, presence))
    missing_system = REQUIRED_SYSTEM_SOURCES - system_ids
    if missing_system:
        raise RegistryError(f"registry is missing required system sources: {sorted(missing_system)}")
    for group_id, members in presence_groups.items():
        if {presence["role"] for _, presence in members} != {"ENTER", "EXIT"}:
            raise RegistryError(f"presence group {group_id} must have ENTER and EXIT events")
        first_event, first_presence = members[0]
        expected = [(name, first_event["fields"][name]["wire_type"], first_event["fields"][name]["clock_basis"]) for name in first_presence["key_fields"]]
        for event, presence in members[1:]:
            actual = [(name, event["fields"][name]["wire_type"], event["fields"][name]["clock_basis"]) for name in presence["key_fields"]]
            if actual != expected:
                raise RegistryError(f"presence group {group_id} has incompatible keys")


def semantic_projection(source: Mapping[str, Any]) -> dict[str, Any]:
    """Return the immutable event-schema-versioned projection for one source contract."""
    return {
        "configuration": source["configuration"],
        "emission_authority": source["emission_authority"],
        "events": source["events"],
        "schema_version": source["schema_version"],
        "source_id": source["source_id"],
        "source_kind": source["source_kind"],
    }


def check_immutability(previous: Mapping[str, Any], current: Mapping[str, Any]) -> None:
    """Reject mutation or deletion of a previously published source/schema contract."""
    if previous.get("registry_format") != "particeps-event-source-registry-v1":
        raise RegistryError("merge-base registry is absent or has an unsupported format")
    old = {(source["source_id"], source["schema_version"]): semantic_projection(source) for source in previous["sources"]}
    new = {(source["source_id"], source["schema_version"]): semantic_projection(source) for source in current["sources"]}
    for identity, contract in old.items():
        if identity not in new:
            raise RegistryError(f"published source contract was deleted: {identity}")
        if canonical_json(contract) != canonical_json(new[identity]):
            raise RegistryError(f"published source contract changed in place: {identity}")


class _AutomationCorpusRegistryValidator:
    """Validate every reducer-corpus reference before rebinding its registry digest."""

    def __init__(self, registry: Mapping[str, Any]):
        self.sources_by_id: dict[str, list[Mapping[str, Any]]] = {}
        self.events: dict[tuple[str, int, str], Mapping[str, Any]] = {}
        for source in registry["sources"]:
            self.sources_by_id.setdefault(source["source_id"], []).append(source)
            for event in source["events"]:
                self.events[(source["source_id"], source["schema_version"], event["event_type"])] = event

    @staticmethod
    def _object(value: Any, path: str) -> Mapping[str, Any]:
        if not isinstance(value, dict):
            raise RegistryError(f"{path} must be an object")
        return value

    @staticmethod
    def _array(value: Any, path: str) -> list[Any]:
        if not isinstance(value, list):
            raise RegistryError(f"{path} must be an array")
        return value

    def _source(self, source_id: Any, path: str, *, kind: str | None = None) -> Mapping[str, Any]:
        if not isinstance(source_id, str):
            raise RegistryError(f"{path} must be a source ID")
        matches = self.sources_by_id.get(source_id, [])
        if len(matches) != 1:
            raise RegistryError(f"{path} references an absent or ambiguous registry source: {source_id!r}")
        source = matches[0]
        if kind is not None and source["source_kind"] != kind:
            raise RegistryError(f"{path} references {source_id!r} as {kind}, not {source['source_kind']}")
        return source

    def _event(self, value: Any, path: str) -> Mapping[str, Any]:
        reference = self._object(value, path)
        source_id = reference.get("source_id")
        schema_version = reference.get("schema_version")
        event_type = reference.get("event_type")
        if not isinstance(source_id, str) or isinstance(schema_version, bool) or not isinstance(schema_version, int) or not isinstance(event_type, str):
            raise RegistryError(f"{path} is not an exact event identity")
        self._source(source_id, f"{path}.source_id")
        contract = self.events.get((source_id, schema_version, event_type))
        if contract is None:
            raise RegistryError(
                f"{path} references an absent registry event: {(source_id, schema_version, event_type)!r}"
            )
        return contract

    def _selector(
        self,
        value: Any,
        path: str,
        condition_kind: str,
    ) -> Mapping[str, Any]:
        selector = self._object(value, path)
        event = self._event(selector.get("event"), f"{path}.event")
        trigger = event["trigger"]
        if trigger["scope"] != "RESEARCHER" or condition_kind not in trigger["condition_kinds"]:
            raise RegistryError(
                f"{path}.event does not permit researcher condition kind {condition_kind}"
            )
        for index, raw_predicate in enumerate(self._array(selector.get("predicates"), f"{path}.predicates")):
            predicate_path = f"{path}.predicates[{index}]"
            predicate = self._object(raw_predicate, predicate_path)
            field_name = predicate.get("field")
            operator = predicate.get("operator")
            field = event["fields"].get(field_name) if isinstance(field_name, str) else None
            if field is None:
                raise RegistryError(f"{predicate_path}.field references an absent event field: {field_name!r}")
            if operator not in field["operators"]:
                raise RegistryError(
                    f"{predicate_path}.operator {operator!r} is not permitted for field {field_name!r}"
                )
        return event

    @staticmethod
    def _evaluation_clock(event: Mapping[str, Any], value: Any, path: str) -> None:
        if value not in event["clock"]["automation_time_inputs"]:
            raise RegistryError(f"{path} is not permitted by the selected event clock contract")

    def _window(self, value: Mapping[str, Any], path: str) -> None:
        aggregate = self._object(value.get("aggregate"), f"{path}.aggregate")
        aggregate_type = aggregate.get("type")
        condition_kind = "WINDOW_SUM" if aggregate_type == "sum" else "WINDOW_COUNT"
        if aggregate_type not in {"count", "sum"}:
            raise RegistryError(f"{path}.aggregate.type is outside the reducer corpus closed world")
        event = self._selector(value.get("selector"), f"{path}.selector", condition_kind)
        self._evaluation_clock(event, value.get("evaluation_clock"), f"{path}.evaluation_clock")
        if aggregate_type == "sum":
            field_name = aggregate.get("field")
            field = event["fields"].get(field_name) if isinstance(field_name, str) else None
            if field is None or not field["window_sum"] or field["wire_type"] not in INTEGER_TYPES:
                raise RegistryError(f"{path}.aggregate.field is not a permitted exact-integer window sum")

    def _condition(self, value: Any, path: str) -> None:
        condition = self._object(value, path)
        kind = condition.get("type")
        if kind == "study_session_active" or kind == "elapsed_at_least":
            return
        if kind in {"all", "any"}:
            for index, child in enumerate(self._array(condition.get("conditions"), f"{path}.conditions")):
                self._condition(child, f"{path}.conditions[{index}]")
            return
        if kind in {"not", "held_for"}:
            self._condition(condition.get("condition"), f"{path}.condition")
            return
        if kind == "event_latch":
            for member in ("set_when", "reset_when"):
                for index, selector in enumerate(self._array(condition.get(member), f"{path}.{member}")):
                    self._selector(selector, f"{path}.{member}[{index}]", "EVENT_MATCH")
            return
        if kind == "keyed_presence":
            key_field = condition.get("key_field")
            groups: set[str] = set()
            for member, role in (("enter_when", "ENTER"), ("exit_when", "EXIT")):
                condition_kind = f"KEYED_PRESENCE_{role}"
                for index, selector in enumerate(self._array(condition.get(member), f"{path}.{member}")):
                    selector_path = f"{path}.{member}[{index}]"
                    event = self._selector(selector, selector_path, condition_kind)
                    presence = event["trigger"]["presence"]
                    field = event["fields"].get(key_field) if isinstance(key_field, str) else None
                    if (
                        presence is None
                        or presence["role"] != role
                        or presence["key_fields"] != [key_field]
                        or field is None
                        or not field["keyed_presence_key"]
                    ):
                        raise RegistryError(f"{selector_path} does not permit keyed presence field {key_field!r}")
                    groups.add(presence["group_id"])
            if len(groups) != 1:
                raise RegistryError(f"{path} combines incompatible keyed-presence groups")
            return
        if kind == "window_threshold":
            self._window(condition, path)
            return
        raise RegistryError(f"{path}.type is outside the reducer corpus condition closed world: {kind!r}")

    def _trigger(self, value: Any, path: str) -> None:
        trigger = self._object(value, path)
        kind = trigger.get("type")
        if kind == "event_match":
            event = self._selector(trigger.get("selector"), f"{path}.selector", "EVENT_MATCH")
            self._evaluation_clock(event, trigger.get("evaluation_clock"), f"{path}.evaluation_clock")
            return
        if kind == "sequence":
            for index, selector in enumerate(self._array(trigger.get("steps"), f"{path}.steps")):
                event = self._selector(selector, f"{path}.steps[{index}]", "SEQUENCE_STEP")
                self._evaluation_clock(event, trigger.get("evaluation_clock"), f"{path}.evaluation_clock")
            return
        if kind == "window_threshold":
            self._window(trigger, path)
            return
        if kind == "condition_rising_edge":
            self._condition(trigger.get("condition"), f"{path}.condition")
            return
        if kind == "schedule":
            return
        raise RegistryError(f"{path}.type is outside the reducer corpus trigger closed world: {kind!r}")

    def _configuration(self, value: Any, path: str) -> None:
        configuration = self._object(value, path)
        for index, raw_collector in enumerate(self._array(configuration.get("collectors"), f"{path}.collectors")):
            collector_path = f"{path}.collectors[{index}]"
            collector = self._object(raw_collector, collector_path)
            source = self._source(collector.get("id"), f"{collector_path}.id", kind="COLLECTOR")
            for profile_index, raw_profile in enumerate(self._array(collector.get("profiles"), f"{collector_path}.profiles")):
                profile_path = f"{collector_path}.profiles[{profile_index}]"
                profile = self._object(raw_profile, profile_path)
                profile_config = self._object(profile.get("config"), f"{profile_path}.config")
                _validate_config_object(source["configuration"]["fields"], profile_config, f"{profile_path}.config")
        for index, raw_automation in enumerate(self._array(configuration.get("automations"), f"{path}.automations")):
            automation_path = f"{path}.automations[{index}]"
            automation = self._object(raw_automation, automation_path)
            automation_type = automation.get("type")
            if automation_type == "occurrence":
                self._trigger(automation.get("trigger"), f"{automation_path}.trigger")
                if automation.get("guard") is not None:
                    self._condition(automation["guard"], f"{automation_path}.guard")
            elif automation_type == "resource_binding":
                resource = self._object(automation.get("resource"), f"{automation_path}.resource")
                resource_kind = resource.get("kind")
                if resource_kind == "collector":
                    self._source(resource.get("id"), f"{automation_path}.resource.id", kind="COLLECTOR")
                elif resource_kind == "actuator":
                    if not isinstance(resource.get("id"), str) or not resource["id"]:
                        raise RegistryError(f"{automation_path}.resource.id must be an actuator resource ID")
                else:
                    raise RegistryError(f"{automation_path}.resource.kind is outside the resource closed world")
                for case_index, raw_case in enumerate(self._array(automation.get("cases"), f"{automation_path}.cases")):
                    case = self._object(raw_case, f"{automation_path}.cases[{case_index}]")
                    self._condition(case.get("condition"), f"{automation_path}.cases[{case_index}].condition")
            else:
                raise RegistryError(f"{automation_path}.type is outside the automation closed world")

    def _input(self, value: Any, path: str) -> None:
        reducer_input = self._object(value, path)
        input_type = reducer_input.get("type")
        if input_type == "EVENT":
            event_value = self._object(reducer_input.get("event"), f"{path}.event")
            event = self._event(event_value, f"{path}.event")
            fields = self._object(event_value.get("fields"), f"{path}.event.fields")
            unknown = set(fields) - set(event["fields"])
            missing = {name for name, field in event["fields"].items() if field["required"] and name not in fields}
            if unknown or missing:
                raise RegistryError(
                    f"{path}.event.fields differ from the registry; missing={sorted(missing)}, unknown={sorted(unknown)}"
                )
            for name, field_value in fields.items():
                if not isinstance(field_value, str) and not (
                    field_value is None and event["fields"][name]["nullable"]
                ):
                    raise RegistryError(f"{path}.event.fields.{name} is not a canonical string value")
        elif input_type == "QUALITY_GAP":
            self._source(reducer_input.get("source_id"), f"{path}.source_id")

    def validate(self, corpus: Mapping[str, Any]) -> None:
        if corpus.get("format") != "particeps-automation-reducer-v1":
            raise RegistryError("automation reducer corpus has an unsupported format")
        digest = corpus.get("registry_sha256")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise RegistryError("automation reducer corpus has a malformed registry_sha256")
        for scenario_index, raw_scenario in enumerate(self._array(corpus.get("scenarios"), "$.scenarios")):
            scenario_path = f"$.scenarios[{scenario_index}]"
            scenario = self._object(raw_scenario, scenario_path)
            self._configuration(scenario.get("configuration"), f"{scenario_path}.configuration")
            for step_index, raw_step in enumerate(self._array(scenario.get("steps"), f"{scenario_path}.steps")):
                step = self._object(raw_step, f"{scenario_path}.steps[{step_index}]")
                self._input(step.get("input"), f"{scenario_path}.steps[{step_index}].input")


def _render_automation_reducer_registry_binding(registry: Mapping[str, Any], digest: str) -> bytes:
    """Bind the hand-curated reducer corpus to the current validated registry."""
    corpus = _load_json(AUTOMATION_REDUCER_CORPUS_PATH)
    _AutomationCorpusRegistryValidator(registry).validate(corpus)
    rebound = dict(corpus)
    rebound["registry_sha256"] = digest
    return json.dumps(rebound, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"


def _kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _kotlin_nullable_string(value: str | None) -> str:
    return "null" if value is None else _kotlin_string(value)


def _kotlin_collection(values: Iterable[str], *, kind: str, enum_type: str | None = None) -> str:
    rendered = [f"{enum_type}.{value}" if enum_type else _kotlin_string(value) for value in values]
    return f"empty{kind.title()}()" if not rendered else f"{kind}Of(" + ", ".join(rendered) + ")"


def _render_kotlin_event_registry(registry: Mapping[str, Any], digest: str) -> str:
    lines = [
        "// Generated by tools/event_source_registry.py. Do not edit.",
        "package cool.jacoblin.particeps.core.collector",
        "",
        "import com.google.gson.Strictness",
        "import com.google.gson.stream.JsonReader",
        "import com.google.gson.stream.JsonToken",
        "import java.io.StringReader",
        "import java.math.BigInteger",
        "",
        "enum class RegistrySourceKind { COLLECTOR, SYSTEM }",
        "enum class RegistryEmissionAuthority { RUNTIME_ONLY, SOURCE_PLUGIN_ONLY }",
        "enum class RegistryFieldWireType { BOOLEAN, ENUM, FLOAT32, FLOAT64, INT32, INT64_DECIMAL, JSON_STRING, SHA256_HEX, STRING, UINT64_DECIMAL, UUID }",
        "enum class RegistryFieldOperator(val wireValue: String) { EQ(\"eq\"), GT(\"gt\"), GTE(\"gte\"), IN(\"in\"), LT(\"lt\"), LTE(\"lte\"), NE(\"ne\") }",
        "enum class RegistryTriggerScope { AUDIT_ONLY, RESEARCHER, RUNTIME_ONLY }",
        "enum class RegistryConditionKind { EVENT_MATCH, KEYED_PRESENCE_ENTER, KEYED_PRESENCE_EXIT, SEQUENCE_STEP, WINDOW_COUNT, WINDOW_SUM }",
        "enum class RegistryClockBasis { BOOT_SESSION_MONOTONIC, CONTINUOUS_MONOTONIC_SINCE_BOOT, NONE, SOURCE_DEFINED, UTC_WALL }",
        "enum class RegistryDeliveryKind { CALLBACK, POLL, RUNTIME_SYNCHRONOUS }",
        "enum class RegistryDeliveryGuarantee { BEST_EFFORT, BEST_OBSERVED, DETERMINISTIC_AFTER_COMMIT }",
        "enum class RegistryCompletenessKind { AGGREGATED_INTERVAL, BEST_EFFORT_CALLBACK, COALESCED, COMPLETE_FOR_RUNTIME_CAUSES, PLATFORM_HISTORY_BOUNDED, SAMPLED }",
        "enum class RegistryQualityGapPolicy { EMIT_AND_CONTINUE, FAIL_CLOSED_FOR_DEPENDENT_AUTOMATIONS, NOT_APPLICABLE }",
        "enum class RegistryPrivacyClass { INTERNAL, RESTRICTED, SENSITIVE }",
        "enum class RegistryAuditCopyPolicy { IDENTIFIERS_ONLY, NO_FIELD_VALUES }",
        "enum class RegistryTriggerExposure { DECLARED_FIELDS_ONLY, NONE }",
        "enum class RegistryRateKind { CONFIGURATION_DERIVED, HARD, PLATFORM_ONLY, UNBOUNDED }",
        "enum class RegistryOverflowPolicy { COALESCE_WITH_SOURCE_TIME, EMIT_QUALITY_GAP, FAIL_SOURCE }",
        "enum class RegistryCrossBootPolicy { NOT_APPLICABLE, RESET }",
        "enum class RegistryWallClockChangePolicy { QUALITY_GAP, UNAFFECTED }",
        "enum class RegistryImplementationStatus { IMPLEMENTED, PLANNED }",
        "",
        "sealed interface RegistryTypedValue {",
        "    data object NullValue : RegistryTypedValue",
        "    data class BooleanValue(val value: Boolean) : RegistryTypedValue",
        "    data class IntegerValue(val value: BigInteger) : RegistryTypedValue",
        "    data class FloatValue(val value: Double) : RegistryTypedValue",
        "    data class TextValue(val value: String) : RegistryTypedValue",
        "}",
        "",
        "data class RegistryPresenceContract(",
        "    val groupId: String,",
        "    val role: String,",
        "    val keyFields: List<String>,",
        ")",
        "",
        "data class RegistryEventFieldContract(",
        "    val wireType: RegistryFieldWireType,",
        "    val required: Boolean,",
        "    val nullable: Boolean,",
        "    val unit: String,",
        "    val meaning: String,",
        "    val clockBasis: RegistryClockBasis,",
        "    val enumValues: Set<String>,",
        "    val minimum: BigInteger?,",
        "    val maximum: BigInteger?,",
        "    val minimumLength: Int?,",
        "    val maximumLength: Int?,",
        "    val utf8Length: Boolean,",
        "    val operators: Set<RegistryFieldOperator>,",
        "    val keyedPresenceKey: Boolean,",
        "    val windowSum: Boolean,",
        ") {",
        "    fun decode(value: String?): RegistryTypedValue? {",
        "        if (value == null) return if (nullable) RegistryTypedValue.NullValue else null",
        "        if (!lengthAccepts(value)) return null",
        "        return when (wireType) {",
        "            RegistryFieldWireType.BOOLEAN -> when (value) { \"true\" -> RegistryTypedValue.BooleanValue(true); \"false\" -> RegistryTypedValue.BooleanValue(false); else -> null }",
        "            RegistryFieldWireType.ENUM -> value.takeIf(enumValues::contains)?.let(RegistryTypedValue::TextValue)",
        "            RegistryFieldWireType.FLOAT32 -> value.takeIf(FLOAT::matches)?.toFloatOrNull()?.takeIf(Float::isFinite)?.toDouble()?.takeIf(::numberAccepts)?.let(RegistryTypedValue::FloatValue)",
        "            RegistryFieldWireType.FLOAT64 -> value.takeIf(FLOAT::matches)?.toDoubleOrNull()?.takeIf(Double::isFinite)?.takeIf(::numberAccepts)?.let(RegistryTypedValue::FloatValue)",
        "            RegistryFieldWireType.INT32 -> canonicalInteger(value)?.takeIf { it in INT32_MIN..INT32_MAX && integerAccepts(it) }?.let(RegistryTypedValue::IntegerValue)",
        "            RegistryFieldWireType.INT64_DECIMAL -> canonicalInteger(value)?.takeIf { it in INT64_MIN..INT64_MAX && integerAccepts(it) }?.let(RegistryTypedValue::IntegerValue)",
        "            RegistryFieldWireType.UINT64_DECIMAL -> canonicalInteger(value)?.takeIf { it in BigInteger.ZERO..UINT64_MAX && integerAccepts(it) }?.let(RegistryTypedValue::IntegerValue)",
        "            RegistryFieldWireType.JSON_STRING -> value.takeIf(::isStrictJson)?.let(RegistryTypedValue::TextValue)",
        "            RegistryFieldWireType.SHA256_HEX -> value.takeIf(SHA256::matches)?.let(RegistryTypedValue::TextValue)",
        "            RegistryFieldWireType.STRING -> RegistryTypedValue.TextValue(value)",
        "            RegistryFieldWireType.UUID -> value.takeIf(UUID::matches)?.let(RegistryTypedValue::TextValue)",
        "        }",
        "    }",
        "",
        "    private fun lengthAccepts(value: String): Boolean {",
        "        if (minimumLength == null && maximumLength == null) return true",
        "        val length = if (utf8Length) value.toByteArray(Charsets.UTF_8).size else value.length",
        "        return (minimumLength == null || length >= minimumLength) && (maximumLength == null || length <= maximumLength)",
        "    }",
        "    private fun integerAccepts(value: BigInteger) = (minimum == null || value >= minimum) && (maximum == null || value <= maximum)",
        "    private fun numberAccepts(value: Double) = (minimum == null || value >= minimum.toDouble()) && (maximum == null || value <= maximum.toDouble())",
        "",
        "    private companion object {",
        "        val INTEGER = Regex(\"0|-?[1-9][0-9]*\")",
        "        val FLOAT = Regex(\"[+-]?(?:(?:[0-9]+(?:\\\\.[0-9]*)?|\\\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)\")",
        "        val SHA256 = Regex(\"[0-9a-f]{64}\")",
        "        val UUID = Regex(\"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\")",
        "        val INT32_MIN: BigInteger = BigInteger.valueOf(Int.MIN_VALUE.toLong())",
        "        val INT32_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())",
        "        val INT64_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)",
        "        val INT64_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)",
        "        val UINT64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)",
        "        fun canonicalInteger(value: String): BigInteger? = value.takeIf(INTEGER::matches)?.toBigIntegerOrNull()",
        "        fun isStrictJson(value: String): Boolean {",
        "            if (value.startsWith('\\uFEFF')) return false",
        "            return try {",
        "            val reader = JsonReader(StringReader(value)).apply { strictness = Strictness.STRICT }",
        "            val contexts = mutableListOf<MutableSet<String>?>()",
        "            var rootValues = 0",
        "            parse@ while (true) {",
        "                when (reader.peek()) {",
        "                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); contexts.add(null) }",
        "                    JsonToken.END_ARRAY -> { reader.endArray(); contexts.removeAt(contexts.lastIndex); if (contexts.isEmpty()) rootValues++ }",
        "                    JsonToken.BEGIN_OBJECT -> { reader.beginObject(); contexts.add(mutableSetOf()) }",
        "                    JsonToken.END_OBJECT -> { reader.endObject(); contexts.removeAt(contexts.lastIndex); if (contexts.isEmpty()) rootValues++ }",
        "                    JsonToken.NAME -> {",
        "                        val names = contexts.lastOrNull() ?: return false",
        "                        if (!names.add(reader.nextName())) return false",
        "                    }",
        "                    JsonToken.STRING, JsonToken.NUMBER -> { reader.nextString(); if (contexts.isEmpty()) rootValues++ }",
        "                    JsonToken.BOOLEAN -> { reader.nextBoolean(); if (contexts.isEmpty()) rootValues++ }",
        "                    JsonToken.NULL -> { reader.nextNull(); if (contexts.isEmpty()) rootValues++ }",
        "                    JsonToken.END_DOCUMENT -> break@parse",
        "                }",
        "            }",
        "            contexts.isEmpty() && rootValues == 1",
        "            } catch (_: java.io.IOException) { false } catch (_: IllegalStateException) { false }",
        "        }",
        "    }",
        "}",
        "",
        "data class RegistryEventContract(",
        "    val eventType: String,",
        "    val maximumEncodedEventBytes: Int,",
        "    val fields: Map<String, RegistryEventFieldContract>,",
        "    val triggerScope: RegistryTriggerScope,",
        "    val conditionKinds: Set<RegistryConditionKind>,",
        "    val presence: RegistryPresenceContract?,",
        "    val primarySourceBasis: RegistryClockBasis,",
        "    val primarySourceTimeField: String?,",
        "    val automationTimeInputs: Set<String>,",
        "    val crossBootPolicy: RegistryCrossBootPolicy,",
        "    val wallClockChangePolicy: RegistryWallClockChangePolicy,",
        "    val deliveryKind: RegistryDeliveryKind,",
        "    val deliveryGuarantee: RegistryDeliveryGuarantee,",
        "    val latencyConfigurationField: String?,",
        "    val maximumLatencyMillis: Int?,",
        "    val completenessKind: RegistryCompletenessKind,",
        "    val mayHaveQualityGaps: Boolean,",
        "    val orderedWithinObservationBatch: Boolean,",
        "    val qualityGapPolicy: RegistryQualityGapPolicy,",
        "    val privacyClass: RegistryPrivacyClass,",
        "    val exported: Boolean,",
        "    val auditCopyPolicy: RegistryAuditCopyPolicy,",
        "    val triggerExposure: RegistryTriggerExposure,",
        "    val prohibitedInferences: Set<String>,",
        "    val rateKind: RegistryRateKind,",
        "    val ratePeriodSeconds: Int?,",
        "    val maximumEventsPerPeriod: Int?,",
        "    val maximumEventsPerHour: Int?,",
        "    val maximumEventsPerBatch: Int?,",
        "    val rateEnforcedBy: String,",
        "    val overflowPolicy: RegistryOverflowPolicy,",
        ") {",
        "    fun accepts(values: Map<String, String?>): Boolean =",
        "        values.keys.all(fields::containsKey) && fields.all { (name, field) ->",
        "            when {",
        "                name !in values -> !field.required",
        "                values[name] == null -> field.nullable",
        "                else -> field.decode(values[name]) != null",
        "            }",
        "        }",
        "}",
        "",
        "data class RegistryAccessContract(",
        "    val kind: String,",
        "    val mode: String,",
        "    val absencePolicy: String,",
        ")",
        "",
        "data class RegistryImplementationContract(",
        "    val platform: String,",
        "    val status: RegistryImplementationStatus,",
        ")",
        "",
        "data class RegistrySourceContract(",
        "    val sourceId: String,",
        "    val schemaVersion: Int,",
        "    val sourceKind: RegistrySourceKind,",
        "    val emissionAuthority: RegistryEmissionAuthority,",
        "    val selectable: Boolean,",
        "    val ownerModule: String,",
        "    val platforms: Set<String>,",
        "    val disclosureKey: String?,",
        "    val access: List<RegistryAccessContract>,",
        "    val implementationStatuses: List<RegistryImplementationContract>,",
        "    val events: Map<String, RegistryEventContract>,",
        ") {",
        "    val maximumEncodedEventBytes: Int = events.values.maxOf(RegistryEventContract::maximumEncodedEventBytes)",
        "    val isRetrospective: Boolean = events.values.any { it.deliveryKind == RegistryDeliveryKind.POLL }",
        "}",
        "",
        "object ProtocolEventSourceRegistry {",
        f'    const val REGISTRY_SHA256: String = "{digest}"',
        "    val sources: Map<String, RegistrySourceContract> = listOf(",
    ]
    for source in registry["sources"]:
        lines.extend(
            [
                "        RegistrySourceContract(",
                f'            sourceId = {_kotlin_string(source["source_id"])},',
                f'            schemaVersion = {source["schema_version"]},',
                f'            sourceKind = RegistrySourceKind.{source["source_kind"]},',
                f'            emissionAuthority = RegistryEmissionAuthority.{source["emission_authority"]},',
                f'            selectable = {str(source["selectable"]).lower()},',
                f'            ownerModule = {_kotlin_string(source["implementation"]["owner_module"])},',
                f'            platforms = {_kotlin_collection(source["platforms"], kind="set")},',
                f'            disclosureKey = {_kotlin_nullable_string(source["disclosure_key"])},',
                "            access = listOf(",
                *[
                    f'                RegistryAccessContract(kind = {_kotlin_string(item["kind"])}, mode = {_kotlin_string(item["mode"])}, absencePolicy = {_kotlin_string(item["absence_policy"])}),'
                    for item in source["access"]
                ],
                "            ),",
                "            implementationStatuses = listOf(",
                *[
                    f'                RegistryImplementationContract(platform = {_kotlin_string(item["platform"])}, status = RegistryImplementationStatus.{item["status"]}),'
                    for item in source["implementation"]["statuses"]
                ],
                "            ),",
                "            events = listOf(",
            ]
        )
        for event in source["events"]:
            rate = event["rate_bound"]
            clock = event["clock"]
            delivery = event["delivery"]
            completeness = event["completeness"]
            privacy = event["privacy"]
            trigger_data = event["trigger"]
            presence = trigger_data["presence"]
            lines.extend(
                [
                    "                RegistryEventContract(",
                    f'                    eventType = {_kotlin_string(event["event_type"])},',
                    f'                    maximumEncodedEventBytes = {event["maximum_encoded_event_bytes"]},',
                    "                    fields = mapOf(",
                ]
            )
            for name, descriptor in event["fields"].items():
                minimum = "null" if descriptor["minimum"] is None else f'BigInteger({_kotlin_string(str(descriptor["minimum"]))})'
                maximum = "null" if descriptor["maximum"] is None else f'BigInteger({_kotlin_string(str(descriptor["maximum"]))})'
                min_length = "null" if descriptor["minimum_length"] is None else str(descriptor["minimum_length"])
                max_length = "null" if descriptor["maximum_length"] is None else str(descriptor["maximum_length"])
                operators = _kotlin_collection([operator.upper() for operator in descriptor["operators"]], kind="set", enum_type="RegistryFieldOperator")
                lines.extend(
                    [
                        f'                        {_kotlin_string(name)} to RegistryEventFieldContract(',
                        f'                            wireType = RegistryFieldWireType.{descriptor["wire_type"].upper()},',
                        f'                            required = {str(descriptor["required"]).lower()},',
                        f'                            nullable = {str(descriptor["nullable"]).lower()},',
                        f'                            unit = {_kotlin_string(descriptor["unit"])},',
                        f'                            meaning = {_kotlin_string(descriptor["meaning"])},',
                        f'                            clockBasis = RegistryClockBasis.{descriptor["clock_basis"]},',
                        f'                            enumValues = {_kotlin_collection(descriptor["enum_values"], kind="set")},',
                        f"                            minimum = {minimum},",
                        f"                            maximum = {maximum},",
                        f"                            minimumLength = {min_length},",
                        f"                            maximumLength = {max_length},",
                        f'                            utf8Length = {str(descriptor["length_unit"] == "UTF8_BYTES").lower()},',
                        f"                            operators = {operators},",
                        f'                            keyedPresenceKey = {str(descriptor["keyed_presence_key"]).lower()},',
                        f'                            windowSum = {str(descriptor["window_sum"]).lower()},',
                        "                        ),",
                    ]
                )
            lines.append("                    ),")
            if presence is None:
                rendered_presence = "null"
            else:
                keys = _kotlin_collection(presence["key_fields"], kind="list")
                rendered_presence = f'RegistryPresenceContract(groupId = {_kotlin_string(presence["group_id"])}, role = {_kotlin_string(presence["role"])}, keyFields = {keys})'
            lines.extend(
                [
                    f'                    triggerScope = RegistryTriggerScope.{trigger_data["scope"]},',
                    f'                    conditionKinds = {_kotlin_collection(trigger_data["condition_kinds"], kind="set", enum_type="RegistryConditionKind")},',
                    f"                    presence = {rendered_presence},",
                    f'                    primarySourceBasis = RegistryClockBasis.{clock["primary_source_basis"]},',
                    f'                    primarySourceTimeField = {_kotlin_nullable_string(clock["primary_source_time_field"])},',
                    f'                    automationTimeInputs = {_kotlin_collection(clock["automation_time_inputs"], kind="set")},',
                    f'                    crossBootPolicy = RegistryCrossBootPolicy.{clock["cross_boot_policy"]},',
                    f'                    wallClockChangePolicy = RegistryWallClockChangePolicy.{clock["wall_clock_change_policy"]},',
                    f'                    deliveryKind = RegistryDeliveryKind.{delivery["kind"]},',
                    f'                    deliveryGuarantee = RegistryDeliveryGuarantee.{delivery["guarantee"]},',
                    f'                    latencyConfigurationField = {_kotlin_nullable_string(delivery["latency_configuration_field"])},',
                    f'                    maximumLatencyMillis = {"null" if delivery["maximum_latency_millis"] is None else delivery["maximum_latency_millis"]},',
                    f'                    completenessKind = RegistryCompletenessKind.{completeness["kind"]},',
                    f'                    mayHaveQualityGaps = {str(completeness["may_have_quality_gaps"]).lower()},',
                    f'                    orderedWithinObservationBatch = {str(completeness["ordered_within_observation_batch"]).lower()},',
                    f'                    qualityGapPolicy = RegistryQualityGapPolicy.{completeness["quality_gap_policy"]},',
                    f'                    privacyClass = RegistryPrivacyClass.{privacy["class"]},',
                    f'                    exported = {str(privacy["exported"]).lower()},',
                    f'                    auditCopyPolicy = RegistryAuditCopyPolicy.{privacy["audit_copy_policy"]},',
                    f'                    triggerExposure = RegistryTriggerExposure.{privacy["trigger_exposure"]},',
                    f'                    prohibitedInferences = {_kotlin_collection(privacy["prohibited_inferences"], kind="set")},',
                    f'                    rateKind = RegistryRateKind.{rate["kind"]},',
                    f'                    ratePeriodSeconds = {"null" if rate["period_seconds"] is None else rate["period_seconds"]},',
                    f'                    maximumEventsPerPeriod = {"null" if rate["maximum_events_per_period"] is None else rate["maximum_events_per_period"]},',
                    f'                    maximumEventsPerHour = {"null" if rate["maximum_events_per_hour"] is None else rate["maximum_events_per_hour"]},',
                    f'                    maximumEventsPerBatch = {"null" if rate["maximum_events_per_batch"] is None else rate["maximum_events_per_batch"]},',
                    f'                    rateEnforcedBy = {_kotlin_string(rate["enforced_by"])},',
                    f'                    overflowPolicy = RegistryOverflowPolicy.{rate["overflow_policy"]},',
                    "                ),",
                ]
            )
        lines.extend(["            ).associateBy(RegistryEventContract::eventType),", "        ),"])
    lines.extend(
        [
            "    ).associateBy(RegistrySourceContract::sourceId)",
            "",
            "    val collectorSourceIds: Set<String> = sources.values.filter { it.sourceKind == RegistrySourceKind.COLLECTOR }.mapTo(sortedSetOf(), RegistrySourceContract::sourceId)",
            "    val systemSourceIds: Set<String> = sources.values.filter { it.sourceKind == RegistrySourceKind.SYSTEM }.mapTo(sortedSetOf(), RegistrySourceContract::sourceId)",
            "",
            "    operator fun get(sourceId: String): RegistrySourceContract? = sources[sourceId]",
            "    fun event(sourceId: String, schemaVersion: Int, eventType: String): RegistryEventContract? =",
            "        sources[sourceId]?.takeIf { it.schemaVersion == schemaVersion }?.events?.get(eventType)",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def _kotlin_json(value: Any) -> str:
    if value is None:
        return "com.google.gson.JsonNull.INSTANCE"
    if isinstance(value, bool):
        return f"com.google.gson.JsonPrimitive({str(value).lower()})"
    if isinstance(value, int):
        return f'com.google.gson.JsonPrimitive(BigInteger({_kotlin_string(str(value))}))'
    if isinstance(value, str):
        return f"com.google.gson.JsonPrimitive({_kotlin_string(value)})"
    if isinstance(value, list):
        body = "; ".join(f"add({_kotlin_json(item)})" for item in value)
        return f"com.google.gson.JsonArray().apply {{ {body} }}"
    if isinstance(value, dict):
        body = "; ".join(f"add({_kotlin_string(key)}, {_kotlin_json(value[key])})" for key in sorted(value))
        return f"com.google.gson.JsonObject().apply {{ {body} }}"
    raise RegistryError(f"cannot render Kotlin JSON value: {value!r}")


def _render_kotlin_profile_field(descriptor: Mapping[str, Any], indent: str) -> list[str]:
    minimum = "null" if descriptor.get("minimum") is None else f'BigInteger({_kotlin_string(str(descriptor["minimum"]))})'
    maximum = "null" if descriptor.get("maximum") is None else f'BigInteger({_kotlin_string(str(descriptor["maximum"]))})'
    length_unit = descriptor.get("length_unit")
    fields = descriptor.get("fields", {})
    lines = [
        f"{indent}GeneratedProfileFieldContract(",
        f'{indent}    type = GeneratedProfileFieldType.{descriptor["type"].upper()},',
        f'{indent}    required = {str(descriptor["required"]).lower()},',
        f'{indent}    unit = {_kotlin_string(descriptor["unit"])},',
        f'{indent}    meaning = {_kotlin_string(descriptor["meaning"])},',
        f'{indent}    authoringDefault = {_kotlin_json(descriptor["authoring_default"])},',
        f"{indent}    minimum = {minimum},",
        f"{indent}    maximum = {maximum},",
        f'{indent}    minimumLength = {descriptor.get("minimum_length", "null")},',
        f'{indent}    maximumLength = {descriptor.get("maximum_length", "null")},',
        f'{indent}    lengthUnit = {"null" if length_unit is None else "GeneratedLengthUnit." + length_unit},',
        f'{indent}    enumValues = {_kotlin_collection(descriptor.get("enum_values", []), kind="list")},',
        f'{indent}    minimumItems = {descriptor.get("minimum_items", "null")},',
        f'{indent}    maximumItems = {descriptor.get("maximum_items", "null")},',
        f'{indent}    lessThanOrEqualField = {_kotlin_nullable_string(descriptor.get("less_than_or_equal_field"))},',
    ]
    if fields:
        lines.append(f"{indent}    fields = mapOf(")
        for name, child in fields.items():
            child_lines = _render_kotlin_profile_field(child, indent + "        ")
            lines.append(f'{indent}        {_kotlin_string(name)} to')
            lines.extend(child_lines)
            lines[-1] += ","
        lines.append(f"{indent}    ),")
    lines.append(f"{indent})")
    return lines


def _pascal_identifier(value: str) -> str:
    parts = [part for part in re.split(r"[^A-Za-z0-9]+|_", value) if part]
    return "".join(part[0].upper() + part[1:] for part in parts)


def _camel_identifier(value: str) -> str:
    pascal = _pascal_identifier(value)
    return pascal[0].lower() + pascal[1:]


def _profile_class_name(source_id: str) -> str:
    return _pascal_identifier(source_id) + "ProfileConfiguration"


def _profile_enum_name(source_id: str, field_name: str) -> str:
    return _pascal_identifier(source_id) + _pascal_identifier(field_name) + "Value"


def _profile_field_type(source_id: str, name: str, descriptor: Mapping[str, Any]) -> str:
    kind = descriptor["type"]
    base = {
        "boolean": "Boolean",
        "integer": "Long",
        "string": "String",
        "enum": _profile_enum_name(source_id, name),
        "enum_array": f"List<{_profile_enum_name(source_id, name)}>",
    }.get(kind)
    if base is None:
        raise RegistryError(
            f"typed Kotlin profile renderer does not support nested object field yet: {source_id}.{name}"
        )
    return base if descriptor["required"] else f"{base}?"


def _profile_decode_expression(source_id: str, name: str, descriptor: Mapping[str, Any]) -> str:
    access = f'config.get({_kotlin_string(name)})'
    kind = descriptor["type"]
    expression = {
        "boolean": f"{access}.asBoolean",
        "integer": f"{access}.asLong",
        "string": f"{access}.asString",
        "enum": f'{_profile_enum_name(source_id, name)}.fromWire({access}.asString)',
        "enum_array": f'{access}.asJsonArray.map {{ {_profile_enum_name(source_id, name)}.fromWire(it.asString) }}',
    }.get(kind)
    if expression is None:
        raise RegistryError(f"unsupported typed profile decoder: {source_id}.{name}")
    if not descriptor["required"]:
        inner = expression.replace(access, "it")
        return f"{access}?.let {{ {inner} }}"
    return expression


def _profile_encode_expression(name: str, descriptor: Mapping[str, Any], property_name: str) -> str:
    kind = descriptor["type"]
    if kind in {"boolean", "integer", "string"}:
        expression = f"com.google.gson.JsonPrimitive(profile.{property_name})"
    elif kind == "enum":
        expression = f"com.google.gson.JsonPrimitive(profile.{property_name}.wireValue)"
    elif kind == "enum_array":
        expression = f"com.google.gson.JsonArray().apply {{ profile.{property_name}.forEach {{ add(it.wireValue) }} }}"
    else:
        raise RegistryError(f"unsupported typed profile encoder field: {name}")
    if descriptor["required"]:
        return f'add({_kotlin_string(name)}, {expression})'
    nullable_expression = expression.replace(f"profile.{property_name}", "value")
    return f'profile.{property_name}?.let {{ value -> add({_kotlin_string(name)}, {nullable_expression}) }}'


def _render_typed_profile_declarations(registry: Mapping[str, Any]) -> list[str]:
    collectors = [source for source in registry["sources"] if source["source_kind"] == "COLLECTOR"]
    lines = ["sealed interface CollectorProfileConfiguration {", "    val sourceId: String", "}", ""]
    for source in collectors:
        source_id = source["source_id"]
        for name, descriptor in source["configuration"]["fields"].items():
            if descriptor["type"] not in {"enum", "enum_array"}:
                continue
            enum_name = _profile_enum_name(source_id, name)
            lines.append(f"enum class {enum_name}(val wireValue: String) {{")
            for value in descriptor["enum_values"]:
                constant = re.sub(r"[^A-Z0-9_]", "_", value.upper())
                lines.append(f"    {constant}({_kotlin_string(value)}),")
            lines.extend(
                [
                    "    ;",
                    "",
                    "    companion object {",
                    f"        fun fromWire(value: String): {enum_name} = entries.singleOrNull {{ it.wireValue == value }}",
                    f'            ?: throw IllegalArgumentException("Unknown {source_id}.{name} value: $value")',
                    "    }",
                    "}",
                    "",
                ]
            )
    for source in collectors:
        source_id = source["source_id"]
        class_name = _profile_class_name(source_id)
        fields = source["configuration"]["fields"]
        lines.append(f"data class {class_name}(")
        for name, descriptor in fields.items():
            lines.append(
                f"    val {_camel_identifier(name)}: {_profile_field_type(source_id, name, descriptor)},"
            )
        lines.extend(
            [
                "    override val sourceId: String = SOURCE_ID,",
                ") : CollectorProfileConfiguration {",
                "    init {",
                "        require(sourceId == SOURCE_ID) { \"Collector profile source ID is immutable\" }",
            ]
        )
        for name, descriptor in fields.items():
            prop = _camel_identifier(name)
            prefix = f"{prop}?.let {{ value -> " if not descriptor["required"] else ""
            suffix = " }" if not descriptor["required"] else ""
            value = "value" if not descriptor["required"] else prop
            kind = descriptor["type"]
            if kind == "integer":
                lines.append(
                    f'        {prefix}require({value} in {descriptor["minimum"]}L..{descriptor["maximum"]}L) {{ "Invalid {source_id}.{name}" }}{suffix}'
                )
            elif kind == "string":
                if descriptor["length_unit"] == "UTF8_BYTES":
                    length = f"{value}.toByteArray(Charsets.UTF_8).size"
                else:
                    length = f"{value}.length"
                lines.append(
                    f'        {prefix}require({length} in {descriptor["minimum_length"]}..{descriptor["maximum_length"]}) {{ "Invalid {source_id}.{name}" }}{suffix}'
                )
            elif kind == "enum_array":
                lines.append(
                    f'        {prefix}require({value}.size in {descriptor["minimum_items"]}..{descriptor["maximum_items"]} && {value}.map {{ it.wireValue }} == {value}.map {{ it.wireValue }}.sorted().distinct()) {{ "Invalid {source_id}.{name}" }}{suffix}'
                )
            upper = descriptor.get("less_than_or_equal_field")
            if upper is not None:
                upper_prop = _camel_identifier(upper)
                if descriptor["required"] and fields[upper]["required"]:
                    lines.append(
                        f'        require({prop} <= {upper_prop}) {{ "{source_id}.{name} exceeds {upper}" }}'
                    )
                else:
                    lines.append(
                        f'        if ({prop} != null && {upper_prop} != null) require({prop} <= {upper_prop}) {{ "{source_id}.{name} exceeds {upper}" }}'
                    )
        lines.extend(
            [
                "    }",
                "",
                f'    companion object {{ const val SOURCE_ID: String = {_kotlin_string(source_id)} }}',
                "}",
                "",
            ]
        )
    lines.extend(
        [
            "object GeneratedCollectorProfileCodec {",
            "    fun decode(sourceId: String, config: JsonObject): CollectorProfileConfiguration {",
            "        GeneratedCollectorProfileContracts.requireValid(sourceId, config)",
            "        return when (sourceId) {",
        ]
    )
    for source in collectors:
        source_id = source["source_id"]
        class_name = _profile_class_name(source_id)
        fields = source["configuration"]["fields"]
        lines.extend([f'            {_kotlin_string(source_id)} -> {class_name}('])
        for name, descriptor in fields.items():
            lines.append(
                f"                {_camel_identifier(name)} = {_profile_decode_expression(source_id, name, descriptor)},"
            )
        lines.append("            )")
    lines.extend(
        [
            "            else -> throw IllegalArgumentException(\"Unknown collector source: $sourceId\")",
            "        }",
            "    }",
            "",
            "    fun encode(profile: CollectorProfileConfiguration): JsonObject = JsonObject().apply {",
            "        when (profile) {",
        ]
    )
    for source in collectors:
        class_name = _profile_class_name(source["source_id"])
        lines.append(f"            is {class_name} -> {{")
        for name, descriptor in source["configuration"]["fields"].items():
            lines.append(
                "                "
                + _profile_encode_expression(name, descriptor, _camel_identifier(name))
            )
        lines.append("            }")
    lines.extend(
        [
            "        }",
            "    }",
            "",
            "    fun authoringDefault(sourceId: String): CollectorProfileConfiguration {",
            "        val contract = requireNotNull(GeneratedCollectorProfileContracts[sourceId]) { \"Unknown collector source: $sourceId\" }",
            "        val config = JsonObject().apply { contract.fields.forEach { (name, field) -> add(name, field.authoringDefault.deepCopy()) } }",
            "        return decode(sourceId, config)",
            "    }",
            "}",
            "",
        ]
    )
    return lines


def _render_kotlin_profiles(registry: Mapping[str, Any], digest: str) -> str:
    lines = [
        "// Generated by tools/event_source_registry.py. Do not edit.",
        "package cool.jacoblin.particeps.core.definition",
        "",
        "import com.google.gson.JsonElement",
        "import com.google.gson.JsonObject",
        "import java.math.BigInteger",
        "",
        "enum class GeneratedProfileFieldType { BOOLEAN, ENUM, ENUM_ARRAY, INTEGER, OBJECT, STRING }",
        "enum class GeneratedLengthUnit { UTF16_CODE_UNITS, UTF8_BYTES }",
        "",
        "data class GeneratedProfileFieldContract(",
        "    val type: GeneratedProfileFieldType,",
        "    val required: Boolean,",
        "    val unit: String,",
        "    val meaning: String,",
        "    val authoringDefault: JsonElement,",
        "    val minimum: BigInteger? = null,",
        "    val maximum: BigInteger? = null,",
        "    val minimumLength: Int? = null,",
        "    val maximumLength: Int? = null,",
        "    val lengthUnit: GeneratedLengthUnit? = null,",
        "    val enumValues: List<String> = emptyList(),",
        "    val minimumItems: Int? = null,",
        "    val maximumItems: Int? = null,",
        "    val lessThanOrEqualField: String? = null,",
        "    val fields: Map<String, GeneratedProfileFieldContract> = emptyMap(),",
        ") {",
        "    fun accepts(value: JsonElement): Boolean = when (type) {",
        "        GeneratedProfileFieldType.BOOLEAN -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean",
        "        GeneratedProfileFieldType.INTEGER -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && canonicalInteger(value.asString)?.let { (minimum == null || it >= minimum) && (maximum == null || it <= maximum) } == true",
        "        GeneratedProfileFieldType.STRING -> value.isJsonPrimitive && value.asJsonPrimitive.isString && stringLength(value.asString).let { (minimumLength == null || it >= minimumLength) && (maximumLength == null || it <= maximumLength) }",
        "        GeneratedProfileFieldType.ENUM -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString in enumValues",
        "        GeneratedProfileFieldType.ENUM_ARRAY -> value.isJsonArray && value.asJsonArray.mapNotNull { item -> item.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.takeIf { it.isString }?.asString }.let { items -> items.size == value.asJsonArray.size() && items == items.sorted().distinct() && items.all(enumValues::contains) && (minimumItems == null || items.size >= minimumItems) && (maximumItems == null || items.size <= maximumItems) }",
        "        GeneratedProfileFieldType.OBJECT -> value.isJsonObject && acceptsObject(value.asJsonObject)",
        "    }",
        "",
        "    fun acceptsObject(value: JsonObject): Boolean =",
        "        value.keySet().all(fields::containsKey) &&",
        "            fields.all { (name, contract) -> (!contract.required && !value.has(name)) || (value.has(name) && contract.accepts(value.get(name))) } &&",
        "            fields.all { (name, contract) -> contract.lessThanOrEqualField?.let { upper -> !value.has(name) || !value.has(upper) || value.get(name).asBigInteger() <= value.get(upper).asBigInteger() } ?: true }",
        "",
        "    private fun stringLength(value: String): Int = if (lengthUnit == GeneratedLengthUnit.UTF8_BYTES) value.toByteArray(Charsets.UTF_8).size else value.length",
        "    private fun JsonElement.asBigInteger(): BigInteger = canonicalInteger(asString) ?: error(\"validated integer expected\")",
        "    private companion object {",
        "        val INTEGER = Regex(\"0|-?[1-9][0-9]*\")",
        "        fun canonicalInteger(value: String): BigInteger? = value.takeIf(INTEGER::matches)?.toBigIntegerOrNull()",
        "    }",
        "}",
        "",
        "data class GeneratedCollectorProfileContract(",
        "    val sourceId: String,",
        "    val fields: Map<String, GeneratedProfileFieldContract>,",
        ") {",
        "    fun accepts(config: JsonObject): Boolean = GeneratedProfileFieldContract(",
        "        type = GeneratedProfileFieldType.OBJECT,",
        "        required = true,",
        "        unit = \"none\",",
        "        meaning = \"collector profile\",",
        "        authoringDefault = JsonObject(),",
        "        fields = fields,",
        "    ).acceptsObject(config)",
        "}",
        "",
    ]
    lines.extend(_render_typed_profile_declarations(registry))
    lines.extend(
        [
            "object GeneratedCollectorProfileContracts {",
            f'    const val REGISTRY_SHA256: String = "{digest}"',
            "    val contracts: Map<String, GeneratedCollectorProfileContract> = listOf(",
        ]
    )
    for source in registry["sources"]:
        if source["source_kind"] != "COLLECTOR":
            continue
        lines.extend(
            [
                "        GeneratedCollectorProfileContract(",
                f'            sourceId = {_kotlin_string(source["source_id"])},',
                "            fields = mapOf(",
            ]
        )
        for name, descriptor in source["configuration"]["fields"].items():
            lines.append(f'                {_kotlin_string(name)} to')
            field_lines = _render_kotlin_profile_field(descriptor, "                ")
            lines.extend(field_lines)
            lines[-1] += ","
        lines.extend(["            ),", "        ),"])
    lines.extend(
        [
            "    ).associateBy(GeneratedCollectorProfileContract::sourceId)",
            "",
            "    operator fun get(sourceId: String): GeneratedCollectorProfileContract? = contracts[sourceId]",
            "    fun requireValid(sourceId: String, config: JsonObject) {",
            "        requireNotNull(contracts[sourceId]) { \"Unknown collector source: $sourceId\" }",
            "            .also { require(it.accepts(config)) { \"Invalid profile config for $sourceId\" } }",
            "    }",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def _render_typescript(registry: Mapping[str, Any], digest: str) -> str:
    literal = json.dumps(registry, ensure_ascii=False, indent=2, sort_keys=True)
    return f'''// Generated by tools/event_source_registry.py. Do not edit.

export type SourceKind = 'COLLECTOR' | 'SYSTEM';
export type EmissionAuthority = 'RUNTIME_ONLY' | 'SOURCE_PLUGIN_ONLY';
export type FieldWireType = 'boolean' | 'enum' | 'float32' | 'float64' | 'int32' | 'int64_decimal' | 'json_string' | 'sha256_hex' | 'string' | 'uint64_decimal' | 'uuid';
export type FieldOperator = 'eq' | 'gt' | 'gte' | 'in' | 'lt' | 'lte' | 'ne';
export type TriggerScope = 'AUDIT_ONLY' | 'RESEARCHER' | 'RUNTIME_ONLY';
export type ConditionKind = 'EVENT_MATCH' | 'KEYED_PRESENCE_ENTER' | 'KEYED_PRESENCE_EXIT' | 'SEQUENCE_STEP' | 'WINDOW_COUNT' | 'WINDOW_SUM';
export type ClockBasis = 'BOOT_SESSION_MONOTONIC' | 'CONTINUOUS_MONOTONIC_SINCE_BOOT' | 'NONE' | 'SOURCE_DEFINED' | 'UTC_WALL';
export type ProfileDefault = boolean | number | string | readonly string[] | {{ readonly [key: string]: ProfileDefault }};

export interface EventIdentity {{ readonly source_id: string; readonly schema_version: number; readonly event_type: string }}
export interface AccessContract {{ readonly absence_policy: 'BLOCK_REQUIRED_STUDY' | 'SOURCE_UNAVAILABLE'; readonly kind: string; readonly mode: 'consent' | 'hardware' | 'install_permission' | 'participant_setting' | 'role' | 'runtime_permission' | 'special_access' }}
export interface ImplementationStatus {{ readonly platform: 'android' | 'ios'; readonly status: 'IMPLEMENTED' | 'PLANNED' | 'UNAVAILABLE' }}
export interface ImplementationContract {{ readonly owner_module: string; readonly statuses: readonly ImplementationStatus[] }}
export interface ProfileFieldContract {{
  readonly type: 'boolean' | 'enum' | 'enum_array' | 'integer' | 'object' | 'string';
  readonly required: boolean;
  readonly unit: string;
  readonly meaning: string;
  readonly authoring_default: ProfileDefault;
  readonly minimum?: number;
  readonly maximum?: number;
  readonly minimum_length?: number;
  readonly maximum_length?: number;
  readonly length_unit?: 'UTF16_CODE_UNITS' | 'UTF8_BYTES';
  readonly enum_values?: readonly string[];
  readonly minimum_items?: number;
  readonly maximum_items?: number;
  readonly less_than_or_equal_field?: string | null;
  readonly fields?: Readonly<Record<string, ProfileFieldContract>>;
}}
export interface RegistryFieldContract {{
  readonly clock_basis: ClockBasis;
  readonly enum_values: readonly string[];
  readonly keyed_presence_key: boolean;
  readonly length_unit: 'UTF16_CODE_UNITS' | 'UTF8_BYTES' | null;
  readonly maximum: number | null;
  readonly maximum_length: number | null;
  readonly meaning: string;
  readonly minimum: number | null;
  readonly minimum_length: number | null;
  readonly nullable: boolean;
  readonly operators: readonly FieldOperator[];
  readonly required: boolean;
  readonly unit: string;
  readonly window_sum: boolean;
  readonly wire_type: FieldWireType;
}}
export interface ClockContract {{
  readonly automation_time_inputs: readonly ('OBSERVED_RESEARCH_TIME' | 'PRIMARY_SOURCE_TIME')[];
  readonly cross_boot_policy: 'COMPARABLE' | 'NOT_APPLICABLE' | 'RESET';
  readonly observation_basis: 'RESEARCH_TIME';
  readonly primary_source_basis: ClockBasis;
  readonly primary_source_time_field: string | null;
  readonly wall_clock_change_policy: 'QUALITY_GAP' | 'RESET' | 'UNAFFECTED';
}}
export interface DeliveryContract {{
  readonly guarantee: 'BEST_EFFORT' | 'BEST_OBSERVED' | 'DETERMINISTIC_AFTER_COMMIT';
  readonly kind: 'CALLBACK' | 'POLL' | 'RUNTIME_SYNCHRONOUS' | 'SCHEDULED_WAKEUP';
  readonly latency_configuration_field: string | null;
  readonly maximum_latency_millis: number | null;
}}
export interface CompletenessContract {{
  readonly kind: 'AGGREGATED_INTERVAL' | 'BEST_EFFORT_CALLBACK' | 'COALESCED' | 'COMPLETE_FOR_RUNTIME_CAUSES' | 'PLATFORM_HISTORY_BOUNDED' | 'SAMPLED';
  readonly may_have_quality_gaps: boolean;
  readonly ordered_within_observation_batch: boolean;
  readonly quality_gap_policy: 'EMIT_AND_CONTINUE' | 'FAIL_CLOSED_FOR_DEPENDENT_AUTOMATIONS' | 'NOT_APPLICABLE';
}}
export interface PrivacyContract {{
  readonly audit_copy_policy: 'IDENTIFIERS_ONLY' | 'NO_FIELD_VALUES';
  readonly class: 'INTERNAL' | 'PUBLIC' | 'RESTRICTED' | 'SENSITIVE';
  readonly exported: boolean;
  readonly prohibited_inferences: readonly string[];
  readonly trigger_exposure: 'DECLARED_FIELDS_ONLY' | 'NONE';
}}
export interface RateBoundContract {{
  readonly enforced_by: string;
  readonly kind: 'CONFIGURATION_DERIVED' | 'HARD' | 'PLATFORM_ONLY' | 'UNBOUNDED';
  readonly maximum_events_per_batch: number | null;
  readonly maximum_events_per_hour: number | null;
  readonly maximum_events_per_period: number | null;
  readonly overflow_policy: 'COALESCE_WITH_SOURCE_TIME' | 'EMIT_QUALITY_GAP' | 'FAIL_SOURCE' | 'IMPOSSIBLE';
  readonly period_seconds: number | null;
}}
export interface PresenceContract {{ readonly group_id: string; readonly key_fields: readonly string[]; readonly role: 'ENTER' | 'EXIT' }}
export interface TriggerContract {{ readonly scope: TriggerScope; readonly condition_kinds: readonly ConditionKind[]; readonly presence: PresenceContract | null }}
export interface RegistryEventContract {{
  readonly event_type: string;
  readonly maximum_encoded_event_bytes: number;
  readonly fields: Readonly<Record<string, RegistryFieldContract>>;
  readonly clock: ClockContract;
  readonly delivery: DeliveryContract;
  readonly completeness: CompletenessContract;
  readonly privacy: PrivacyContract;
  readonly rate_bound: RateBoundContract;
  readonly trigger: TriggerContract;
}}
export interface RegistrySourceContract {{
  readonly source_id: string;
  readonly schema_version: number;
  readonly source_kind: SourceKind;
  readonly emission_authority: EmissionAuthority;
  readonly selectable: boolean;
  readonly implementation: ImplementationContract;
  readonly platforms: readonly ('android' | 'ios')[];
  readonly access: readonly AccessContract[];
  readonly configuration: {{ readonly fields: Readonly<Record<string, ProfileFieldContract>> }} | null;
  readonly disclosure_key: string | null;
  readonly events: readonly RegistryEventContract[];
}}
export interface EventSourceRegistry {{ readonly protocol_schema_version: 1; readonly registry_format: 'particeps-event-source-registry-v1'; readonly registry_version: 1; readonly sources: readonly RegistrySourceContract[] }}

export const EVENT_SOURCE_REGISTRY = {literal} as const satisfies EventSourceRegistry;
export const EVENT_SOURCE_REGISTRY_SHA256 = '{digest}' as const;
export const EVENT_SOURCE_CONTRACTS: readonly RegistrySourceContract[] = EVENT_SOURCE_REGISTRY.sources;
export const COLLECTOR_SOURCE_IDS = EVENT_SOURCE_REGISTRY.sources.filter((source) => source.source_kind === 'COLLECTOR').map((source) => source.source_id);
export const SYSTEM_SOURCE_IDS = EVENT_SOURCE_REGISTRY.sources.filter((source) => source.source_kind === 'SYSTEM').map((source) => source.source_id);
export const EVENT_IDENTITIES: readonly EventIdentity[] = EVENT_SOURCE_REGISTRY.sources.flatMap((source) => source.events.map((event) => ({{ source_id: source.source_id, schema_version: source.schema_version, event_type: event.event_type }})));

export function sourceContract(sourceId: string, schemaVersion: number): RegistrySourceContract | undefined {{
  return EVENT_SOURCE_REGISTRY.sources.find((source) => source.source_id === sourceId && source.schema_version === schemaVersion);
}}

export function eventContract(sourceId: string, schemaVersion: number, eventType: string): RegistryEventContract | undefined {{
  return sourceContract(sourceId, schemaVersion)?.events.find((event) => event.event_type === eventType);
}}
'''


def _render_python(registry: Mapping[str, Any], digest: str) -> str:
    literal = pprint.pformat(registry, width=100, sort_dicts=True)
    return f'''# Generated by tools/event_source_registry.py. Do not edit.
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Final, Literal, TypeAlias, TypedDict

SourceKind: TypeAlias = Literal["COLLECTOR", "SYSTEM"]
EmissionAuthority: TypeAlias = Literal["RUNTIME_ONLY", "SOURCE_PLUGIN_ONLY"]
FieldOperator: TypeAlias = Literal["eq", "gt", "gte", "in", "lt", "lte", "ne"]
TriggerScope: TypeAlias = Literal["AUDIT_ONLY", "RESEARCHER", "RUNTIME_ONLY"]

class EventFieldContract(TypedDict):
    clock_basis: str
    enum_values: tuple[str, ...]
    keyed_presence_key: bool
    length_unit: str | None
    maximum: int | None
    maximum_length: int | None
    meaning: str
    minimum: int | None
    minimum_length: int | None
    nullable: bool
    operators: tuple[FieldOperator, ...]
    required: bool
    unit: str
    window_sum: bool
    wire_type: str

class EventContract(TypedDict):
    event_type: str
    maximum_encoded_event_bytes: int
    fields: Mapping[str, EventFieldContract]
    clock: Mapping[str, object]
    completeness: Mapping[str, object]
    delivery: Mapping[str, object]
    privacy: Mapping[str, object]
    rate_bound: Mapping[str, object]
    trigger: Mapping[str, object]

class SourceContract(TypedDict):
    source_id: str
    schema_version: int
    source_kind: SourceKind
    emission_authority: EmissionAuthority
    selectable: bool
    access: tuple[Mapping[str, object], ...]
    configuration: Mapping[str, object] | None
    disclosure_key: str | None
    events: tuple[EventContract, ...]
    implementation: Mapping[str, object]
    platforms: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class EventIdentity:
    source_id: str
    schema_version: int
    event_type: str

def _freeze(value: object) -> object:
    if isinstance(value, dict):
        return MappingProxyType({{key: _freeze(item) for key, item in value.items()}})
    if isinstance(value, list):
        return tuple(_freeze(item) for item in value)
    return value

EVENT_SOURCE_REGISTRY_SHA256: Final = "{digest}"
EVENT_SOURCE_REGISTRY: Final[Mapping[str, object]] = _freeze({literal})  # type: ignore[assignment]
EVENT_SOURCE_CONTRACTS: Final[tuple[SourceContract, ...]] = EVENT_SOURCE_REGISTRY["sources"]  # type: ignore[assignment]
COLLECTOR_SOURCE_IDS: Final = tuple(source["source_id"] for source in EVENT_SOURCE_CONTRACTS if source["source_kind"] == "COLLECTOR")
SYSTEM_SOURCE_IDS: Final = tuple(source["source_id"] for source in EVENT_SOURCE_CONTRACTS if source["source_kind"] == "SYSTEM")
EVENT_IDENTITIES: Final = tuple(
    EventIdentity(source["source_id"], source["schema_version"], event["event_type"])
    for source in EVENT_SOURCE_CONTRACTS
    for event in source["events"]
)

def source_contract(source_id: str, schema_version: int) -> SourceContract | None:
    return next((source for source in EVENT_SOURCE_CONTRACTS if source["source_id"] == source_id and source["schema_version"] == schema_version), None)

def event_contract(source_id: str, schema_version: int, event_type: str) -> EventContract | None:
    source = source_contract(source_id, schema_version)
    if source is None:
        return None
    return next((event for event in source["events"] if event["event_type"] == event_type), None)
'''


def _render_contract_digests(registry: Mapping[str, Any], digest: str) -> bytes:
    value = {
        "contract_digest_format": "particeps-event-source-contract-digests-v1",
        "registry_sha256": digest,
        "sources": [
            {
                "schema_version": source["schema_version"],
                "sha256": _sha256(semantic_projection(source)),
                "source_id": source["source_id"],
            }
            for source in registry["sources"]
        ],
    }
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"


def _render_conformance_manifest(registry: Mapping[str, Any], digest: str) -> bytes:
    """Describe portable valid bytes and hostile mutations without copying validator logic."""
    value = {
        "corpus_format": "particeps-event-source-registry-conformance-v1",
        "invalid_cases": [
            {"expected_error": "sorted", "id": "duplicate-source", "mutation": "DUPLICATE_FIRST_SOURCE"},
            {"expected_error": "sorted", "id": "unsorted-sources", "mutation": "REVERSE_SOURCES"},
            {"expected_error": "unknown member", "id": "unknown-root-member", "mutation": "ADD_ROOT_MEMBER"},
            {"expected_error": "source_id", "id": "invalid-source-id", "mutation": "INVALID_FIRST_SOURCE_ID"},
            {"expected_error": "schema_version", "id": "invalid-schema-version", "mutation": "ZERO_FIRST_SCHEMA_VERSION"},
            {"expected_error": "system-source", "id": "selectable-system", "mutation": "MAKE_FIRST_SYSTEM_SELECTABLE"},
            {"expected_error": "configuration", "id": "collector-without-configuration", "mutation": "REMOVE_FIRST_COLLECTOR_CONFIGURATION"},
            {"expected_error": "incompatible trigger operator", "id": "invalid-field-operator", "mutation": "INVALID_FIRST_RESEARCHER_FIELD_OPERATOR"},
            {"expected_error": "primary source", "id": "invalid-primary-clock-field", "mutation": "INVALID_FIRST_PRIMARY_CLOCK_FIELD"},
            {"expected_error": "must have ENTER and EXIT", "id": "incomplete-presence-pair", "mutation": "BREAK_FIRST_PRESENCE_PAIR"},
            {"expected_error": "lifecycle audit event", "id": "researcher-lifecycle-feedback", "mutation": "MAKE_STUDY_RUNNING_RESEARCHER"},
            {"expected_error": "enforced_by", "id": "finite-rate-without-enforcement", "mutation": "REMOVE_FIRST_FINITE_RATE_ENFORCEMENT"},
            {"expected_error": "changed in place", "id": "published-contract-mutation", "mutation": "MUTATE_PUBLISHED_EVENT_SIZE"},
            {"expected_error": "deleted", "id": "published-contract-deletion", "mutation": "DELETE_PUBLISHED_SOURCE"},
        ],
        "valid_cases": [
            {
                "canonical_jcs": "current-registry.jcs",
                "id": "current-registry",
                "registry": "../../event-source-registry.json",
                "registry_sha256": digest,
                "semantic_projection_digests": "../../generated/event-source-contract-digests.json",
                "source_count": len(registry["sources"]),
            }
        ],
    }
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"


def _render_registry_docs(registry: Mapping[str, Any], digest: str) -> str:
    lines = [
        "<!-- Generated by tools/event_source_registry.py. Do not edit. -->",
        "# Protocol v1 event-source registry",
        "",
        f"Registry SHA-256: `{digest}`",
        "",
        "Event identity is the exact tuple `(source_id, schema_version, event_type)`. Event fields use canonical string wire values and generated typed decoders.",
        "",
    ]
    for source in registry["sources"]:
        lines.extend(
            [
                f"## `{source['source_id']}` schema {source['schema_version']}",
                "",
                f"Kind: `{source['source_kind']}` · authority: `{source['emission_authority']}` · owner: `{source['implementation']['owner_module']}`",
                "",
                "| Event | Scope | Delivery | Completeness | Rate | Maximum bytes |",
                "|---|---|---|---|---|---:|",
            ]
        )
        for event in source["events"]:
            bound = event["rate_bound"]
            rate_text = f"≤ {bound['maximum_events_per_period']} / {bound['period_seconds']} s" if bound["period_seconds"] is not None else bound["kind"]
            lines.append(
                f"| `{event['event_type']}` | `{event['trigger']['scope']}` | `{event['delivery']['kind']}` / `{event['delivery']['guarantee']}` | `{event['completeness']['kind']}` | {rate_text} | {event['maximum_encoded_event_bytes']} |"
            )
        for event in source["events"]:
            lines.extend(
                [
                    "",
                    f"### `{event['event_type']}` fields",
                    "",
                    "| Field | Wire type | Required | Unit | Trigger operators | Meaning |",
                    "|---|---|---:|---|---|---|",
                ]
            )
            for name, descriptor in event["fields"].items():
                operators = ", ".join(f"`{item}`" for item in descriptor["operators"]) or "—"
                meaning = descriptor["meaning"].replace("|", "\\|")
                lines.append(
                    f"| `{name}` | `{descriptor['wire_type']}` | {'yes' if descriptor['required'] else 'no'} | `{descriptor['unit']}` | {operators} | {meaning} |"
                )
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _render_platform_docs(registry: Mapping[str, Any], digest: str) -> str:
    lines = [
        "<!-- Generated by tools/event_source_registry.py. Do not edit. -->",
        "# Event-source platform capabilities",
        "",
        f"Registry SHA-256: `{digest}`",
        "",
        "| Source | Kind | Platform status | Access requirements | Disclosure key |",
        "|---|---|---|---|---|",
    ]
    for source in registry["sources"]:
        status = ", ".join(f"`{item['platform']}`: `{item['status']}`" for item in source["implementation"]["statuses"])
        access = ", ".join(f"`{item['kind']}` ({item['mode']}, {item['absence_policy']})" for item in source["access"]) or "—"
        disclosure = "—" if source["disclosure_key"] is None else f"`{source['disclosure_key']}`"
        lines.append(f"| `{source['source_id']}` | `{source['source_kind']}` | {status} | {access} | {disclosure} |")
    return "\n".join(lines) + "\n"


def render_artifacts(registry: Mapping[str, Any]) -> dict[Path, bytes]:
    """Render every checked-in Kotlin, TypeScript, Python, digest, and documentation artifact."""
    digest = _sha256(registry)
    return {
        GENERATED_PATHS["kotlin_events"]: _render_kotlin_event_registry(registry, digest).encode("utf-8"),
        GENERATED_PATHS["kotlin_profiles"]: _render_kotlin_profiles(registry, digest).encode("utf-8"),
        GENERATED_PATHS["typescript"]: _render_typescript(registry, digest).encode("utf-8"),
        GENERATED_PATHS["python"]: _render_python(registry, digest).encode("utf-8"),
        GENERATED_PATHS["python_init"]: b'"""Generated Protocol v1 event-source registry projection."""\n',
        GENERATED_PATHS["registry_digest"]: (digest + "\n").encode("ascii"),
        GENERATED_PATHS["android_registry_digest"]: (digest + "\n").encode("ascii"),
        GENERATED_PATHS["contract_digests"]: _render_contract_digests(registry, digest),
        GENERATED_PATHS["registry_docs"]: _render_registry_docs(registry, digest).encode("utf-8"),
        GENERATED_PATHS["platform_docs"]: _render_platform_docs(registry, digest).encode("utf-8"),
        GENERATED_PATHS["conformance_jcs"]: canonical_json(registry),
        GENERATED_PATHS["conformance_manifest"]: _render_conformance_manifest(registry, digest),
        GENERATED_PATHS["automation_reducer_registry_binding"]: _render_automation_reducer_registry_binding(
            registry,
            digest,
        ),
    }


def write_artifacts(artifacts: Mapping[Path, bytes]) -> None:
    """Atomically replace all generated files after every renderer has succeeded."""
    for path, data in artifacts.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
            temporary.write(data)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary_path = Path(temporary.name)
        temporary_path.replace(path)


def check_artifacts(artifacts: Mapping[Path, bytes]) -> None:
    """Fail on a missing or stale generated artifact without writing the worktree."""
    failures: list[str] = []
    for path, expected in artifacts.items():
        try:
            actual = path.read_bytes()
        except OSError:
            failures.append(f"missing generated artifact: {path.relative_to(ROOT)}")
            continue
        if actual != expected:
            failures.append(f"stale generated artifact: {path.relative_to(ROOT)}")
    if failures:
        raise RegistryError("; ".join(failures))


def _check_configured_merge_base(current: Mapping[str, Any]) -> None:
    reference = os.environ.get("PARTICEPS_REGISTRY_MERGE_BASE")
    allow_transition = os.environ.get("PARTICEPS_ALLOW_INITIAL_REGISTRY_TRANSITION") == "1"
    if reference is None:
        if os.environ.get("GITHUB_BASE_REF"):
            raise RegistryError("PARTICEPS_REGISTRY_MERGE_BASE is required for pull-request immutability checking")
        return
    try:
        raw = subprocess.run(
            ["git", "show", f"{reference}:protocol/v1/event-source-registry.json"],
            cwd=ROOT,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout
        previous = json.loads(raw, object_pairs_hook=_reject_duplicate, parse_float=_reject_non_integral, parse_constant=_reject_non_integral)
    except subprocess.CalledProcessError as error:
        if not allow_transition:
            raise RegistryError(f"merge-base registry is unavailable at {reference}") from error
        # The first registry commit has no registry object at its merge base. That one explicit
        # bootstrap boundary has nothing to compare; later commits must always find and compare
        # the registry. No retired catalog is consulted as a compatibility source.
        return
    if not isinstance(previous, dict):
        raise RegistryError("merge-base registry root is not an object")
    check_immutability(previous, current)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "generate", "check"))
    args = parser.parse_args(argv)
    try:
        registry = load_registry()
        validate_registry(registry)
        if args.command == "validate":
            print(f"valid event-source registry: {len(registry['sources'])} sources, {sum(len(source['events']) for source in registry['sources'])} events")
            return 0
        artifacts = render_artifacts(registry)
        if args.command == "generate":
            write_artifacts(artifacts)
            print(f"generated {len(artifacts)} event-source registry artifacts")
        else:
            check_artifacts(artifacts)
            _check_configured_merge_base(registry)
            print(f"event-source registry projections are current: {_sha256(registry)}")
        return 0
    except RegistryError as error:
        print(f"event-source registry error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
