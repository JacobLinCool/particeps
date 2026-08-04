from __future__ import annotations

import copy
import json
import unittest
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from adc_analysis.catalog import CollectorCatalog
from adc_analysis.configuration import validate_configuration
from adc_analysis.errors import ValidationError
from adc_analysis.jcs import parse

REPOSITORY = Path(__file__).resolve().parents[2]
PROTOCOL = REPOSITORY / "protocol" / "v1"


class ConfigurationAndCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = CollectorCatalog(PROTOCOL / "collector-catalog.json")
        corpus = json.loads((PROTOCOL / "conformance-vectors.json").read_text())
        encoded = bytes.fromhex(
            corpus["valid"]["signed_configuration"]["canonical_jcs_utf8_hex"]
        )
        cls.configuration = parse(encoded)

    def test_random_window_exact_schema_and_bounds(self) -> None:
        configuration = copy.deepcopy(self.configuration)
        configuration["interventions"] = [
            {
                "id": "daily-ema",
                "action": {
                    "type": "notification",
                    "notification_title": "Check in",
                    "notification_message": "How are you?",
                },
                "triggers": [
                    {
                        "id": "daily-ema-trigger",
                        "availability_minutes": 30,
                        "schedule": {
                            "type": "random_window",
                            "local_windows": [
                                {
                                    "start_local_time": "09:00",
                                    "end_local_time": "11:00",
                                },
                                {
                                    "start_local_time": "15:00",
                                    "end_local_time": "17:00",
                                },
                            ],
                            "occurrences_per_window": 1,
                            "maximum_occurrences_per_day": 2,
                            "maximum_occurrences_total": 20,
                            "minimum_separation_minutes": 60,
                        },
                    }
                ],
            }
        ]
        validate_configuration(configuration, self.catalog)
        invalid = copy.deepcopy(configuration)
        invalid["interventions"][0]["triggers"][0]["schedule"]["local_windows"][1][
            "start_local_time"
        ] = "10:00"
        with self.assertRaises(ValidationError):
            validate_configuration(invalid, self.catalog)
        impossible = copy.deepcopy(configuration)
        schedule = impossible["interventions"][0]["triggers"][0]["schedule"]
        schedule["local_windows"] = [
            {"start_local_time": "09:00", "end_local_time": "09:30"}
        ]
        schedule["occurrences_per_window"] = 2
        schedule["maximum_occurrences_per_day"] = 2
        schedule["minimum_separation_minutes"] = 30
        with self.assertRaises(ValidationError):
            validate_configuration(impossible, self.catalog)

    def test_random_window_global_bound_uses_each_signed_lifetime_cap(self) -> None:
        configuration = copy.deepcopy(self.configuration)
        configuration["duration_hours"] = 1

        def trigger(index: int) -> dict[str, Any]:
            return {
                "id": f"random-trigger-{index}",
                "availability_minutes": 30,
                "schedule": {
                    "type": "random_window",
                    "local_windows": [
                        {"start_local_time": "08:00", "end_local_time": "09:00"}
                    ],
                    "occurrences_per_window": 8,
                    "maximum_occurrences_per_day": 8,
                    "maximum_occurrences_total": 512,
                    "minimum_separation_minutes": 1,
                },
            }

        configuration["interventions"] = [
            {
                "id": "date-line-ema",
                "action": {
                    "type": "notification",
                    "notification_title": "Check in",
                    "notification_message": "Please respond",
                },
                "triggers": [trigger(1)],
            }
        ]
        validate_configuration(configuration, self.catalog)
        configuration["interventions"][0]["triggers"].append(trigger(2))
        with self.assertRaises(ValidationError):
            validate_configuration(configuration, self.catalog)

    def test_catalog_converts_wire_strings_without_schema_inference(self) -> None:
        battery = self.catalog.payload("battery_state.v1", 1, "BATTERY_STATE")
        fields = {
            "charging_source": "USB",
            "charging_state": "CHARGING",
            "percentage": "87",
            "power_save_enabled": "false",
        }
        typed = self.catalog.typed_fields(battery, fields)
        self.assertEqual(
            {
                "charging_source": "USB",
                "charging_state": "CHARGING",
                "percentage": 87,
                "power_save_enabled": False,
            },
            typed,
        )
        with self.assertRaises(ValidationError):
            self.catalog.typed_fields(battery, {"percentage": "87"})
        with self.assertRaises(ValidationError):
            self.catalog.typed_fields(battery, dict(typed, unknown="value"))
        for invalid_percentage in ("-1", "-0", "+1", "01", "101"):
            with (
                self.subTest(percentage=invalid_percentage),
                self.assertRaises(ValidationError),
            ):
                self.catalog.typed_fields(
                    battery, dict(fields, percentage=invalid_percentage)
                )

    def test_optional_payload_fields_remain_nullable(self) -> None:
        location = self.catalog.payload("location.v1", 1, "LOCATION_FIX")
        required = {
            name: _example(descriptor["type"], descriptor)
            for name, descriptor in location.fields.items()
            if descriptor["required"]
        }
        converted = self.catalog.typed_fields(location, required)
        self.assertEqual(set(required), set(converted))

    def test_instant_text_must_match_the_signed_configuration_codec(self) -> None:
        invalid = copy.deepcopy(self.configuration)
        invalid["issued_at"] = "2026-01-01T00:00:00.000Z"
        with self.assertRaises(ValidationError):
            validate_configuration(invalid, self.catalog)

    def test_every_catalog_payload_has_a_concrete_typed_conversion(self) -> None:
        for schema in self.catalog.payload_schemas:
            with self.subTest(
                collector=schema.collector_id, payload=schema.payload_type
            ):
                fields = {
                    name: _example(descriptor["type"], descriptor)
                    for name, descriptor in schema.fields.items()
                }
                self.assertEqual(
                    set(fields), set(self.catalog.typed_fields(schema, fields))
                )

    def test_all_survey_questions_and_local_schedule_types(self) -> None:
        configuration = copy.deepcopy(self.configuration)
        text = {"default": "Prompt", "translations": {"zh-TW": "問題"}}
        options = [
            {"id": "option-a", "label": text},
            {"id": "option-b", "label": text},
        ]
        configuration["surveys"] = [
            {
                "id": "daily-survey",
                "title": text,
                "description": text,
                "questions": [
                    {
                        "type": "short_text",
                        "id": "short-question",
                        "prompt": text,
                        "required": True,
                        "maximum_length": 200,
                    },
                    {
                        "type": "scale",
                        "id": "scale-question",
                        "prompt": text,
                        "required": True,
                        "minimum": 1,
                        "maximum": 7,
                        "minimum_label": text,
                        "maximum_label": text,
                    },
                    {
                        "type": "single_choice",
                        "id": "single-question",
                        "prompt": text,
                        "required": False,
                        "options": options,
                    },
                    {
                        "type": "multiple_choice",
                        "id": "multiple-question",
                        "prompt": text,
                        "required": True,
                        "options": options,
                        "minimum_selections": 1,
                        "maximum_selections": 2,
                    },
                ],
            }
        ]
        configuration["interventions"] = [
            {
                "id": "survey-reminder",
                "action": {
                    "type": "survey",
                    "notification_title": "Survey",
                    "notification_message": "Please respond",
                    "survey_id": "daily-survey",
                },
                "triggers": [
                    {
                        "id": "one-time-trigger",
                        "availability_minutes": 30,
                        "schedule": {
                            "type": "one_time",
                            "offset_minutes": 0,
                            "clock": "ACTIVE_RUNNING_TIME",
                        },
                    },
                    {
                        "id": "interval-trigger",
                        "availability_minutes": 30,
                        "schedule": {
                            "type": "interval",
                            "start_offset_minutes": 0,
                            "interval_minutes": 1_440,
                            "clock": "CALENDAR_TIME",
                        },
                    },
                    {
                        "id": "daily-trigger",
                        "availability_minutes": 30,
                        "schedule": {"type": "daily_local", "local_time": "08:30"},
                    },
                ],
            }
        ]
        validate_configuration(configuration, self.catalog)
        invalid = copy.deepcopy(configuration)
        invalid["surveys"][0]["questions"][3]["minimum_selections"] = 0
        with self.assertRaises(ValidationError):
            validate_configuration(invalid, self.catalog)


def _example(kind: str, descriptor: Mapping[str, Any]) -> str:
    if kind == "boolean":
        return "false"
    if kind == "decimal_string":
        return "1"
    if kind == "enum":
        return descriptor["enum"][0]
    if kind in {"float32", "float64"}:
        minimum = descriptor.get("minimum", 0)
        return str(float(minimum))
    if kind == "int32":
        return "1"
    if kind == "json_string":
        return "{}"
    return "value"


if __name__ == "__main__":
    unittest.main()
