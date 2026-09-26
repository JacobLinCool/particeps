"""Mutation probes over real runtime exports.

Each probe changes one shape of a frozen RC13 or branch commit chain that the amended
Protocol v1 rules accept, re-signs the commit digests, and must be rejected with the message
of the rule that owns the shape. The unmutated chains replay in test_runtime_bundle_fixtures.
"""

from __future__ import annotations

import copy
import json
import unittest
from collections.abc import Callable
from typing import Any

from factories import resign_commit
from runtime_fixtures import decrypted_chain, is_automation_timer

from particeps_analysis.engine import (
    EngineCommitParser,
    EngineReplayVerifier,
    _observation_digest,
)
from particeps_analysis.errors import ValidationError
from particeps_analysis.registry import EventSourceRegistry

RC13_RESTART = "rc13-jvm-pilot-process-restart.partexp"
RC13_EMULATOR_COMPLETE = "rc13-emulator-complete-from-running.partexp"
RC13_EMULATOR_REBOOT = "rc13-emulator-reboot-while-running.partexp"
BRANCH_RESTART = "branch-jvm-pilot-process-restart.partexp"
RC13_EMPTY_FLUSH_AND_REVOKE = "rc13-jvm-empty-flush-and-vpn-revoked.partexp"
RC13_RELEASE_FAILS = "rc13-jvm-release-fails-then-resume.partexp"
RC13_DEATH_WHILE_PAUSING = "rc13-jvm-death-while-pausing.partexp"
RC13_UNTRUSTED_REBOOT = "rc13-jvm-reboot-without-trusted-time.partexp"
COMPONENT_ORDER = [
    "AUTOMATION_CHECKPOINT", "TIMER", "STUDY_DEADLINE_TIMER", "RESOURCE_AUDIT_TIMER",
    "ACTION_INVOCATION", "UPLOAD_ACKNOWLEDGEMENT", "RESOURCE", "RESOURCE_CLEANUP",
]

Commit = dict[str, Any]


def _types(commit: Commit) -> set[str]:
    return {event["event_type"] for event in commit["events"]}


def _first(commits: list[Commit], predicate: Callable[[Commit], bool]) -> int:
    return next(index for index, commit in enumerate(commits) if predicate(commit))


def _canonical(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), sort_keys=True)


def _remove_event(commit: Commit, position: int) -> None:
    """Drop one event from the last replayed commit and close its sequence range."""

    events = commit["events"]
    del events[position]
    for event in events[position:]:
        event["sequence_number"] = str(int(event["sequence_number"]) - 1)
    projection = commit["successor_projection"]
    projection["next_event_sequence"] = str(int(projection["next_event_sequence"]) - 1)


def _move_event_time(event: dict[str, Any], boot: str, elapsed: int, wall: int) -> None:
    """Move a condition event's boundary and observed time together, as a forger would."""

    event["fields"]["boundary_research_time"] = _canonical({
        "boot_session_id": boot, "monotonic_time_nanos": str(elapsed), "wall_time_utc_millis": str(wall),
    })
    event["observed_time"] = {
        "boot_session_id": boot, "elapsed_realtime_nanos": str(elapsed), "wall_time_utc_millis": str(wall),
    }


def _deactivation(commit: Commit) -> dict[str, Any]:
    return next(event for event in commit["events"] if event["event_type"] == "CONDITION_EPOCH_DEACTIVATED")


def _append_event(commit: Commit, event: dict[str, Any]) -> None:
    projection = commit["successor_projection"]
    event["sequence_number"] = projection["next_event_sequence"]
    commit["events"].append(event)
    projection["next_event_sequence"] = str(int(projection["next_event_sequence"]) + 1)


class RuntimeBundleMutationTest(unittest.TestCase):
    registry = EventSourceRegistry()

    def assert_rejected(
        self,
        fixture: str,
        locate: Callable[[list[Commit]], int],
        mutate: Callable[[list[Commit], int], None],
        message: str,
        *,
        whole_chain: bool = False,
    ) -> None:
        """Mutate one commit and require the rejection at that commit, or at end of replay."""

        configuration, digest, commits = decrypted_chain(fixture)
        index = locate(commits)
        mutate(commits, index)
        resign_commit(commits[index])
        parser = EngineCommitParser(self.registry)
        verifier = EngineReplayVerifier(self.registry, configuration, digest)
        if whole_chain:
            for position in range(index + 1, len(commits)):
                commits[position]["previous_commit_sha256"] = commits[position - 1]["commit_sha256"]
                resign_commit(commits[position])
            with self.assertRaisesRegex(ValidationError, message):
                verifier.replay([parser.parse(commit) for commit in commits])
            return
        for commit in commits[:index]:
            verifier.accept(parser.parse(commit))
        with self.assertRaisesRegex(ValidationError, message):
            verifier.accept(parser.parse(commits[index]))

    # (1) and (9) The automation checkpoint.

    def test_commit_without_its_checkpoint_upsert_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "ACTION_RESULT")

        def mutate(commits: list[Commit], index: int) -> None:
            commits[index]["mutations"] = [
                item for item in commits[index]["mutations"]
                if item["component_kind"] != "AUTOMATION_CHECKPOINT"
            ]

        self.assert_rejected(
            RC13_RESTART, locate, mutate, "commit does not upsert its complete automation checkpoint"
        )

    # (2) Source cursors.

    def test_cursor_changed_without_a_flush_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                (commit["successor_projection"]["source_checkpoints"].get("usage_events.v1") or {}).get("cursor")
                is not None
                and not any(o["admission_kind"] == "BARRIER_FLUSH" for o in commit["source_observations"])
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            commits[index]["successor_projection"]["source_checkpoints"]["usage_events.v1"]["cursor"] = "1"

        self.assert_rejected(
            RC13_RESTART, locate, mutate, "successor source checkpoints diverge from observation provenance"
        )

    def test_barrier_flush_outside_a_closing_commit_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                "CONDITION_EPOCH_DEACTIVATED" not in _types(commit)
                and any(o["source_id"] == "usage_events.v1" for o in commit["source_observations"])
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            observation = next(
                o for o in commits[index]["source_observations"] if o["source_id"] == "usage_events.v1"
            )
            observation["admission_kind"] = "BARRIER_FLUSH"

        self.assert_rejected(
            RC13_RESTART, locate, mutate, "barrier flush is not one retrospective coverage observation"
        )

    # (3) Retirement reasons.

    def test_barrier_retirement_relabelled_fired_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "TIMER_WAKE"
                and any(
                    event["event_type"] == "CONDITION_EPOCH_DEACTIVATED"
                    and event["fields"]["deactivation_reason"] == "RESOURCE_VECTOR_CHANGED"
                    for event in commit["events"]
                )
                and any(
                    event["event_type"] == "TIMER_RETIRED" and is_automation_timer(event)
                    for event in commit["events"]
                )
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "TIMER_RETIRED" and is_automation_timer(event)
            )
            self.assertEqual("CANCELLED", event["fields"]["retirement_reason"])
            event["fields"]["retirement_reason"] = "FIRED"

        self.assert_rejected(RC13_RESTART, locate, mutate, "timer audit events diverge from reducer intents")

    def test_deadline_stop_reducer_retirement_relabelled_fired_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "TIMER_WAKE" and "STUDY_COMPLETE_REQUESTED" in _types(commit)
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "TIMER_RETIRED" and is_automation_timer(event)
            )
            self.assertEqual("LIFECYCLE_ENDED", event["fields"]["retirement_reason"])
            event["fields"]["retirement_reason"] = "FIRED"

        self.assert_rejected(RC13_RESTART, locate, mutate, "timer audit events diverge from reducer intents")

    def test_deadline_stop_deadline_retirement_relabelled_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "TIMER_WAKE" and "STUDY_COMPLETE_REQUESTED" in _types(commit)
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "TIMER_RETIRED"
                and event["fields"]["producer_key"] == "study-deadline"
            )
            self.assertEqual("FIRED", event["fields"]["retirement_reason"])
            event["fields"]["retirement_reason"] = "LIFECYCLE_ENDED"

        self.assert_rejected(RC13_RESTART, locate, mutate, "study deadline retirement reason mismatch")

    # (4) and (7) Recovery containment.

    def test_recovery_that_rewrites_the_traffic_receipt_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            mutation = next(
                item for item in commits[index]["mutations"]
                if item["component_id"] == "actuator:traffic-shaping.v1" and item["component_kind"] == "RESOURCE"
            )
            mutation["canonical_value"] = next(
                item["canonical_value"]
                for commit in reversed(commits[:index])
                for item in commit["mutations"]
                if item["component_id"] == mutation["component_id"]
                and item["component_kind"] == "RESOURCE"
                and item["canonical_value"] not in {None, mutation["canonical_value"]}
            )

        self.assert_rejected(
            RC13_RESTART, locate, mutate, "traffic profile was not removed before condition deactivation"
        )

    def test_recovery_that_rewrites_a_collector_receipt_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            mutation = next(
                item for item in commits[index]["mutations"]
                if item["component_kind"] == "RESOURCE" and item["component_id"].startswith("collector:")
                and any(
                    prior["component_id"] == item["component_id"]
                    and prior["component_kind"] == "RESOURCE"
                    and prior["canonical_value"] not in {None, item["canonical_value"]}
                    for commit in commits[:index]
                    for prior in commit["mutations"]
                )
            )
            mutation["canonical_value"] = next(
                prior["canonical_value"]
                for commit in reversed(commits[:index])
                for prior in commit["mutations"]
                if prior["component_id"] == mutation["component_id"]
                and prior["component_kind"] == "RESOURCE"
                and prior["canonical_value"] not in {None, mutation["canonical_value"]}
            )

        self.assert_rejected(RC13_RESTART, locate, mutate, "recovery close rewrote a trusted resource receipt")

    def test_recovery_that_records_a_traffic_audit_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            envelope = commits[index]["events"][0]["condition_epoch_id"]
            snapshot = copy.deepcopy(next(
                event
                for commit in commits[:index]
                for event in commit["events"]
                if event["event_type"] == "TRAFFIC_SHAPING_SNAPSHOT"
                and event["condition_epoch_id"] == envelope
            ))
            _append_event(commits[index], snapshot)

        self.assert_rejected(RC13_RESTART, locate, mutate, "recovery commit cannot audit a traffic profile")

    def test_recovery_close_with_another_reason_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "CONDITION_EPOCH_DEACTIVATED"
            )
            self.assertEqual("PROCESS_RECOVERY_UNPROVEN", event["fields"]["deactivation_reason"])
            event["fields"]["deactivation_reason"] = "SAFETY_PAUSED"

        self.assert_rejected(
            RC13_EMULATOR_REBOOT, locate, mutate, "recovery close is not unproven PAUSED containment"
        )

    def test_recovery_with_another_quality_gap_reason_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"] if event["event_type"] == "SOURCE_QUALITY_GAP"
            )
            self.assertEqual("PROCESS_RECOVERY", event["fields"]["reason"])
            event["fields"]["reason"] = "PLATFORM_HISTORY_GAP"

        self.assert_rejected(
            RC13_EMULATOR_REBOOT,
            locate,
            mutate,
            "recovery commit must record exactly one process-recovery quality gap",
        )

    def test_participant_pause_closed_in_another_boot_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "LIFECYCLE_COMMAND"
                and {"STUDY_PAUSE_REQUESTED", "CONDITION_EPOCH_DEACTIVATED"} <= _types(commit)
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            event = _deactivation(commits[index])
            self.assertEqual("PARTICIPANT_PAUSED", event["fields"]["deactivation_reason"])
            boundary = json.loads(event["fields"]["boundary_research_time"])
            _move_event_time(
                event, "another-boot-session", int(boundary["monotonic_time_nanos"]),
                int(boundary["wall_time_utc_millis"]),
            )

        self.assert_rejected(RC13_RESTART, locate, mutate, "condition epoch cannot span a reboot")

    def test_condition_boundary_that_is_not_its_event_time_is_rejected(self) -> None:
        for event_type in ("CONDITION_EPOCH_ACTIVATED", "CONDITION_EPOCH_DEACTIVATED"):
            with self.subTest(event_type=event_type):
                def locate(commits: list[Commit], event_type: str = event_type) -> int:
                    return _first(commits, lambda commit: event_type in _types(commit))

                def mutate(commits: list[Commit], index: int, event_type: str = event_type) -> None:
                    event = next(e for e in commits[index]["events"] if e["event_type"] == event_type)
                    boundary = json.loads(event["fields"]["boundary_research_time"])
                    boundary["wall_time_utc_millis"] = str(int(boundary["wall_time_utc_millis"]) - 1)
                    event["fields"]["boundary_research_time"] = _canonical(boundary)

                self.assert_rejected(
                    RC13_RESTART, locate, mutate, "condition epoch boundary differs from its event time"
                )

    # (F1) The recovery close lies at the recovery instant, whatever committed_at says.

    def test_recovery_close_away_from_the_recovery_instant_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "RECOVERY" and "CONDITION_EPOCH_DEACTIVATED" in _types(commit)
            ))

        for fixture in (RC13_EMULATOR_REBOOT, RC13_RESTART, RC13_UNTRUSTED_REBOOT):
            for boot, elapsed, wall_offset in (
                ("forged-boot-session", 1, 30 * 24 * 3_600_000),
                ("forged-boot-session", 5, 1),
                ("forged-boot-session", 5, -60_000),
            ):
                with self.subTest(fixture=fixture, wall_offset=wall_offset):
                    def mutate(
                        commits: list[Commit], index: int,
                        boot: str = boot, elapsed: int = elapsed, wall_offset: int = wall_offset,
                    ) -> None:
                        event = _deactivation(commits[index])
                        wall = int(event["observed_time"]["wall_time_utc_millis"]) + wall_offset
                        _move_event_time(event, boot, elapsed, wall)

                    self.assert_rejected(
                        fixture, locate, mutate, "recovery close is not at the recovery instant"
                    )

    # (F2) A safety pause whose boundary audit could not read the traffic counters.

    def test_safety_close_without_traffic_audit_keeps_its_exact_shape(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "SAFETY_FAILURE" and "CONDITION_EPOCH_DEACTIVATED" in _types(commit)
            ))

        def relabel_audit_retirement(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "TIMER_RETIRED"
                and event["fields"]["producer_key"].startswith("resource-audit:")
            )
            self.assertEqual("LIFECYCLE_ENDED", event["fields"]["retirement_reason"])
            event["fields"]["retirement_reason"] = "QUALITY_GAP_RESET"

        def another_reason(commits: list[Commit], index: int) -> None:
            event = _deactivation(commits[index])
            self.assertEqual("SAFETY_PAUSED", event["fields"]["deactivation_reason"])
            event["fields"]["deactivation_reason"] = "PARTICIPANT_PAUSED"

        def rewrite_traffic_receipt(commits: list[Commit], index: int) -> None:
            mutation = next(
                item for item in commits[index]["mutations"]
                if item["component_id"] == "actuator:traffic-shaping.v1" and item["component_kind"] == "RESOURCE"
            )
            mutation["canonical_value"] = next(
                item["canonical_value"]
                for commit in reversed(commits[:index])
                for item in commit["mutations"]
                if item["component_id"] == mutation["component_id"]
                and item["component_kind"] == "RESOURCE"
                and item["canonical_value"] not in {None, mutation["canonical_value"]}
            )

        for mutate in (relabel_audit_retirement, another_reason, rewrite_traffic_receipt):
            with self.subTest(mutation=mutate.__name__):
                self.assert_rejected(
                    RC13_EMPTY_FLUSH_AND_REVOKE,
                    locate,
                    mutate,
                    "traffic profile was not removed before condition deactivation",
                )

    # (D5, D6) A safety pause or recovery of a study already PAUSING.

    def test_pausing_to_pausing_request_is_no_transition_only_while_pausing(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: (
                commit["input_kind"] == "SAFETY_FAILURE" and "CONDITION_EPOCH_DEACTIVATED" in _types(commit)
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "STUDY_SAFETY_PAUSE_REQUESTED"
            )
            self.assertEqual("RUNNING", event["fields"]["previous_state"])
            event["fields"]["previous_state"] = "PAUSING"

        self.assert_rejected(
            RC13_EMPTY_FLUSH_AND_REVOKE, locate, mutate,
            "lifecycle event does not identify its reducer transition",
        )

    def test_a_commit_that_re_anchors_the_clock_re_arms_the_retired_deadline(self) -> None:
        def drop_deadline(commits: list[Commit], index: int) -> None:
            commit = commits[index]
            position = next(
                position for position, event in enumerate(commit["events"])
                if event["event_type"] == "TIMER_SCHEDULED"
                and event["fields"]["producer_key"] == "study-deadline"
            )
            _remove_event(commit, position)
            commit["mutations"] = [
                item for item in commit["mutations"] if item["component_kind"] != "STUDY_DEADLINE_TIMER"
            ]

        cases = {
            RC13_RELEASE_FAILS: lambda commit: "STUDY_RESUMED" in _types(commit),
            RC13_DEATH_WHILE_PAUSING: lambda commit: commit["input_kind"] == "RECOVERY",
        }
        for fixture, predicate in cases.items():
            with self.subTest(fixture=fixture):
                self.assert_rejected(
                    fixture,
                    lambda commits, predicate=predicate: _first(commits, predicate),
                    drop_deadline,
                    "started study is missing its durable deadline",
                )

    # (5) One envelope epoch per commit.

    def test_deadline_event_outside_the_commit_epoch_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: any(
                event["source_id"] == "timer.v1"
                and event["fields"]["producer_key"] == "study-deadline"
                and event["condition_epoch_id"] is not None
                for event in commit["events"]
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["source_id"] == "timer.v1" and event["fields"]["producer_key"] == "study-deadline"
            )
            event["condition_epoch_id"] = None

        self.assert_rejected(
            RC13_EMULATOR_COMPLETE, locate, mutate, "commit events do not carry the commit's condition epoch"
        )

    # (6) The deadline.

    def test_participant_pause_that_drops_the_deadline_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: "STUDY_PAUSE_REQUESTED" in _types(commit))

        def mutate(commits: list[Commit], index: int) -> None:
            mutations = commits[index]["mutations"]
            mutations.append({
                "canonical_value": None,
                "component_id": "study-duration",
                "component_kind": "STUDY_DEADLINE_TIMER",
                "operation": "REMOVE",
            })
            mutations.sort(key=lambda item: (COMPONENT_ORDER.index(item["component_kind"]), item["component_id"]))

        self.assert_rejected(RC13_RESTART, locate, mutate, "started study is missing its durable deadline")

    # (A) The preparation bound.

    def test_coverage_before_the_preparation_bound_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: any(
                o["source_id"] == "usage_events.v1" and o["producer_ordinal"] == "0"
                for o in commit["source_observations"]
            ))

        def mutate(commits: list[Commit], index: int) -> None:
            commit = commits[index]
            observation = next(
                o for o in commit["source_observations"]
                if o["source_id"] == "usage_events.v1" and o["producer_ordinal"] == "0"
            )
            old = dict(observation["coverage"])
            observation["coverage"]["start_inclusive"] = str(int(old["start_inclusive"]) - 10_000)
            parser = EngineCommitParser(self.registry)
            covered = [
                parser._event(event)
                for event in commit["events"]
                if observation["first_event_sequence"] is not None
                and int(observation["first_event_sequence"])
                <= int(event["sequence_number"])
                <= int(observation["last_event_sequence"])
            ]
            observation["encoded_sha256"] = _observation_digest(parser._observation(observation), covered)
            for later in commits[index:]:
                checkpoint = later["successor_projection"]["source_checkpoints"].get("usage_events.v1")
                if checkpoint is not None and checkpoint["coverage"] == old:
                    checkpoint["coverage"] = dict(observation["coverage"])

        self.assert_rejected(
            RC13_EMULATOR_COMPLETE,
            locate,
            mutate,
            "retrospective coverage crosses a condition epoch boundary",
            whole_chain=True,
        )

    # (B) Intervention identity.

    def test_survey_whose_scheduled_time_leaves_its_request_is_rejected(self) -> None:
        for offset in (-6 * 3_600_000, 1):
            with self.subTest(offset=offset):
                def locate(commits: list[Commit]) -> int:
                    return _first(commits, lambda commit: "SURVEY_OPENED" in _types(commit))

                def mutate(commits: list[Commit], index: int, offset: int = offset) -> None:
                    event = next(e for e in commits[index]["events"] if e["event_type"] == "SURVEY_OPENED")
                    event["fields"]["scheduled_for_utc_millis"] = str(
                        int(event["fields"]["scheduled_for_utc_millis"]) + offset
                    )

                self.assert_rejected(
                    RC13_RESTART, locate, mutate, "intervention event diverges from its action request"
                )

    # (C) Timer renderings.

    def test_recovery_schedule_of_an_unarmed_generation_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "TIMER_SCHEDULED" and is_automation_timer(event)
            )
            event["fields"]["generation"] = str(int(event["fields"]["generation"]) + 5)

        self.assert_rejected(RC13_RESTART, locate, mutate, "timer audit events diverge from reducer intents")

    def test_rc13_recovery_with_one_duplicate_retirement_removed_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            events = commits[index]["events"]
            seen: set[tuple[str, str]] = set()
            for position, event in enumerate(events):
                if event["event_type"] == "TIMER_RETIRED" and is_automation_timer(event):
                    identity = (event["fields"]["timer_id"], event["fields"]["generation"])
                    if identity in seen:
                        _remove_event(commits[index], position)
                        return
                    seen.add(identity)
            self.fail("the RC13 recovery commit has no duplicate retirement")

        self.assert_rejected(RC13_RESTART, locate, mutate, "timer audit events diverge from reducer intents")

    def test_branch_recovery_retirement_of_another_generation_is_rejected(self) -> None:
        def locate(commits: list[Commit]) -> int:
            return _first(commits, lambda commit: commit["input_kind"] == "RECOVERY")

        def mutate(commits: list[Commit], index: int) -> None:
            event = next(
                event for event in commits[index]["events"]
                if event["event_type"] == "TIMER_RETIRED" and is_automation_timer(event)
            )
            event["fields"]["generation"] = str(int(event["fields"]["generation"]) + 1)

        self.assert_rejected(BRANCH_RESTART, locate, mutate, "timer audit events diverge from reducer intents")


if __name__ == "__main__":
    unittest.main()
