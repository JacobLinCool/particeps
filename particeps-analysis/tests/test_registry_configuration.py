from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from factories import configuration

from particeps_analysis.configuration import validate_configuration
from particeps_analysis.errors import ValidationError
from particeps_analysis.generated.event_source_registry import (
    EVENT_SOURCE_REGISTRY_SHA256,
)
from particeps_analysis.jcs import parse
from particeps_analysis.registry import EventSourceRegistry


def battery_configuration() -> dict:
    value = configuration()
    value["collectors"] = [
        {
            "id": "battery_state.v1",
            "profiles": [{"config": {}, "id": "continuous"}],
            "required": True,
        }
    ]
    value["automations"] = [
        {
            "cases": [
                {
                    "condition": {"type": "study_session_active"},
                    "profile_id": "continuous",
                }
            ],
            "default_profile_id": "continuous",
            "id": "bind-battery",
            "resource": {"id": "battery_state.v1", "kind": "collector"},
            "type": "resource_binding",
        }
    ]
    return value


def event_match_configuration(
    *,
    source_id: str,
    profile: dict,
    event_type: str,
    predicate: dict,
) -> dict:
    value = configuration()
    value["collectors"] = [{
        "id": source_id,
        "profiles": [{"config": profile, "id": "continuous"}],
        "required": True,
    }]
    value["interventions"] = [{
        "action": {
            "notification_message": "Message",
            "notification_title": "Title",
            "type": "notification",
        },
        "id": "notify-one",
        "required": True,
    }]
    value["automations"] = [
        {
            "cases": [{
                "condition": {"type": "study_session_active"},
                "profile_id": "continuous",
            }],
            "default_profile_id": "continuous",
            "id": "bind-source",
            "resource": {"id": source_id, "kind": "collector"},
            "type": "resource_binding",
        },
        {
            "availability_seconds": 300,
            "cooldown": None,
            "guard": None,
            "id": "event-trigger",
            "intervention_id": "notify-one",
            "maximum_activations": 10,
            "trigger": {
                "evaluation_clock": "OBSERVED_RESEARCH_TIME",
                "selector": {
                    "event": {
                        "event_type": event_type,
                        "schema_version": 1,
                        "source_id": source_id,
                    },
                    "predicates": [predicate],
                },
                "type": "event_match",
            },
            "type": "occurrence",
        },
    ]
    return value


class RegistryConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = EventSourceRegistry()

    def test_generated_registry_is_the_only_typed_authority(self) -> None:
        self.assertEqual(
            EVENT_SOURCE_REGISTRY_SHA256,
            self.registry.digest,
        )

        schema = self.registry.event("battery_state.v1", 1, "BATTERY_STATE")
        decoded = self.registry.typed_fields(
            schema,
            {
                "charging_source": "USB",
                "charging_state": "CHARGING",
                "percentage": "50",
                "power_save_enabled": "false",
            },
        )
        self.assertEqual(50, decoded["percentage"])
        self.assertIs(False, decoded["power_save_enabled"])
        with self.assertRaises(ValidationError):
            self.registry.typed_fields(schema, {**decoded, "percentage": "101"})
        location = self.registry.event("location.v1", 1, "LOCATION_FIX")
        with self.assertRaisesRegex(ValidationError, "above maximum"):
            self.registry.decode_event_field(
                "latitude_degrees",
                "90.1",
                location.fields["latitude_degrees"],
                location.maximum_encoded_event_bytes,
            )

    def test_study_session_active_case_is_total_during_active_session(self) -> None:
        value = battery_configuration()
        binding = value["automations"][0]
        binding["default_profile_id"] = None
        validate_configuration(value, self.registry)

        binding["cases"].insert(
            0,
            {
                "condition": {
                    "clock": "ACTIVE_RUNNING_TIME",
                    "duration_seconds": 1,
                    "type": "elapsed_at_least",
                },
                "profile_id": None,
            },
        )
        with self.assertRaisesRegex(ValidationError, "required resource"):
            validate_configuration(value, self.registry)

    def test_event_float_wire_and_signed_predicate_spelling_are_distinct(self) -> None:
        schema = self.registry.event(
            "accelerometer.v1", 1, "ACCELEROMETER_SAMPLE"
        )
        descriptor = schema.fields["x_meters_per_second_squared"]
        expected = {
            "+1": 1.0,
            "01": 1.0,
            ".5": 0.5,
            "1.": 1.0,
            "1e-3": 0.001,
            "1E+3": 1_000.0,
        }
        for wire, number in expected.items():
            with self.subTest(wire=wire):
                self.assertEqual(
                    number,
                    self.registry.decode_event_field(
                        "x_meters_per_second_squared",
                        wire,
                        descriptor,
                        schema.maximum_encoded_event_bytes,
                    ),
                )
                with self.assertRaisesRegex(ValidationError, "canonical Java"):
                    self.registry.decode_predicate_literal(
                        "x_meters_per_second_squared",
                        wire,
                        descriptor,
                        schema.maximum_encoded_event_bytes,
                    )
        for hostile in (
            "NaN", "Infinity", "-Infinity", "1e309", "0x1.0p0",
            "1_0", " 1", "1 ", ".", "+",
        ):
            with self.subTest(hostile=hostile), self.assertRaises(ValidationError):
                self.registry.decode_event_field(
                    "x_meters_per_second_squared",
                    hostile,
                    descriptor,
                    schema.maximum_encoded_event_bytes,
                )

        canonical = event_match_configuration(
            source_id="accelerometer.v1",
            profile={
                "maximum_report_latency_us": 1_000_000,
                "sampling_period_us": 100_000,
            },
            event_type="ACCELEROMETER_SAMPLE",
            predicate={
                "field": "x_meters_per_second_squared",
                "operator": "eq",
                "value": "1.0",
            },
        )
        validate_configuration(canonical, self.registry)
        for wire in expected:
            hostile = copy.deepcopy(canonical)
            hostile["automations"][1]["trigger"]["selector"]["predicates"][0][
                "value"
            ] = wire
            with self.subTest(predicate=wire), self.assertRaises(ValidationError):
                validate_configuration(hostile, self.registry)

    def test_uuid_decoder_rejects_rfc_variant_versions_outside_one_through_five(self) -> None:
        schema = self.registry.event(
            "study_condition.v1", 1, "CONDITION_EPOCH_ACTIVATED"
        )
        descriptor = schema.fields["condition_epoch_id"]
        self.registry.decode_event_field(
            "condition_epoch_id",
            "b7f90e3c-2f22-4fe5-b838-d8b5d3082e69",
            descriptor,
            schema.maximum_encoded_event_bytes,
        )
        with self.assertRaisesRegex(ValidationError, "version 1-5"):
            self.registry.decode_event_field(
                "condition_epoch_id",
                "b7f90e3c-2f22-6fe5-b838-d8b5d3082e69",
                descriptor,
                schema.maximum_encoded_event_bytes,
            )

    def test_embedded_json_accepts_non_jcs_but_rejects_duplicate_members(self) -> None:
        schema = self.registry.event(
            "automation_runtime.v1", 1, "ACTION_FAILED"
        )
        descriptor = schema.fields["logical_time"]
        self.assertEqual(
            {"b": 2, "a": 1},
            self.registry.decode_event_field(
                "logical_time",
                '{ "b": 2, "a": 1 }',
                descriptor,
                schema.maximum_encoded_event_bytes,
            ),
        )
        for hostile in (
            '{"a":1,"a":2}',
            '{"outer":{"a":1,"a":2}}',
            r'{"a":1,"\u0061":2}',
            '\ufeff{"a":1}',
        ):
            with self.subTest(hostile=hostile), self.assertRaises(ValidationError):
                self.registry.decode_event_field(
                    "logical_time",
                    hostile,
                    descriptor,
                    schema.maximum_encoded_event_bytes,
                )

    def test_in_values_use_utf16_code_unit_order(self) -> None:
        astral = "\U00010000"
        private_use = "\uE000"
        ordered = event_match_configuration(
            source_id="app_lifecycle.v1",
            profile={},
            event_type="ACTIVITY_RESUMED",
            predicate={
                "field": "activity_class",
                "operator": "in",
                "values": [astral, private_use],
            },
        )
        validate_configuration(ordered, self.registry)
        reversed_values = copy.deepcopy(ordered)
        reversed_values["automations"][1]["trigger"]["selector"]["predicates"][0][
            "values"
        ] = [private_use, astral]
        with self.assertRaisesRegex(ValidationError, "sorted unique"):
            validate_configuration(reversed_values, self.registry)

    def test_lifecycle_outputs_are_audit_only_in_the_analysis_bridge(self) -> None:
        for event_type in ("STUDY_STARTED", "STUDY_RESUMED", "STUDY_RUNNING"):
            with self.subTest(event_type=event_type):
                event = self.registry.event("study_runtime.v1", 1, event_type)
                self.assertEqual("AUDIT_ONLY", event.trigger["scope"])
                self.assertEqual((), event.trigger["condition_kinds"])

    def test_current_configuration_and_named_profile_validate(self) -> None:
        value = battery_configuration()
        self.assertIs(value, validate_configuration(value, self.registry))

    def test_text_bounds_count_utf16_code_units(self) -> None:
        maximum = configuration()
        maximum["title"] = "😀" * 60
        validate_configuration(maximum, self.registry)

        too_long = copy.deepcopy(maximum)
        too_long["title"] += "😀"
        with self.assertRaisesRegex(ValidationError, "title length"):
            validate_configuration(too_long, self.registry)

    def test_survey_array_is_bounded_at_128(self) -> None:
        def survey(index: int) -> dict:
            return {
                "description": {"default": "Description", "translations": {}},
                "id": f"survey-{index:03d}",
                "questions": [{
                    "id": "question-one",
                    "maximum_length": 100,
                    "prompt": {"default": "Prompt", "translations": {}},
                    "required": False,
                    "type": "short_text",
                }],
                "title": {"default": "Survey", "translations": {}},
            }

        maximum = configuration()
        maximum["surveys"] = [survey(index) for index in range(128)]
        validate_configuration(maximum, self.registry)
        maximum["surveys"].append(survey(128))
        with self.assertRaisesRegex(ValidationError, "surveys has invalid length"):
            validate_configuration(maximum, self.registry)

    def test_shared_hostile_configuration_vectors_fail_closed(self) -> None:
        corpus = json.loads(
            (Path(__file__).parents[2] / "protocol/v1/conformance-vectors.json")
            .read_text(encoding="utf-8")
        )
        for vector in corpus["hostile"]:
            if vector["entrypoint"] != "configuration_jcs":
                continue
            with self.subTest(vector=vector["id"]), self.assertRaises(ValidationError):
                validate_configuration(
                    parse(bytes.fromhex(vector["input_hex"])),
                    self.registry,
                )

    def test_legacy_and_open_world_shapes_are_rejected(self) -> None:
        for missing in ("automations", "traffic_shaping"):
            value = configuration()
            value.pop(missing)
            with self.subTest(missing=missing), self.assertRaises(ValidationError):
                validate_configuration(value, self.registry)
        legacy = configuration()
        legacy["collectors"] = [
            {"collector_id": "battery_state.v1", "configuration": {}}
        ]
        with self.assertRaises(ValidationError):
            validate_configuration(legacy, self.registry)
        hostile = configuration()
        hostile["traffic_shaping"] = {"enabled": False}
        with self.assertRaises(ValidationError):
            validate_configuration(hostile, self.registry)

    def test_trigger_source_must_be_required_and_permanently_live(self) -> None:
        value = battery_configuration()
        value["surveys"] = [
            {
                "description": {"default": "Description", "translations": {}},
                "id": "survey-one",
                "questions": [
                    {
                        "id": "question-one",
                        "maximum_length": 100,
                        "prompt": {"default": "Prompt", "translations": {}},
                        "required": True,
                        "type": "short_text",
                    }
                ],
                "title": {"default": "Survey", "translations": {}},
            }
        ]
        value["interventions"] = [
            {
                "action": {
                    "notification_message": "Please answer",
                    "notification_title": "Survey",
                    "survey_id": "survey-one",
                    "type": "survey",
                },
                "id": "survey-action",
                "required": True,
            }
        ]
        value["automations"].append(
            {
                "availability_seconds": 300,
                "cooldown": None,
                "guard": None,
                "id": "battery-trigger",
                "intervention_id": "survey-action",
                "maximum_activations": 1,
                "trigger": {
                    "evaluation_clock": "OBSERVED_RESEARCH_TIME",
                    "selector": {
                        "event": {
                            "event_type": "BATTERY_STATE",
                            "schema_version": 1,
                            "source_id": "battery_state.v1",
                        },
                        "predicates": [
                            {
                                "field": "percentage",
                                "operator": "lte",
                                "value": "20",
                            }
                        ],
                    },
                    "type": "event_match",
                },
                "type": "occurrence",
            }
        )
        value["automations"].sort(key=lambda item: item["id"])
        validate_configuration(value, self.registry)
        optional = copy.deepcopy(value)
        optional["collectors"][0]["required"] = False
        with self.assertRaisesRegex(ValidationError, "trigger source"):
            validate_configuration(optional, self.registry)
        inactive = copy.deepcopy(value)
        binding = next(
            item
            for item in inactive["automations"]
            if item["type"] == "resource_binding"
        )
        binding["cases"][0]["profile_id"] = None
        with self.assertRaises(ValidationError):
            validate_configuration(inactive, self.registry)

    def test_unknown_operator_and_unbounded_window_fail_before_signing(self) -> None:
        value = battery_configuration()
        matcher = {
            "event": {
                "event_type": "BATTERY_STATE",
                "schema_version": 1,
                "source_id": "battery_state.v1",
            },
            "predicates": [
                {"field": "charging_source", "operator": "gt", "value": "USB"}
            ],
        }
        value["automations"][0]["cases"][0]["condition"] = {
            "aggregate": {"type": "count"},
            "comparison": {"operator": "gte", "value": "1"},
            "evaluation_clock": "OBSERVED_RESEARCH_TIME",
            "selector": matcher,
            "type": "window_threshold",
            "window_seconds": 60,
        }
        with self.assertRaises(ValidationError):
            validate_configuration(value, self.registry)

    def test_clock_enums_are_uppercase_exact(self) -> None:
        duration = battery_configuration()
        duration["automations"][0]["cases"][0]["condition"] = {
            "clock": "ACTIVE_RUNNING_TIME",
            "condition": {"type": "study_session_active"},
            "duration_seconds": 1,
            "type": "held_for",
        }
        validate_configuration(duration, self.registry)
        duration["automations"][0]["cases"][0]["condition"]["clock"] = (
            "active_running_time"
        )
        with self.assertRaisesRegex(ValidationError, "held clock"):
            validate_configuration(duration, self.registry)

        evaluation = battery_configuration()
        evaluation["interventions"] = [
            {
                "action": {
                    "notification_message": "Message",
                    "notification_title": "Title",
                    "type": "notification",
                },
                "id": "notify-one",
                "required": True,
            }
        ]
        evaluation["automations"].append(
            {
                "availability_seconds": 300,
                "cooldown": None,
                "guard": None,
                "id": "battery-event",
                "intervention_id": "notify-one",
                "maximum_activations": 1,
                "trigger": {
                    "evaluation_clock": "OBSERVED_RESEARCH_TIME",
                    "selector": {
                        "event": {
                            "event_type": "BATTERY_STATE",
                            "schema_version": 1,
                            "source_id": "battery_state.v1",
                        },
                        "predicates": [],
                    },
                    "type": "event_match",
                },
                "type": "occurrence",
            }
        )
        evaluation["automations"].sort(key=lambda item: item["id"])
        validate_configuration(evaluation, self.registry)
        occurrence = next(
            item for item in evaluation["automations"] if item["id"] == "battery-event"
        )
        occurrence["trigger"]["evaluation_clock"] = "observed_research_time"
        with self.assertRaisesRegex(ValidationError, "evaluation clock"):
            validate_configuration(evaluation, self.registry)


if __name__ == "__main__":
    unittest.main()
