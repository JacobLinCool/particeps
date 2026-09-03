"""Closed-world compiler for signed Protocol v1 automation definitions."""

from __future__ import annotations

import hashlib
from typing import Any

from .automation_model import CompiledAutomationProgram
from .configuration import validate_configuration
from .errors import ValidationError
from .jcs import canonicalize
from .registry import EventSourceRegistry


def compile_automation_program(
    configuration: dict[str, Any],
    configuration_sha256: str | None = None,
    registry: EventSourceRegistry | None = None,
) -> CompiledAutomationProgram:
    """Compile a signature-verified configuration without accepting alternate contracts."""

    source_registry = registry or EventSourceRegistry()
    normalized = validate_configuration(configuration, source_registry)
    digest = hashlib.sha256(canonicalize(normalized)).hexdigest()
    if configuration_sha256 is not None and digest != configuration_sha256:
        raise ValidationError("automation configuration digest mismatch")
    contracts = {}
    for automation in normalized["automations"]:
        for matcher in _automation_matchers(automation):
            event = matcher["event"]
            key = (
                event["source_id"],
                event["schema_version"],
                event["event_type"],
            )
            contracts[key] = source_registry.event(*key)
    occurrences = tuple(
        sorted(
            (
                automation
                for automation in normalized["automations"]
                if automation["type"] == "occurrence"
            ),
            key=lambda automation: automation["id"],
        )
    )
    bindings = tuple(
        sorted(
            (
                automation
                for automation in normalized["automations"]
                if automation["type"] == "resource_binding"
            ),
            key=lambda automation: automation["id"],
        )
    )
    return CompiledAutomationProgram(
        normalized,
        digest,
        normalized["duration_hours"] * 3600,
        occurrences,
        bindings,
        contracts,
    )


def _automation_matchers(automation: dict[str, Any]) -> list[dict[str, Any]]:
    if automation["type"] == "resource_binding":
        return [
            matcher
            for case in automation["cases"]
            for matcher in _condition_matchers(case["condition"])
        ]
    trigger = automation["trigger"]
    kind = trigger["type"]
    if kind == "event_match":
        matchers = [trigger["selector"]]
    elif kind == "sequence":
        matchers = list(trigger["steps"])
    elif kind == "window_threshold":
        matchers = [trigger["selector"]]
    elif kind == "condition_rising_edge":
        matchers = _condition_matchers(trigger["condition"])
    elif kind == "schedule":
        matchers = []
    else:
        raise ValidationError("unknown compiled trigger")
    guard = automation["guard"]
    return matchers + (_condition_matchers(guard) if guard is not None else [])


def _condition_matchers(condition: dict[str, Any]) -> list[dict[str, Any]]:
    kind = condition["type"]
    if kind in {"study_session_active", "elapsed_at_least"}:
        return []
    if kind == "event_latch":
        return list(condition["set_when"]) + list(condition["reset_when"])
    if kind == "keyed_presence":
        return list(condition["enter_when"]) + list(condition["exit_when"])
    if kind in {"held_for", "not"}:
        return _condition_matchers(condition["condition"])
    if kind == "window_threshold":
        return [condition["selector"]]
    if kind in {"all", "any"}:
        return [
            matcher
            for child in condition["conditions"]
            for matcher in _condition_matchers(child)
        ]
    raise ValidationError("unknown compiled condition")
