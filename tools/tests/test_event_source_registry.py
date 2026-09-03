from __future__ import annotations

import copy
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools import event_source_registry


class EventSourceRegistryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.valid = event_source_registry.load_registry()

    def test_repository_registry_and_every_projection_are_current(self) -> None:
        event_source_registry.validate_registry(self.valid)
        event_source_registry.check_artifacts(event_source_registry.render_artifacts(self.valid))

    def test_registry_contains_the_required_typed_runtime_sources(self) -> None:
        by_id = {source["source_id"]: source for source in self.valid["sources"]}
        self.assertEqual(
            {
                "automation_runtime.v1",
                "interventions.v1",
                "study_condition.v1",
                "study_runtime.v1",
                "timer.v1",
                "traffic_shaping.v1",
            },
            {source_id for source_id, source in by_id.items() if source["source_kind"] == "SYSTEM"},
        )
        usage = by_id["usage_events.v1"]
        self.assertIn("poll_interval_seconds", usage["configuration"]["fields"])
        self.assertNotIn("poll_interval_minutes", usage["configuration"]["fields"])
        lifecycle = {
            event["event_type"]: event
            for event in by_id["study_runtime.v1"]["events"]
        }
        for event_type in event_source_registry.AUDIT_ONLY_STUDY_LIFECYCLE_EVENTS:
            event = lifecycle[event_type]
            self.assertEqual("AUDIT_ONLY", event["trigger"]["scope"])
            self.assertEqual([], event["trigger"]["condition_kinds"])
            self.assertEqual("NONE", event["privacy"]["trigger_exposure"])

    def test_duplicate_member_and_non_integral_number_are_rejected_while_loading(self) -> None:
        for encoded, message in (
            ('{"registry_format":"a","registry_format":"b"}', "duplicate"),
            ('{"registry_version":1.5}', "non-integral"),
        ):
            with self.subTest(message=message), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "registry.json"
                path.write_text(encoded, encoding="utf-8")
                with self.assertRaisesRegex(event_source_registry.RegistryError, message):
                    event_source_registry.load_registry(path)

    def test_unknown_members_are_rejected_by_the_closed_schema(self) -> None:
        hostile = copy.deepcopy(self.valid)
        hostile["fallback"] = True
        with self.assertRaisesRegex(event_source_registry.RegistryError, "unknown member"):
            event_source_registry.validate_registry(hostile)

    def test_invalid_field_operator_is_rejected(self) -> None:
        hostile = copy.deepcopy(self.valid)
        source = next(item for item in hostile["sources"] if item["source_id"] == "battery_state.v1")
        event = next(item for item in source["events"] if item["event_type"] == "BATTERY_STATE")
        event["fields"]["charging_state"]["operators"] = ["gt"]
        with self.assertRaisesRegex(event_source_registry.RegistryError, "incompatible trigger operator"):
            event_source_registry.validate_registry(hostile)

    def test_presence_requires_matching_enter_and_exit_contracts(self) -> None:
        hostile = copy.deepcopy(self.valid)
        source = next(item for item in hostile["sources"] if item["source_id"] == "usage_events.v1")
        paused = next(item for item in source["events"] if item["event_type"] == "ACTIVITY_PAUSED")
        paused["trigger"]["presence"]["group_id"] = "different-group"
        with self.assertRaisesRegex(event_source_registry.RegistryError, "must have ENTER and EXIT"):
            event_source_registry.validate_registry(hostile)

    def test_published_source_contracts_cannot_change_in_place(self) -> None:
        hostile = copy.deepcopy(self.valid)
        hostile["sources"][0]["events"][0]["maximum_encoded_event_bytes"] += 1
        with self.assertRaisesRegex(event_source_registry.RegistryError, "changed in place"):
            event_source_registry.check_immutability(self.valid, hostile)

    def test_integral_jcs_uses_utf16_member_order(self) -> None:
        value = {"\ufffd": 1, "\U00010000": 2}
        self.assertEqual(
            '{"\U00010000":2,"\ufffd":1}'.encode(),
            event_source_registry.canonical_json(value),
        )

    def test_pull_request_merge_base_is_required(self) -> None:
        with mock.patch.dict(os.environ, {"GITHUB_BASE_REF": "main"}, clear=True), self.assertRaisesRegex(
            event_source_registry.RegistryError,
            "PARTICEPS_REGISTRY_MERGE_BASE is required",
        ):
            event_source_registry._check_configured_merge_base(self.valid)

    def test_unavailable_declared_base_fails_closed(self) -> None:
        missing = subprocess.CalledProcessError(128, ["git", "show"])
        with (
            mock.patch.dict(os.environ, {"PARTICEPS_REGISTRY_MERGE_BASE": "base-sha"}, clear=True),
            mock.patch.object(event_source_registry.subprocess, "run", side_effect=missing),
            self.assertRaisesRegex(event_source_registry.RegistryError, "merge-base registry is unavailable"),
        ):
            event_source_registry._check_configured_merge_base(self.valid)

    def test_initial_transition_requires_explicit_operator_authorization(self) -> None:
        missing = subprocess.CalledProcessError(128, ["git", "show"])
        with (
            mock.patch.dict(
                os.environ,
                {
                    "PARTICEPS_REGISTRY_MERGE_BASE": "initial-registry-parent",
                    "PARTICEPS_ALLOW_INITIAL_REGISTRY_TRANSITION": "1",
                },
                clear=True,
            ),
            mock.patch.object(event_source_registry.subprocess, "run", side_effect=missing),
        ):
            event_source_registry._check_configured_merge_base(self.valid)

    def test_language_neutral_conformance_corpus_is_current_and_hostile(self) -> None:
        corpus_root = (
            Path(__file__).parents[2]
            / "protocol/v1/conformance/event-source-registry"
        )
        manifest = json.loads((corpus_root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(
            "particeps-event-source-registry-conformance-v1",
            manifest["corpus_format"],
        )
        valid = manifest["valid_cases"]
        self.assertEqual(1, len(valid))
        canonical = (corpus_root / valid[0]["canonical_jcs"]).read_bytes()
        self.assertEqual(event_source_registry.canonical_json(self.valid), canonical)
        self.assertEqual(valid[0]["registry_sha256"], hashlib.sha256(canonical).hexdigest())
        self.assertEqual(valid[0]["source_count"], len(self.valid["sources"]))

        for case in manifest["invalid_cases"]:
            hostile = copy.deepcopy(self.valid)
            with self.subTest(case=case["id"]), self.assertRaisesRegex(
                event_source_registry.RegistryError,
                case["expected_error"],
            ):
                if case["mutation"] == "MUTATE_PUBLISHED_EVENT_SIZE":
                    hostile["sources"][0]["events"][0]["maximum_encoded_event_bytes"] += 1
                    event_source_registry.check_immutability(self.valid, hostile)
                elif case["mutation"] == "DELETE_PUBLISHED_SOURCE":
                    hostile["sources"].pop(0)
                    event_source_registry.check_immutability(self.valid, hostile)
                else:
                    self._apply_registry_mutation(hostile, case["mutation"])
                    event_source_registry.validate_registry(hostile)

    def test_automation_corpus_binding_validates_every_event_reference(self) -> None:
        corpus = event_source_registry._load_json(
            event_source_registry.AUTOMATION_REDUCER_CORPUS_PATH
        )
        validator = event_source_registry._AutomationCorpusRegistryValidator(self.valid)
        validator.validate(corpus)
        expected = copy.deepcopy(corpus)
        expected["registry_sha256"] = event_source_registry._sha256(self.valid)
        rebound = json.loads(
            event_source_registry._render_automation_reducer_registry_binding(
                self.valid,
                expected["registry_sha256"],
            )
        )
        self.assertEqual(expected, rebound)

        scenario = corpus["scenarios"][0]
        occurrence_index = next(
            index
            for index, automation in enumerate(scenario["configuration"]["automations"])
            if automation["id"] == "occ-event"
        )

        def event_match(hostile: dict) -> dict:
            return hostile["scenarios"][0]["configuration"]["automations"][occurrence_index]["trigger"]["selector"]

        def first_event_fields(hostile: dict) -> dict:
            step = next(
                item for item in hostile["scenarios"][0]["steps"]
                if item["input"]["type"] == "EVENT"
            )
            return step["input"]["event"]["fields"]

        for mutation, message in (
            (
                lambda hostile: event_match(hostile)["event"].__setitem__("source_id", "absent.v1"),
                "absent or ambiguous registry source",
            ),
            (
                lambda hostile: event_match(hostile)["event"].__setitem__("event_type", "ABSENT"),
                "absent registry event",
            ),
            (
                lambda hostile: event_match(hostile)["predicates"][0].__setitem__("field", "absent"),
                "absent event field",
            ),
            (
                lambda hostile: event_match(hostile)["predicates"][0].__setitem__("operator", "regex"),
                "not permitted",
            ),
            (
                lambda hostile: first_event_fields(hostile).__setitem__("absent", "value"),
                "differ from the registry",
            ),
        ):
            with self.subTest(message=message):
                hostile = copy.deepcopy(corpus)
                mutation(hostile)
                with self.assertRaisesRegex(event_source_registry.RegistryError, message):
                    validator.validate(hostile)

    def _apply_registry_mutation(self, registry: dict, mutation: str) -> None:
        sources = registry["sources"]
        if mutation == "DUPLICATE_FIRST_SOURCE":
            sources.insert(1, copy.deepcopy(sources[0]))
        elif mutation == "REVERSE_SOURCES":
            sources.reverse()
        elif mutation == "ADD_ROOT_MEMBER":
            registry["unknown"] = True
        elif mutation == "INVALID_FIRST_SOURCE_ID":
            sources[0]["source_id"] = "Invalid"
        elif mutation == "ZERO_FIRST_SCHEMA_VERSION":
            sources[0]["schema_version"] = 0
        elif mutation == "MAKE_FIRST_SYSTEM_SELECTABLE":
            next(source for source in sources if source["source_kind"] == "SYSTEM")["selectable"] = True
        elif mutation == "REMOVE_FIRST_COLLECTOR_CONFIGURATION":
            next(source for source in sources if source["source_kind"] == "COLLECTOR")["configuration"] = None
        elif mutation == "INVALID_FIRST_RESEARCHER_FIELD_OPERATOR":
            for source in sources:
                for event in source["events"]:
                    if event["trigger"]["scope"] != "RESEARCHER":
                        continue
                    for field in event["fields"].values():
                        if field["wire_type"] in {"boolean", "enum", "sha256_hex", "string", "uuid"} and field["operators"]:
                            field["operators"] = ["gt"]
                            return
            self.fail("current registry has no researcher field")
        elif mutation == "INVALID_FIRST_PRIMARY_CLOCK_FIELD":
            for source in sources:
                for event in source["events"]:
                    if event["clock"]["primary_source_basis"] != "NONE":
                        event["clock"]["primary_source_time_field"] = "missing_time"
                        return
            self.fail("current registry has no primary source clock")
        elif mutation == "BREAK_FIRST_PRESENCE_PAIR":
            for source in sources:
                for event in source["events"]:
                    presence = event["trigger"]["presence"]
                    if presence is not None:
                        presence["group_id"] += "_broken"
                        return
            self.fail("current registry has no presence pair")
        elif mutation == "MAKE_STUDY_RUNNING_RESEARCHER":
            source = next(source for source in sources if source["source_id"] == "study_runtime.v1")
            event = next(event for event in source["events"] if event["event_type"] == "STUDY_RUNNING")
            event["privacy"]["trigger_exposure"] = "DECLARED_FIELDS_ONLY"
            event["trigger"]["condition_kinds"] = ["EVENT_MATCH"]
            event["trigger"]["scope"] = "RESEARCHER"
        elif mutation == "REMOVE_FIRST_FINITE_RATE_ENFORCEMENT":
            for source in sources:
                for event in source["events"]:
                    if event["rate_bound"]["kind"] in {"HARD", "CONFIGURATION_DERIVED"}:
                        event["rate_bound"]["enforced_by"] = ""
                        return
            self.fail("current registry has no finite rate contract")
        else:
            self.fail(f"unknown corpus mutation: {mutation}")


if __name__ == "__main__":
    unittest.main()
