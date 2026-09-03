from __future__ import annotations

import copy
import dataclasses
import json
import unittest
from pathlib import Path
from typing import Any

from particeps_analysis.automation import (
    AutomationCheckpoint,
    AutomationEvent,
    CompiledAutomationProgram,
    DurableTimer,
    ReducerClock,
    ReducerInput,
    ResearchTime,
    ResourceKey,
    TimerTarget,
    automation_checkpoint_digest,
    compile_automation_program,
    decode_automation_checkpoint,
    encode_automation_checkpoint,
    reduce_automation_batch,
)
from particeps_analysis.errors import ValidationError
from particeps_analysis.registry import EventSourceRegistry

CORPUS = Path(__file__).parents[2] / "protocol" / "v1" / "automation-reducer-vectors.json"


class AutomationReducerConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.corpus = json.loads(CORPUS.read_text())

    def test_every_input_matches_authoritative_checkpoint_and_outputs(self) -> None:
        self.assertEqual("particeps-automation-reducer-v1", self.corpus["format"])
        self.assertEqual(EventSourceRegistry.digest, self.corpus["registry_sha256"])
        self.assertEqual(
            "Each scenario step is one indivisible SourceObservation or EngineCommit reducer batch.",
            self.corpus["batch_semantics"]["step_boundary"],
        )
        for scenario in self.corpus["scenarios"]:
            with self.subTest(scenario=scenario["id"]):
                program = compile_automation_program(
                    scenario["configuration"], scenario["configuration_sha256"]
                )
                checkpoint = AutomationCheckpoint()
                parsed_inputs = []
                for vector in scenario["steps"]:
                    input_value = _input(vector["input"])
                    parsed_inputs.append(input_value)
                    result = reduce_automation_batch(program, checkpoint, [input_value])
                    checkpoint = result.checkpoint
                    self.assertEqual(vector["expected"], _expected(result))
                    self.assertEqual(
                        checkpoint,
                        decode_automation_checkpoint(
                            vector["expected"]["checkpoint"]
                        ),
                    )
                self.assertEqual(
                    scenario["final_checkpoint_sha256"],
                    automation_checkpoint_digest(checkpoint),
                )
                for partition_range in scenario["stream_partition_ranges"]:
                    first = int(partition_range["first_step"]) - 1
                    last = int(partition_range["last_step"])
                    boundary = AutomationCheckpoint()
                    for prefix_input in parsed_inputs[:first]:
                        boundary = reduce_automation_batch(
                            program, boundary, [prefix_input]
                        ).checkpoint
                    expected_partition = boundary
                    for atomic_input in parsed_inputs[first:last]:
                        expected_partition = reduce_automation_batch(
                            program, expected_partition, [atomic_input]
                        ).checkpoint
                    for split in range(first + 1, last):
                        actual_partition = boundary
                        for transport_chunk in (
                            parsed_inputs[first:split], parsed_inputs[split:last]
                        ):
                            for atomic_input in transport_chunk:
                                actual_partition = reduce_automation_batch(
                                    program, actual_partition, [atomic_input]
                                ).checkpoint
                        self.assertEqual(
                            automation_checkpoint_digest(expected_partition),
                            automation_checkpoint_digest(actual_partition),
                            f"{scenario['id']} transport partition after atomic step {split}",
                        )

    def test_shared_compiler_hostiles_are_rejected(self) -> None:
        scenarios = {item["id"]: item for item in self.corpus["scenarios"]}
        seen: set[str] = set()
        for vector in self.corpus["compiler_hostile_cases"]:
            with self.subTest(vector=vector["id"]):
                seen.add(vector["id"])
                configuration, registry = _hostile_configuration(
                    scenarios[vector["base_scenario_id"]]["configuration"],
                    vector["mutation"],
                )
                with self.assertRaises(ValidationError):
                    compile_automation_program(configuration, registry=registry)
        self.assertEqual(
            {
                "combined-trigger-guard-condition-node-overflow",
                "global-concurrent-timer-overflow",
                "lifecycle-audit-event-match",
                "presence-condition-kind-mismatch",
                "presence-group-mismatch",
                "presence-key-mismatch",
                "presence-role-inversion",
                "random-window-daily-capacity-overflow",
                "random-window-adjacent-separation-violation",
                "random-window-cyclic-separation-violation",
                "utf16-astral-title-overflow",
                "sixty-five-stateful-resources",
            },
            seen,
        )

    def test_shared_timer_materialization_hostiles_are_rejected(self) -> None:
        scenarios = {item["id"]: item for item in self.corpus["scenarios"]}
        for vector in self.corpus["reducer_hostile_cases"]:
            with self.subTest(vector=vector["id"]):
                scenario = scenarios[vector["scenario_id"]]
                program = compile_automation_program(
                    scenario["configuration"], scenario["configuration_sha256"]
                )
                step_index = int(vector["step"]) - 1
                checkpoint = AutomationCheckpoint()
                for step in scenario["steps"][:step_index]:
                    checkpoint = reduce_automation_batch(
                        program, checkpoint, [_input(step["input"])]
                    ).checkpoint
                hostile = _mutate_reducer_input(
                    _input(scenario["steps"][step_index]["input"]),
                    vector["mutation"],
                )
                with self.assertRaises(ValidationError):
                    reduce_automation_batch(program, checkpoint, [hostile])

    def test_clock_discontinuity_materializes_a_durable_zone_change(self) -> None:
        scenario = next(
            item for item in self.corpus["scenarios"]
            if item["id"] == "conditions-resources-and-resets"
        )
        discontinuity = next(
            index for index, step in enumerate(scenario["steps"])
            if step["input"]["type"] == "CLOCK_DISCONTINUITY"
        )
        self.assertNotEqual(
            scenario["steps"][discontinuity - 1]["input"]["clock"]["zone_id"],
            scenario["steps"][discontinuity]["input"]["clock"]["zone_id"],
        )
        self.assertEqual(
            scenario["steps"][discontinuity]["input"]["clock"]["zone_id"],
            scenario["steps"][discontinuity + 1]["input"]["clock"]["zone_id"],
        )

    def test_delayed_enter_exit_batch_preserves_applied_generation(self) -> None:
        self.assertEqual(1, len(self.corpus["atomic_batch_cases"]))
        vector = self.corpus["atomic_batch_cases"][0]
        self.assertEqual(
            "UNCHANGED", vector["expected_desired_resource_relation"]
        )
        scenario = next(
            item for item in self.corpus["scenarios"]
            if item["id"] == vector["scenario_id"]
        )
        program = compile_automation_program(
            scenario["configuration"], scenario["configuration_sha256"]
        )
        checkpoint = AutomationCheckpoint()
        for step in scenario["steps"][: int(vector["base_checkpoint_after_step"])]:
            checkpoint = reduce_automation_batch(
                program, checkpoint, [_input(step["input"])]
            ).checkpoint
        batch = []
        for recipe in vector["inputs"]:
            source = _input(scenario["steps"][int(recipe["source_step"]) - 1]["input"])
            if source.type != "EVENT" or source.event is None:
                self.fail("atomic batch recipes must reference event inputs")
            sequence = int(recipe["sequence_number"])
            batch.append(dataclasses.replace(
                source,
                sequence_number=sequence,
                event=dataclasses.replace(source.event, sequence_number=sequence),
            ))
        key = ResourceKey(vector["resource"]["kind"], vector["resource"]["id"])
        before = checkpoint.desired_resources[key]
        result = reduce_automation_batch(program, checkpoint, batch)
        self.assertEqual(vector["expected_resource_changes"], list(result.resource_changes.values()))
        self.assertEqual(before, result.checkpoint.desired_resources[key])

    def test_shared_active_time_cooldown_property(self) -> None:
        self.assertEqual(1, len(self.corpus["reducer_property_cases"]))
        vector = self.corpus["reducer_property_cases"][0]
        self.assertEqual(
            "SET_OCC_EVENT_MAXIMUM_ACTIVATIONS_2", vector["mutation"]
        )
        scenario = next(
            item for item in self.corpus["scenarios"]
            if item["id"] == vector["scenario_id"]
        )
        configuration = copy.deepcopy(scenario["configuration"])
        automation = next(
            item for item in configuration["automations"]
            if item["id"] == vector["expected_automation_id"]
        )
        automation["maximum_activations"] = 2
        program = compile_automation_program(configuration)
        checkpoint = AutomationCheckpoint()
        action_count = -1
        suppression = None
        for index, step in enumerate(
            scenario["steps"][: int(vector["expected_suppression_step"])], start=1
        ):
            result = reduce_automation_batch(
                program, checkpoint, [_input(step["input"])]
            )
            checkpoint = result.checkpoint
            if index == int(vector["expected_action_step"]):
                action_count = len(result.action_requests)
            if index == int(vector["expected_suppression_step"]):
                suppression = next(
                    audit.suppression_reason for audit in result.audits
                    if audit.automation_id == vector["expected_automation_id"]
                )
        self.assertEqual(vector["expected_action_count_at_step"], action_count)
        self.assertEqual(vector["expected_suppression_reason"], suppression)

    def test_checkpoint_wire_is_canonical(self) -> None:
        for scenario in self.corpus["scenarios"]:
            for vector in scenario["steps"]:
                encoded = vector["expected"]["checkpoint"]
                self.assertEqual(
                    encoded,
                    encode_automation_checkpoint(decode_automation_checkpoint(encoded)),
                )

    def test_protocol_decimal_float_events_match_canonical_in_values_numerically(self) -> None:
        registry = EventSourceRegistry()
        identity = ("accelerometer.v1", 1, "ACCELEROMETER_SAMPLE")
        automation = {
            "availability_seconds": 300,
            "cooldown": None,
            "guard": None,
            "id": "float-trigger",
            "intervention_id": "notify-one",
            "maximum_activations": 10,
            "trigger": {
                "evaluation_clock": "OBSERVED_RESEARCH_TIME",
                "selector": {
                    "event": {
                        "source_id": identity[0],
                        "schema_version": identity[1],
                        "event_type": identity[2],
                    },
                    "predicates": [{
                        "field": "x_meters_per_second_squared",
                        "operator": "in",
                        "values": ["0.001", "0.5", "1.0", "1000.0"],
                    }],
                },
                "type": "event_match",
            },
            "type": "occurrence",
        }
        program = CompiledAutomationProgram(
            {},
            "0" * 64,
            3_600,
            (automation,),
            (),
            {identity: registry.event(*identity)},
        )
        zero = ResearchTime(0, 0, "boot-float")
        checkpoint = reduce_automation_batch(
            program,
            AutomationCheckpoint(),
            [
                ReducerInput(
                    "LIFECYCLE", 1, ReducerClock(zero, 0, 0, "UTC"),
                    state="ACTIVATING",
                ),
                ReducerInput(
                    "LIFECYCLE", 2, ReducerClock(zero, 0, 0, "UTC"),
                    state="RUNNING",
                ),
            ],
        ).checkpoint
        inputs = []
        for index, wire in enumerate(
            ("+1", "01", ".5", "1.", "1e-3", "1E+3"), start=3
        ):
            now = ResearchTime(index, index * 1_000_000, "boot-float")
            inputs.append(
                ReducerInput(
                    "EVENT",
                    index,
                    ReducerClock(now, index * 1_000_000, index * 1_000_000, "UTC"),
                    event=AutomationEvent(
                        index,
                        identity[0],
                        identity[1],
                        identity[2],
                        now,
                        None,
                        {"x_meters_per_second_squared": wire},
                    ),
                )
            )
        result = reduce_automation_batch(program, checkpoint, inputs)
        self.assertEqual(6, len(result.action_requests))
        self.assertEqual(6, result.checkpoint.activation_counts["float-trigger"])


def _input(value: dict[str, Any]) -> ReducerInput:
    event = value.get("event")
    return ReducerInput(
        value["type"],
        value["sequence_number"],
        _clock(value["clock"]),
        event=(
            AutomationEvent(
                event["sequence_number"],
                event["source_id"],
                event["schema_version"],
                event["event_type"],
                _time(event["observed_time"]),
                _time(event["primary_source_time"])
                if event["primary_source_time"] is not None
                else None,
                event["fields"],
            )
            if event is not None
            else None
        ),
        state=value.get("state"),
        timer_id=value.get("timer_id"),
        automation_id=value.get("automation_id"),
        generation=int(value["generation"]) if "generation" in value else None,
        causal_sequence=value.get("causal_sequence"),
        target=_target(value["target"]) if "target" in value else None,
        logical_due=_time(value["logical_due"]) if "logical_due" in value else None,
        timer=_timer(value["timer"]) if "timer" in value else None,
        source_id=value.get("source_id"),
        restart_resources=tuple(
            ResourceKey(item["kind"], item["id"])
            for item in value.get("restart_resources", ())
        ),
    )


def _time(value: dict[str, Any]) -> ResearchTime:
    return ResearchTime(
        value["wall_time_utc_millis"],
        int(value["elapsed_realtime_nanos"]),
        value["boot_session_id"],
    )


def _clock(value: dict[str, Any]) -> ReducerClock:
    return ReducerClock(
        _time(value["now"]),
        int(value["active_elapsed_nanos"]),
        int(value["calendar_elapsed_nanos"]),
        value["zone_id"],
    )


def _target(value: dict[str, Any]) -> TimerTarget:
    return TimerTarget(
        value["type"],
        utc_millis=value.get("utc_millis"),
        elapsed_nanos=(
            int(value["elapsed_nanos"]) if "elapsed_nanos" in value else None
        ),
        boot_session_id=value.get("boot_session_id"),
        elapsed_realtime_nanos=(
            int(value["elapsed_realtime_nanos"])
            if "elapsed_realtime_nanos" in value
            else None
        ),
    )


def _timer(value: dict[str, Any]) -> DurableTimer:
    return DurableTimer(
        value["id"],
        value["automation_id"],
        int(value["generation"]),
        value["causal_sequence"],
        value["producer_key"],
        _target(value["target"]),
        value["logical_deadline_utc_millis"],
        value["expires_at_utc_millis"],
    )


def _timer_json(value: DurableTimer) -> dict[str, Any]:
    return {
        "id": value.id,
        "automation_id": value.automation_id,
        "generation": str(value.generation),
        "causal_sequence": value.causal_sequence,
        "producer_key": value.producer_key,
        "target": _target_json(value.target),
        "logical_deadline_utc_millis": value.logical_deadline_utc_millis,
        "expires_at_utc_millis": value.expires_at_utc_millis,
    }


def _target_json(value: TimerTarget) -> dict[str, Any]:
    result: dict[str, Any] = {"type": value.type}
    if value.utc_millis is not None:
        result["utc_millis"] = value.utc_millis
    if value.elapsed_nanos is not None:
        result["elapsed_nanos"] = str(value.elapsed_nanos)
    if value.boot_session_id is not None:
        result["boot_session_id"] = value.boot_session_id
    if value.elapsed_realtime_nanos is not None:
        result["elapsed_realtime_nanos"] = str(value.elapsed_realtime_nanos)
    return result


def _expected(result: Any) -> dict[str, Any]:
    return {
        "checkpoint": encode_automation_checkpoint(result.checkpoint),
        "checkpoint_sha256": automation_checkpoint_digest(result.checkpoint),
        "actions": [dataclasses.asdict(item) for item in result.action_requests],
        "timer_intents": [
            {
                "type": item.type,
                **(
                    {"timer": _timer_json(item.timer)}
                    if item.timer is not None
                    else {
                        "timer_id": item.timer_id,
                        "generation": str(item.generation),
                    }
                ),
            }
            for item in result.timer_intents
        ],
        "timer_production_requests": [
            {
                "automation_id": item.automation["id"],
                "schedule_type": item.schedule["type"],
                "causal_sequence": item.causal_sequence,
                "current_generation": str(item.current_generation),
                "pending_timer_id": item.pending_timer.id
                if item.pending_timer is not None
                else None,
                "materialized_producer_keys": [
                    entry.producer_key for entry in item.materialized
                ],
            }
            for item in result.timer_production_requests
        ],
        "resource_changes": [
            {
                "kind": key.kind,
                "id": key.id,
                "generation": str(value.generation),
                "profile_id": value.profile_id,
            }
            for key, value in sorted(
                result.resource_changes.items(), key=lambda item: item[0].sort_key()
            )
        ],
        "audits": [dataclasses.asdict(item) for item in result.audits],
    }


def _mutate_reducer_input(value: ReducerInput, mutation: str) -> ReducerInput:
    timer = value.timer
    if value.type != "TIMER_MATERIALIZED" or timer is None:
        raise AssertionError("hostile materialization must reference TIMER_MATERIALIZED")
    if mutation.startswith("SHIFT_TIMER_TARGET_UTC_BY_"):
        delta = int(mutation.removeprefix("SHIFT_TIMER_TARGET_UTC_BY_"))
        if timer.target.type != "CALENDAR_UTC" or timer.target.utc_millis is None:
            raise AssertionError("hostile target shift requires a calendar timer")
        timer = dataclasses.replace(
            timer,
            target=dataclasses.replace(
                timer.target, utc_millis=timer.target.utc_millis + delta
            ),
        )
    elif mutation == "INCREMENT_TIMER_GENERATION":
        timer = dataclasses.replace(timer, generation=timer.generation + 1)
    else:
        raise AssertionError(f"unknown reducer hostile mutation: {mutation}")
    return dataclasses.replace(value, timer=timer)


def _hostile_configuration(
    base: dict[str, Any], mutation: str
) -> tuple[dict[str, Any], Any]:
    configuration = copy.deepcopy(base)
    registry: Any = EventSourceRegistry()

    def add_occurrence(identifier: str, trigger: dict[str, Any], guard: Any = None) -> None:
        intervention_id = f"{identifier}-intervention"
        configuration["interventions"].append({
            "action": {
                "notification_message": "Message",
                "notification_title": "Title",
                "type": "notification",
            },
            "id": intervention_id,
            "required": False,
        })
        configuration["automations"].append({
            "availability_seconds": 60,
            "cooldown": None,
            "guard": guard,
            "id": identifier,
            "intervention_id": intervention_id,
            "maximum_activations": 1,
            "trigger": trigger,
            "type": "occurrence",
        })

    if mutation == "ADD_DENSE_TRIGGER_AND_GUARD":
        def dense() -> dict[str, Any]:
            return {
                "conditions": [
                    {
                        "conditions": [
                            {"type": "study_session_active"} for _ in range(4)
                        ],
                        "type": "any",
                    }
                    for _ in range(8)
                ],
                "type": "all",
            }

        add_occurrence(
            "hostile-dense",
            {"condition": dense(), "type": "condition_rising_edge"},
            dense(),
        )
    elif mutation == "ADD_513_CONCURRENT_TIMERS":
        for index in range(57):
            guard = {
                "conditions": [
                    {
                        "clock": "ACTIVE_RUNNING_TIME",
                        "condition": {"type": "study_session_active"},
                        "duration_seconds": 1,
                        "type": "held_for",
                    }
                    for _ in range(8)
                ],
                "type": "all",
            }
            add_occurrence(
                f"hostile-timer-{index:02d}",
                {
                    "schedule": {
                        "clock": "ACTIVE_RUNNING_TIME",
                        "offset_minutes": 0,
                        "type": "one_time",
                    },
                    "type": "schedule",
                },
                guard,
            )
    elif mutation == "USE_AUDIT_ONLY_STUDY_RUNNING_EVENT":
        add_occurrence(
            "hostile-lifecycle-feedback",
            {
                "evaluation_clock": "OBSERVED_RESEARCH_TIME",
                "selector": {
                    "event": {
                        "event_type": "STUDY_RUNNING",
                        "schema_version": 1,
                        "source_id": "study_runtime.v1",
                    },
                    "predicates": [],
                },
                "type": "event_match",
            },
        )
    elif mutation.startswith("ADD_RANDOM_"):
        if mutation == "ADD_RANDOM_DAILY_CAPACITY_OVERFLOW":
            windows, daily, separation = ([{"start_local_time": "08:00", "end_local_time": "09:00"}], 2, 1)
        elif mutation == "ADD_RANDOM_ADJACENT_SEPARATION_VIOLATION":
            windows, daily, separation = ([
                {"start_local_time": "08:00", "end_local_time": "09:00"},
                {"start_local_time": "09:30", "end_local_time": "10:30"},
            ], 2, 32)
        elif mutation == "ADD_RANDOM_CYCLIC_SEPARATION_VIOLATION":
            windows, daily, separation = ([
                {"start_local_time": "00:30", "end_local_time": "01:30"},
                {"start_local_time": "23:00", "end_local_time": "23:30"},
            ], 2, 62)
        else:
            raise AssertionError(f"unknown compiler hostile mutation: {mutation}")
        add_occurrence(
            "hostile-random",
            {
                "schedule": {
                    "local_windows": windows,
                    "maximum_occurrences_per_day": daily,
                    "maximum_occurrences_total": 10,
                    "minimum_separation_minutes": separation,
                    "occurrences_per_window": 1,
                    "type": "random_window",
                },
                "type": "schedule",
            },
        )
    elif mutation == "SET_61_ASTRAL_TITLE":
        configuration["title"] = "😀" * 61
    elif mutation == "SWAP_PRESENCE_ENTER_EXIT":
        presence = _first_keyed_presence(configuration)
        presence["enter_when"], presence["exit_when"] = (
            presence["exit_when"],
            presence["enter_when"],
        )
    elif mutation == "SET_PRESENCE_KEY_TO_PACKAGE_NAME":
        _first_keyed_presence(configuration)["key_field"] = "package_name"
    elif mutation == "USE_NON_PRESENCE_ENTER_EVENT":
        _first_keyed_presence(configuration)["enter_when"][0]["event"][
            "event_type"
        ] = "DEVICE_STARTUP"
    elif mutation == "ALTER_EXIT_PRESENCE_GROUP_CONTRACT":
        class MismatchedPresenceGroupRegistry(EventSourceRegistry):
            def __init__(self) -> None:
                super().__init__()
                key = ("usage_events.v1", 1, "ACTIVITY_PAUSED")
                schema = self._events[key]
                trigger = dict(schema.trigger)
                presence = dict(trigger["presence"])
                presence["group_id"] = "different_presence_group"
                trigger["presence"] = presence
                self._events[key] = dataclasses.replace(schema, trigger=trigger)

        registry = MismatchedPresenceGroupRegistry()
    elif mutation == "DECLARE_64_COLLECTORS_AND_TRAFFIC":
        configuration["collectors"] = [
            {
                "id": f"synthetic_{index:02d}.v1",
                "profiles": [{"config": {}, "id": "continuous"}],
                "required": False,
            }
            for index in range(64)
        ]

        class SyntheticRegistry:
            def __init__(self) -> None:
                self.base = EventSourceRegistry()

            def source(self, source_id: str, schema_version: int = 1) -> Any:
                return self.base.source("battery_state.v1", schema_version)

            def validate_profile(self, source_id: str, value: Any) -> dict[str, Any]:
                if value != {}:
                    raise ValidationError("synthetic profile must be empty")
                return value

        registry = SyntheticRegistry()
    else:
        raise AssertionError(f"unknown compiler hostile mutation: {mutation}")

    configuration["interventions"].sort(key=lambda item: item["id"])
    configuration["automations"].sort(key=lambda item: item["id"])
    return configuration, registry


def _first_keyed_presence(configuration: dict[str, Any]) -> dict[str, Any]:
    def visit(condition: dict[str, Any]) -> dict[str, Any] | None:
        kind = condition["type"]
        if kind == "keyed_presence":
            return condition
        if kind in {"held_for", "not"}:
            return visit(condition["condition"])
        if kind in {"all", "any"}:
            for child in condition["conditions"]:
                if found := visit(child):
                    return found
        return None

    for automation in configuration["automations"]:
        if automation["type"] == "resource_binding":
            conditions = [case["condition"] for case in automation["cases"]]
        else:
            conditions = [automation["guard"]]
            if automation["trigger"]["type"] == "condition_rising_edge":
                conditions.append(automation["trigger"]["condition"])
        for condition in conditions:
            if condition is not None and (found := visit(condition)) is not None:
                return found
    raise AssertionError("shared presence hostile base has no keyed-presence condition")


if __name__ == "__main__":
    unittest.main()
