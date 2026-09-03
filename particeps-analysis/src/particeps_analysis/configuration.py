"""Strict semantic validator for the event-driven Protocol v1 configuration."""

from __future__ import annotations

import re
from collections.abc import Mapping
from datetime import datetime
from itertools import pairwise
from typing import Any
from urllib.parse import urlsplit

from .encoding import base64url_decode, protocol_id
from .errors import ValidationError
from .jcs import canonical_decimal, exact_object
from .registry import EventSourceRegistry

ROOT_KEYS = {
    "schema_version", "experiment_id", "configuration_id",
    "assigned_participant_id", "issued_at", "expires_at", "platform",
    "minimum_client_version", "title", "researcher", "purpose",
    "duration_hours", "consent", "collectors", "surveys", "interventions",
    "automations", "traffic_shaping", "storage", "signer", "export", "upload",
}
_ID = re.compile(r"[a-z0-9][a-z0-9-]{2,63}\Z")
_PARTICIPANT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
_BCP47 = re.compile(r"[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\Z")
_LOCAL_TIME = re.compile(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]\Z")
_PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z")
_INTEGER = re.compile(r"(?:0|-?[1-9][0-9]*)\Z")
_INSTANT = re.compile(
    r"(?P<base>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})"
    r"(?:\.(?P<fraction>[0-9]{3}(?:[0-9]{3})?(?:[0-9]{3})?))?Z\Z"
)
_CLOCKS = {"ACTIVE_RUNNING_TIME", "CALENDAR_TIME"}
_EVALUATION_CLOCKS = {"OBSERVED_RESEARCH_TIME", "PRIMARY_SOURCE_TIME"}
_OPERATORS = {"eq", "ne", "lt", "lte", "gt", "gte", "in"}


def validate_configuration(value: Any, registry: EventSourceRegistry) -> dict[str, Any]:
    root = exact_object(value, ROOT_KEYS, "configuration")
    _integer(root["schema_version"], "schema_version", 1, 1)
    protocol_id(root["experiment_id"], "experiment ID")
    protocol_id(root["configuration_id"], "configuration ID")
    participant = root["assigned_participant_id"]
    if participant is not None and (
        not isinstance(participant, str)
        or not _PARTICIPANT.fullmatch(participant)
        or len(participant.encode()) > 64
    ):
        raise ValidationError("assigned participant ID is invalid")
    issued, expires = _instant(root["issued_at"]), _instant(root["expires_at"])
    if issued >= expires:
        raise ValidationError("configuration expiry must follow issue time")
    if root["platform"] != "android":
        raise ValidationError("analysis accepts only Android configurations")
    if canonical_decimal(root["minimum_client_version"], "minimum_client_version") < 1:
        raise ValidationError("minimum client version must be positive")
    _bounded_text(root["title"], "title", 1, 120)
    researcher = exact_object(root["researcher"], {"name", "contact"}, "researcher")
    _bounded_text(researcher["name"], "researcher name", 1, 120)
    _bounded_text(researcher["contact"], "researcher contact", 3, 240)
    _bounded_text(root["purpose"], "purpose", 1, 2_000)
    duration_hours = _integer(root["duration_hours"], "duration_hours", 1, 8_760)
    duration_seconds = duration_hours * 3600
    consent = exact_object(root["consent"], {"document_version", "summary"}, "consent")
    _bounded_text(consent["document_version"], "consent version", 1, 64)
    _bounded_text(consent["summary"], "consent summary", 1, 8_000)

    collectors = _array(root["collectors"], "collectors", maximum=64)
    collector_profiles: dict[tuple[str, str], dict[str, Any]] = {}
    required_collectors: set[str] = set()
    configured_sources: list[str] = []
    for index, item in enumerate(collectors):
        collector = exact_object(item, {"id", "required", "profiles"}, f"collectors[{index}]")
        source_id = _string(collector["id"], "collector id")
        source = registry.source(source_id)
        if source["source_kind"] != "COLLECTOR" or not source["selectable"]:
            raise ValidationError(f"source is not selectable: {source_id}")
        if not isinstance(collector["required"], bool):
            raise ValidationError("collector required must be boolean")
        if collector["required"]:
            required_collectors.add(source_id)
        profiles = _array(collector["profiles"], "collector profiles", minimum=1, maximum=64)
        profile_ids: list[str] = []
        for profile_item in profiles:
            profile = exact_object(profile_item, {"id", "config"}, "collector profile")
            profile_id = _protocol_id(profile["id"], "collector profile ID")
            registry.validate_profile(source_id, profile["config"])
            profile_ids.append(profile_id)
            collector_profiles[(source_id, profile_id)] = profile["config"]
        _sorted_unique(profile_ids, "collector profile IDs")
        configured_sources.append(source_id)
    _sorted_unique(configured_sources, "collector source IDs")

    surveys = _array(root["surveys"], "surveys", maximum=128)
    survey_ids = [_validate_survey(item) for item in surveys]
    _unique(survey_ids, "survey IDs")
    interventions = _array(root["interventions"], "interventions", maximum=128)
    intervention_ids: list[str] = []
    intervention_surveys: list[str] = []
    for item in interventions:
        intervention_id, survey_id = _validate_intervention(item)
        intervention_ids.append(intervention_id)
        if survey_id is not None:
            intervention_surveys.append(survey_id)
    _sorted_unique(intervention_ids, "intervention IDs")
    if any(survey_id not in survey_ids for survey_id in intervention_surveys):
        raise ValidationError("intervention references an unknown survey")

    traffic = _validate_traffic(root["traffic_shaping"])
    resource_profiles: dict[tuple[str, str], set[str]] = {
        ("collector", source_id): {
            profile_id for candidate, profile_id in collector_profiles if candidate == source_id
        }
        for source_id in configured_sources
    }
    if traffic:
        resource_profiles[("actuator", "traffic-shaping.v1")] = {
            profile["id"] for profile in traffic["profiles"]
        }
    required_resources = {
        ("collector", source_id) for source_id in required_collectors
    }
    if traffic:
        required_resources.add(("actuator", "traffic-shaping.v1"))
    if len(resource_profiles) > 64:
        raise ValidationError("configuration declares more than 64 resources")

    automations = _array(root["automations"], "automations", maximum=128)
    if not automations and (resource_profiles or interventions):
        raise ValidationError("configured resources and interventions require automations")
    automation_ids: list[str] = []
    used_interventions: list[str] = []
    owners: dict[tuple[str, str], dict[str, Any]] = {}
    referenced_sources: set[str] = set()
    lifetime_activations = 0
    concurrent_timers = 0
    for index, automation in enumerate(automations):
        normalized, references = _validate_automation(
            automation, registry, duration_seconds, resource_profiles,
            set(intervention_ids), f"automations[{index}]",
        )
        automation_ids.append(normalized["id"])
        referenced_sources.update(references)
        if normalized["type"] == "occurrence":
            used_interventions.append(normalized["intervention_id"])
            lifetime_activations += normalized["maximum_activations"]
        else:
            key = (normalized["resource"]["kind"], normalized["resource"]["id"])
            if key in owners:
                raise ValidationError(f"resource has more than one owner: {key}")
            owners[key] = normalized
        concurrent_timers += _maximum_concurrent_timers(normalized)
    _sorted_unique(automation_ids, "automation IDs")
    if lifetime_activations > 512:
        raise ValidationError("lifetime activations exceed 512")
    if concurrent_timers > 512:
        raise ValidationError("automation state may require more than 512 timers")
    if set(used_interventions) != set(intervention_ids):
        raise ValidationError("every intervention must be referenced")
    if set(owners) != set(resource_profiles):
        raise ValidationError("every resource requires exactly one binding automation")
    for key in sorted(required_resources):
        if not _always_active(owners[key]):
            raise ValidationError(f"required resource can become inactive: {key[1]}")
    for source_id in referenced_sources:
        source = registry.source(source_id)
        if source["source_kind"] == "COLLECTOR":
            owner = owners.get(("collector", source_id))
            if source_id not in required_collectors or owner is None or not _always_active(owner):
                raise ValidationError(
                    f"automation trigger source must remain required and active: {source_id}"
                )
    _validate_resource_graph(owners, registry)

    storage = exact_object(root["storage"], {"maximum_local_bytes"}, "storage")
    _integer(storage["maximum_local_bytes"], "maximum_local_bytes", 8 << 20, 8 << 30)
    signer = exact_object(root["signer"], {"key_id", "public_key"}, "signer")
    protocol_id(signer["key_id"], "signer key ID")
    base64url_decode(signer["public_key"], 32, "signer public key")
    export = exact_object(root["export"], {"researcher_key_id", "hpke_public_key"}, "export")
    protocol_id(export["researcher_key_id"], "researcher key ID")
    base64url_decode(export["hpke_public_key"], 32, "researcher public key")
    _validate_upload(root["upload"])
    return root


def _validate_automation(
    value: Any,
    registry: EventSourceRegistry,
    duration_seconds: int,
    resources: dict[tuple[str, str], set[str]],
    interventions: set[str],
    path: str,
) -> tuple[dict[str, Any], set[str]]:
    if not isinstance(value, dict):
        raise ValidationError(f"{path} must be an object")
    kind = value.get("type")
    references: set[str] = set()
    if kind == "occurrence":
        root = exact_object(value, {
            "type", "id", "trigger", "guard", "intervention_id",
            "availability_seconds", "cooldown", "maximum_activations",
        }, path)
        _protocol_id(root["id"], "automation ID")
        if root["intervention_id"] not in interventions:
            raise ValidationError("automation references unknown intervention")
        _integer(root["availability_seconds"], "availability seconds", 1, 31_536_000)
        _integer(root["maximum_activations"], "maximum activations", 1, 512)
        if root["cooldown"] is not None:
            cooldown = exact_object(root["cooldown"], {"duration_seconds", "clock"}, "cooldown")
            _integer(cooldown["duration_seconds"], "cooldown duration", 1, 31_536_000)
            _enum(cooldown["clock"], _CLOCKS, "cooldown clock")
        condition_nodes = [0]
        references.update(
            _validate_trigger(
                root["trigger"], registry, duration_seconds, 1, condition_nodes
            )
        )
        if root["guard"] is not None:
            references.update(
                _validate_condition(
                    root["guard"], registry, duration_seconds, 1, condition_nodes
                )
            )
        return root, references
    if kind == "resource_binding":
        root = exact_object(value, {"type", "id", "resource", "cases", "default_profile_id"}, path)
        _protocol_id(root["id"], "automation ID")
        resource = exact_object(root["resource"], {"kind", "id"}, "resource")
        key = (_enum(resource["kind"], {"collector", "actuator"}, "resource kind"), _string(resource["id"], "resource id"))
        profiles = resources.get(key)
        if profiles is None:
            raise ValidationError(f"automation references unknown resource: {key}")
        cases = _array(root["cases"], "resource cases", minimum=1, maximum=16)
        counter = [0]
        for case in cases:
            case_root = exact_object(case, {"condition", "profile_id"}, "resource case")
            references.update(_validate_condition(case_root["condition"], registry, duration_seconds, 1, counter))
            _profile_reference(case_root["profile_id"], profiles)
        _profile_reference(root["default_profile_id"], profiles)
        return root, references
    raise ValidationError(f"unknown automation type: {kind}")


def _validate_trigger(
    value: Any,
    registry: EventSourceRegistry,
    duration: int,
    depth: int,
    nodes: list[int],
) -> set[str]:
    if not isinstance(value, dict):
        raise ValidationError("trigger must be an object")
    kind = value.get("type")
    if kind == "event_match":
        root = exact_object(value, {"type", "selector", "evaluation_clock"}, "event trigger")
        return _validate_matcher(
            root["selector"], registry, root["evaluation_clock"], "EVENT_MATCH"
        )
    if kind == "sequence":
        root = exact_object(value, {"type", "steps", "within_seconds", "evaluation_clock"}, "sequence trigger")
        steps = _array(root["steps"], "sequence steps", minimum=2, maximum=16)
        _integer(root["within_seconds"], "sequence window", 1, 604_800)
        references = set()
        for step in steps:
            references.update(
                _validate_matcher(
                    step, registry, root["evaluation_clock"], "SEQUENCE_STEP"
                )
            )
        _validate_retained_bound(steps, root["within_seconds"], registry)
        return references
    if kind == "window_threshold":
        root = exact_object(value, {"type", "selector", "window_seconds", "evaluation_clock", "aggregate", "comparison"}, "window trigger")
        _integer(root["window_seconds"], "window seconds", 1, 604_800)
        references = _validate_matcher(
            root["selector"],
            registry,
            root["evaluation_clock"],
            _window_condition_kind(root["aggregate"]),
        )
        _validate_aggregate(root["aggregate"], root["comparison"], root["selector"], registry)
        _validate_retained_bound([root["selector"]], root["window_seconds"], registry)
        return references
    if kind == "condition_rising_edge":
        root = exact_object(value, {"type", "condition"}, "condition trigger")
        return _validate_condition(root["condition"], registry, duration, depth, nodes)
    if kind == "schedule":
        root = exact_object(value, {"type", "schedule"}, "schedule trigger")
        _validate_schedule(root["schedule"], duration)
        return set()
    raise ValidationError(f"unknown trigger type: {kind}")


def _validate_condition(
    value: Any, registry: EventSourceRegistry, duration: int, depth: int,
    nodes: list[int],
) -> set[str]:
    if depth > 8:
        raise ValidationError("condition nesting exceeds 8")
    nodes[0] += 1
    if nodes[0] > 64:
        raise ValidationError("automation contains more than 64 condition nodes")
    if not isinstance(value, dict):
        raise ValidationError("condition must be an object")
    kind = value.get("type")
    if kind == "study_session_active":
        exact_object(value, {"type"}, "study-session condition")
        return set()
    if kind == "event_latch":
        root = exact_object(
            value, {"type", "set_when", "reset_when"}, "event_latch"
        )
        references: set[str] = set()
        for member in ("set_when", "reset_when"):
            for matcher in _array(root[member], member, minimum=1, maximum=8):
                references.update(
                    _validate_matcher(matcher, registry, None, "EVENT_MATCH")
                )
        return references
    if kind == "keyed_presence":
        root = exact_object(
            value,
            {"type", "enter_when", "exit_when", "key_field"},
            "keyed_presence",
        )
        enter_matchers = _array(
            root["enter_when"], "enter_when", minimum=1, maximum=8
        )
        exit_matchers = _array(
            root["exit_when"], "exit_when", minimum=1, maximum=8
        )
        references: set[str] = set()
        enter_schemas = []
        exit_schemas = []
        for matchers, condition_kind, schemas in (
            (enter_matchers, "KEYED_PRESENCE_ENTER", enter_schemas),
            (exit_matchers, "KEYED_PRESENCE_EXIT", exit_schemas),
        ):
            for matcher in matchers:
                references.update(
                    _validate_matcher(matcher, registry, None, condition_kind)
                )
                event = matcher["event"]
                schemas.append(
                    registry.event(
                        event["source_id"],
                        event["schema_version"],
                        event["event_type"],
                    )
                )
        field = _string(root["key_field"], "presence key field")
        schemas = enter_schemas + exit_schemas
        contracts = [schema.fields.get(field) for schema in schemas]
        if any(
            contract is None
            or not contract["required"]
            or not contract["keyed_presence_key"]
            for contract in contracts
        ):
            raise ValidationError(
                "presence key is not authorized by every event contract"
            )
        if len(
            {
                contract["wire_type"]
                for contract in contracts
                if contract is not None
            }
        ) != 1:
            raise ValidationError("presence key must have one wire type")
        _validate_presence_contracts(field, enter_schemas, exit_schemas)
        return references
    if kind == "held_for":
        root = exact_object(value, {"type", "condition", "duration_seconds", "clock"}, "held-for condition")
        _integer(root["duration_seconds"], "held duration", 1, duration)
        _enum(root["clock"], _CLOCKS, "held clock")
        return _validate_condition(root["condition"], registry, duration, depth + 1, nodes)
    if kind == "elapsed_at_least":
        root = exact_object(value, {"type", "duration_seconds", "clock"}, "elapsed condition")
        _integer(root["duration_seconds"], "elapsed duration", 1, duration)
        _enum(root["clock"], _CLOCKS, "elapsed clock")
        return set()
    if kind == "window_threshold":
        root = exact_object(value, {"type", "selector", "window_seconds", "evaluation_clock", "aggregate", "comparison"}, "window condition")
        _integer(root["window_seconds"], "window seconds", 1, 604_800)
        references = _validate_matcher(
            root["selector"],
            registry,
            root["evaluation_clock"],
            _window_condition_kind(root["aggregate"]),
        )
        _validate_aggregate(root["aggregate"], root["comparison"], root["selector"], registry)
        _validate_retained_bound([root["selector"]], root["window_seconds"], registry)
        return references
    if kind in {"all", "any"}:
        root = exact_object(value, {"type", "conditions"}, f"{kind} condition")
        children = _array(root["conditions"], "condition group", minimum=2, maximum=8)
        references: set[str] = set()
        for child in children:
            references.update(_validate_condition(child, registry, duration, depth + 1, nodes))
        return references
    if kind == "not":
        root = exact_object(value, {"type", "condition"}, "not condition")
        return _validate_condition(root["condition"], registry, duration, depth + 1, nodes)
    raise ValidationError(f"unknown condition type: {kind}")


def _validate_matcher(
    value: Any,
    registry: EventSourceRegistry,
    evaluation_clock: Any,
    condition_kind: str,
) -> set[str]:
    root = exact_object(value, {"event", "predicates"}, "event matcher")
    event = exact_object(root["event"], {"source_id", "schema_version", "event_type"}, "event identity")
    source_id = _string(event["source_id"], "event source ID")
    schema_version = _integer(event["schema_version"], "event schema version", 1, 2**31 - 1)
    event_type = _string(event["event_type"], "event type")
    schema = registry.event(source_id, schema_version, event_type)
    if schema.trigger["scope"] != "RESEARCHER":
        raise ValidationError("runtime/audit event cannot trigger research automation")
    if condition_kind not in schema.trigger["condition_kinds"]:
        raise ValidationError("event does not support selected condition kind")
    if evaluation_clock is not None:
        clock = _enum(evaluation_clock, _EVALUATION_CLOCKS, "evaluation clock")
        if clock not in schema.clock["automation_time_inputs"]:
            raise ValidationError("event does not support selected evaluation clock")
    predicates = _array(root["predicates"], "predicates", maximum=16)
    names: list[str] = []
    for predicate_value in predicates:
        if not isinstance(predicate_value, dict):
            raise ValidationError("predicate must be an object")
        operator = _enum(predicate_value.get("operator"), _OPERATORS, "operator")
        expected = {"field", "operator", "values"} if operator == "in" else {"field", "operator", "value"}
        predicate = exact_object(predicate_value, expected, "predicate")
        field_name = _string(predicate["field"], "predicate field")
        field = schema.fields.get(field_name)
        if field is None or operator not in field["operators"]:
            raise ValidationError("unknown field or unsupported operator")
        names.append(field_name)
        candidates = predicate["values"] if operator == "in" else [predicate["value"]]
        if operator == "in" and (
            not isinstance(candidates, list) or not 1 <= len(candidates) <= 64
            or any(not isinstance(candidate, str) for candidate in candidates)
            or candidates != sorted(set(candidates), key=_utf16_sort_key)
        ):
            raise ValidationError("in values must be 1-64 sorted unique strings")
        for candidate in candidates:
            registry.decode_predicate_literal(
                field_name,
                candidate,
                field,
                schema.maximum_encoded_event_bytes,
            )
    if len(names) != len(set(names)):
        raise ValidationError("a matcher may compare a field once")
    return {source_id}


def _utf16_sort_key(value: str) -> bytes:
    """Match Kotlin/JavaScript lexicographic order over UTF-16 code units."""

    return value.encode("utf-16-be", errors="surrogatepass")


def _window_condition_kind(value: Any) -> str:
    if not isinstance(value, dict):
        raise ValidationError("window aggregate must be an object")
    if value.get("type") == "count":
        return "WINDOW_COUNT"
    if value.get("type") == "sum":
        return "WINDOW_SUM"
    raise ValidationError("unknown window aggregate")


def _validate_presence_contracts(
    key_field: str,
    enter_schemas: list[Any],
    exit_schemas: list[Any],
) -> None:
    groups: set[str] = set()
    for schemas, expected_role in (
        (enter_schemas, "ENTER"),
        (exit_schemas, "EXIT"),
    ):
        for schema in schemas:
            presence = schema.trigger["presence"]
            if (
                not isinstance(presence, Mapping)
                or presence["role"] != expected_role
                or tuple(presence["key_fields"]) != (key_field,)
            ):
                raise ValidationError(
                    "presence matcher role or key fields do not match registry"
                )
            groups.add(str(presence["group_id"]))
    if len(groups) != 1:
        raise ValidationError("presence matchers must use one registry group")


def _validate_aggregate(value: Any, comparison_value: Any, matcher: Any, registry: EventSourceRegistry) -> None:
    aggregate = value if isinstance(value, dict) else {}
    kind = aggregate.get("type")
    if kind == "count":
        exact_object(aggregate, {"type"}, "count aggregate")
    elif kind == "sum":
        root = exact_object(aggregate, {"type", "field"}, "sum aggregate")
        event = matcher["event"]
        schema = registry.event(event["source_id"], event["schema_version"], event["event_type"])
        field = schema.fields.get(_string(root["field"], "sum field"))
        if field is None or not field["required"] or not field["window_sum"] or field["wire_type"] not in {"int32", "uint64_decimal"}:
            raise ValidationError("sum requires a registry-authorized required integer field")
    else:
        raise ValidationError("unknown window aggregate")
    comparison = exact_object(comparison_value, {"operator", "value"}, "numeric comparison")
    _enum(comparison["operator"], _OPERATORS - {"in"}, "numeric operator")
    threshold = _string(comparison["value"], "numeric threshold")
    if not _INTEGER.fullmatch(threshold):
        raise ValidationError("numeric threshold must be a canonical integer")


def _validate_retained_bound(
    matchers: list[dict[str, Any]], window_seconds: int,
    registry: EventSourceRegistry,
) -> None:
    retained = 0
    for matcher in matchers:
        event = matcher["event"]
        schema = registry.event(
            event["source_id"], event["schema_version"], event["event_type"]
        )
        source = registry.source(schema.source_id, schema.schema_version)
        contract = next(
            item for item in source["events"] if item["event_type"] == schema.event_type
        )
        bound = contract["rate_bound"]
        events_per_period = bound["maximum_events_per_period"]
        period_seconds = bound["period_seconds"]
        if (
            bound["kind"] in {"UNBOUNDED", "PLATFORM_ONLY"}
            or events_per_period is None
            or period_seconds is None
        ):
            raise ValidationError("sequence/window source has no enforced finite rate bound")
        retained += int(events_per_period) * (
            (window_seconds + int(period_seconds) - 1) // int(period_seconds)
        )
    if retained > 4096:
        raise ValidationError("sequence/window retained entry bound exceeds 4,096")


def _validate_schedule(value: Any, duration: int) -> None:
    if not isinstance(value, dict):
        raise ValidationError("schedule must be an object")
    kind = value.get("type")
    if kind == "one_time":
        root = exact_object(value, {"type", "offset_minutes", "clock"}, "one-time schedule")
        _integer(root["offset_minutes"], "offset", 0, (duration - 1) // 60)
        _enum(root["clock"], _CLOCKS, "schedule clock")
    elif kind == "interval":
        root = exact_object(value, {"type", "start_offset_minutes", "interval_minutes", "clock"}, "interval schedule")
        _integer(root["start_offset_minutes"], "interval start", 0, (duration - 1) // 60)
        _integer(root["interval_minutes"], "interval", 1, 525_600)
        _enum(root["clock"], _CLOCKS, "schedule clock")
    elif kind == "daily_local":
        root = exact_object(value, {"type", "local_time"}, "daily schedule")
        _local_minute(root["local_time"])
    elif kind == "random_window":
        root = exact_object(value, {"type", "local_windows", "occurrences_per_window", "maximum_occurrences_per_day", "maximum_occurrences_total", "minimum_separation_minutes"}, "random schedule")
        windows = _array(root["local_windows"], "local windows", minimum=1, maximum=8)
        parsed = []
        for window in windows:
            item = exact_object(window, {"start_local_time", "end_local_time"}, "local window")
            start, end = _local_minute(item["start_local_time"]), _local_minute(item["end_local_time"])
            if start >= end:
                raise ValidationError("random windows cannot be empty or overnight")
            parsed.append((start, end))
        if parsed != sorted(parsed) or any(left[1] > right[0] for left, right in pairwise(parsed)):
            raise ValidationError("random windows must be sorted and non-overlapping")
        per_window = _integer(root["occurrences_per_window"], "occurrences per window", 1, 8)
        daily = _integer(root["maximum_occurrences_per_day"], "daily cap", 1, 64)
        _integer(root["maximum_occurrences_total"], "total cap", 1, 512)
        separation = _integer(root["minimum_separation_minutes"], "minimum separation", 1, 1440)
        if daily > len(parsed) * per_window:
            raise ValidationError("daily cap exceeds signed slots")
        if any(end - start < 1 + (per_window - 1) * separation for start, end in parsed):
            raise ValidationError("random window cannot preserve minimum separation")
        cyclic = [*parsed, (parsed[0][0] + 1_440, parsed[0][1] + 1_440)]
        if any(right[0] - (left[1] - 1) < separation for left, right in pairwise(cyclic)):
            raise ValidationError("adjacent random windows violate minimum separation")
    else:
        raise ValidationError(f"unknown automation schedule: {kind}")


def _validate_traffic(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        raise ValidationError("traffic_shaping must be an object")
    if not value:
        return None
    root = exact_object(value, {"target_packages", "profiles"}, "traffic_shaping")
    packages = _array(root["target_packages"], "target packages", minimum=1, maximum=64)
    if any(not isinstance(item, str) or not _PACKAGE.fullmatch(item) for item in packages):
        raise ValidationError("invalid Android application ID")
    _sorted_unique(packages, "target packages")
    if "cool.jacoblin.particeps" in packages:
        raise ValidationError("Particeps cannot be a shaping target")
    profiles = _array(root["profiles"], "traffic profiles", minimum=1, maximum=64)
    ids = []
    for item in profiles:
        profile = exact_object(item, {"id", "uplink_kbps", "downlink_kbps"}, "traffic profile")
        ids.append(_protocol_id(profile["id"], "traffic profile ID"))
        for direction in ("uplink_kbps", "downlink_kbps"):
            if profile[direction] is not None:
                _integer(profile[direction], direction, 1, 1_000_000)
    _sorted_unique(ids, "traffic profile IDs")
    return root


def _validate_survey(value: Any) -> str:
    root = exact_object(value, {"id", "title", "description", "questions"}, "survey")
    survey_id = _protocol_id(root["id"], "survey ID")
    _localized(root["title"])
    _localized(root["description"])
    questions = _array(root["questions"], "survey questions", minimum=1, maximum=100)
    ids = []
    for item in questions:
        if not isinstance(item, dict):
            raise ValidationError("question must be an object")
        kind = item.get("type")
        common = {"type", "id", "prompt", "required"}
        extra = {
            "short_text": {"maximum_length"},
            "scale": {"minimum", "maximum", "minimum_label", "maximum_label"},
            "single_choice": {"options"},
            "multiple_choice": {"options", "minimum_selections", "maximum_selections"},
        }.get(kind)
        if extra is None:
            raise ValidationError("unknown survey question type")
        question = exact_object(item, common | extra, "question")
        ids.append(_protocol_id(question["id"], "question ID"))
        _localized(question["prompt"])
        if not isinstance(question["required"], bool):
            raise ValidationError("question required must be boolean")
        if kind == "short_text":
            _integer(question["maximum_length"], "text limit", 1, 4000)
        elif kind == "scale":
            minimum = _integer(question["minimum"], "scale minimum", -1000, 1000)
            maximum = _integer(question["maximum"], "scale maximum", -1000, 1000)
            if minimum >= maximum:
                raise ValidationError("invalid scale bounds")
            _localized(question["minimum_label"])
            _localized(question["maximum_label"])
        else:
            options = _array(question["options"], "choice options", minimum=2, maximum=50)
            option_ids = []
            for option_value in options:
                option = exact_object(option_value, {"id", "label"}, "choice option")
                option_ids.append(_protocol_id(option["id"], "choice ID"))
                _localized(option["label"])
            _unique(option_ids, "choice IDs")
            if kind == "multiple_choice":
                lower = _integer(question["minimum_selections"], "minimum selections", 0, len(options))
                _integer(question["maximum_selections"], "maximum selections", max(1, lower), len(options))
                if question["required"] and lower == 0:
                    raise ValidationError("required choice needs a selection")
    _unique(ids, "question IDs")
    return survey_id


def _validate_intervention(value: Any) -> tuple[str, str | None]:
    root = exact_object(value, {"id", "required", "action"}, "intervention")
    intervention_id = _protocol_id(root["id"], "intervention ID")
    if not isinstance(root["required"], bool):
        raise ValidationError("intervention required must be boolean")
    if not isinstance(root["action"], dict):
        raise ValidationError("action must be an object")
    kind = root["action"].get("type")
    keys = {"type", "notification_title", "notification_message"}
    if kind == "survey":
        keys.add("survey_id")
    elif kind != "notification":
        raise ValidationError("unknown intervention action")
    action = exact_object(root["action"], keys, "action")
    _bounded_text(action["notification_title"], "notification title", 1, 120)
    _bounded_text(action["notification_message"], "notification message", 1, 500)
    return intervention_id, (_protocol_id(action["survey_id"], "survey ID") if kind == "survey" else None)


def _validate_upload(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValidationError("upload must be an object")
    if not value:
        return
    root = exact_object(value, {"endpoint", "interval_minutes", "allow_metered"}, "upload")
    endpoint = _bounded_text(root["endpoint"], "upload endpoint", 8, 2048)
    parsed = urlsplit(endpoint)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.fragment:
        raise ValidationError("upload endpoint must be an HTTPS origin/path")
    _integer(root["interval_minutes"], "upload interval", 1, 10080)
    if not isinstance(root["allow_metered"], bool):
        raise ValidationError("allow_metered must be boolean")


def _validate_resource_graph(owners: dict[tuple[str, str], dict[str, Any]], registry: EventSourceRegistry) -> None:
    graph = {key: set() for key in owners}
    for key, owner in owners.items():
        for case in owner["cases"]:
            for source_id in _condition_source_ids(case["condition"]):
                if registry.source(source_id)["source_kind"] == "COLLECTOR" and ("collector", source_id) in owners:
                    graph[key].add(("collector", source_id))
    visiting: set[tuple[str, str]] = set()
    visited: set[tuple[str, str]] = set()

    def visit(node: tuple[str, str]) -> None:
        if node in visiting:
            raise ValidationError(f"resource dependency cycle includes {node[1]}")
        if node in visited:
            return
        visiting.add(node)
        for child in sorted(graph[node]):
            visit(child)
        visiting.remove(node)
        visited.add(node)

    for node in sorted(graph):
        visit(node)


def _condition_source_ids(condition: dict[str, Any]) -> set[str]:
    kind = condition["type"]
    matchers = []
    if kind == "event_latch":
        matchers = condition["set_when"] + condition["reset_when"]
    elif kind == "keyed_presence":
        matchers = condition["enter_when"] + condition["exit_when"]
    elif kind == "window_threshold":
        matchers = [condition["selector"]]
    elif kind in {"held_for", "not"}:
        return _condition_source_ids(condition["condition"])
    elif kind in {"all", "any"}:
        return set().union(*(_condition_source_ids(item) for item in condition["conditions"]))
    return {item["event"]["source_id"] for item in matchers}


def _maximum_concurrent_timers(automation: dict[str, Any]) -> int:
    if automation["type"] == "occurrence":
        return _trigger_timer_count(automation["trigger"]) + (
            _condition_timer_count(automation["guard"])
            if automation["guard"] is not None
            else 0
        )
    return sum(
        _condition_timer_count(case["condition"]) for case in automation["cases"]
    )


def _trigger_timer_count(trigger: dict[str, Any]) -> int:
    if trigger["type"] in {"schedule", "window_threshold"}:
        return 1
    if trigger["type"] == "condition_rising_edge":
        return _condition_timer_count(trigger["condition"])
    return 0


def _condition_timer_count(condition: dict[str, Any]) -> int:
    kind = condition["type"]
    if kind in {"study_session_active", "event_latch", "keyed_presence"}:
        return 0
    if kind in {"elapsed_at_least", "window_threshold"}:
        return 1
    if kind == "held_for":
        return 1 + _condition_timer_count(condition["condition"])
    if kind in {"all", "any"}:
        return sum(_condition_timer_count(item) for item in condition["conditions"])
    if kind == "not":
        return _condition_timer_count(condition["condition"])
    raise ValidationError(f"unknown condition type: {kind}")


def _always_active(binding: dict[str, Any]) -> bool:
    for case in binding["cases"]:
        if case["profile_id"] is None:
            return False
        if case["condition"]["type"] == "study_session_active":
            return True
    return binding["default_profile_id"] is not None


def _profile_reference(value: Any, profiles: set[str]) -> None:
    if value is not None and (not isinstance(value, str) or value not in profiles):
        raise ValidationError("automation references unknown resource profile")


def _localized(value: Any) -> None:
    root = exact_object(value, {"default", "translations"}, "localized text")
    _bounded_text(root["default"], "localized default", 1, 2000)
    if not isinstance(root["translations"], dict) or len(root["translations"]) > 32:
        raise ValidationError("invalid translations")
    lowered = []
    for tag, text in root["translations"].items():
        if not _BCP47.fullmatch(tag):
            raise ValidationError("invalid language tag")
        lowered.append(tag.lower())
        _bounded_text(text, "localized translation", 1, 2000)
    _unique(lowered, "language tags")


def _instant(value: Any) -> tuple[str, int]:
    if not isinstance(value, str) or (match := _INSTANT.fullmatch(value)) is None:
        raise ValidationError("instant must use canonical UTC encoding")
    try:
        # The calendar prefix is validated independently; retaining all nine fractional
        # digits avoids collapsing two signed instants that differ below microseconds.
        datetime.fromisoformat(match["base"])
    except ValueError as error:
        raise ValidationError("invalid instant") from error
    return match["base"], int((match["fraction"] or "").ljust(9, "0"))


def _local_minute(value: Any) -> int:
    if not isinstance(value, str) or not _LOCAL_TIME.fullmatch(value):
        raise ValidationError("local time must be HH:mm")
    return int(value[:2]) * 60 + int(value[3:])


def _array(value: Any, name: str, *, minimum: int = 0, maximum: int = 2**31 - 1) -> list[Any]:
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise ValidationError(f"{name} has invalid length")
    return value


def _integer(value: Any, name: str, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValidationError(f"{name} must be an integer in {minimum}..{maximum}")
    return value


def _string(value: Any, name: str) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{name} must be a string")
    return value


def _enum(value: Any, allowed: set[str], name: str) -> str:
    value = _string(value, name)
    if value not in allowed:
        raise ValidationError(f"{name} is invalid")
    return value


def _protocol_id(value: Any, name: str) -> str:
    value = _string(value, name)
    if not _ID.fullmatch(value):
        raise ValidationError(f"{name} is invalid")
    return value


def _bounded_text(value: Any, name: str, minimum: int, maximum: int) -> str:
    value = _string(value, name)
    # Protocol v1 text bounds use JVM/ECMAScript String.length semantics so all
    # validators count astral characters as two UTF-16 code units.
    length = len(value.encode("utf-16-le", "surrogatepass")) // 2
    if not minimum <= length <= maximum:
        raise ValidationError(f"{name} length is invalid")
    return value


def _unique(values: list[str], name: str) -> None:
    if len(values) != len(set(values)):
        raise ValidationError(f"duplicate {name}")


def _sorted_unique(values: list[str], name: str) -> None:
    if values != sorted(set(values)):
        raise ValidationError(f"{name} must be sorted and unique")
