"""Unit rules for the Protocol v1 shapes that the RC13 pilot build and this branch record.

The frozen runtime exports exercise these rules end to end; these tests pin each rule's
accepted and rejected cases one condition at a time.
"""

from __future__ import annotations

import unittest
from types import SimpleNamespace
from typing import Any

from factories import CONFIGURATION_SHA256, checkpoint_component, configuration

from particeps_analysis.engine import (
    ConditionEpoch,
    EngineReplayVerifier,
    ResearchTime,
    SourceCoverage,
    SourceObservation,
    _expected_deadline_retirement_reason,
    _expected_reducer_timer_retirement_reason,
)
from particeps_analysis.errors import ValidationError
from particeps_analysis.registry import EventSourceRegistry

EPOCH = "2f7720d8-e530-45de-868b-15b282abbce2"
NEXT_EPOCH = "0b1a9d4e-5c3f-4e2a-9d1b-7f6e5d4c3b2a"


def event(source: str, kind: str, epoch: str | None = EPOCH, **fields: str) -> Any:
    return SimpleNamespace(
        sequence_number=0,
        source_id=source,
        schema_version=1,
        event_type=kind,
        condition_epoch_id=epoch,
        wire_fields=fields,
    )


def lifecycle(kind: str, previous: str, current: str, epoch: str | None = EPOCH) -> Any:
    return event("study_runtime.v1", kind, epoch, previous_state=previous, current_state=current)


def gap(reason: str) -> Any:
    return event("study_runtime.v1", "SOURCE_QUALITY_GAP", reason=reason, source_id="timer.v1")


def automation_due() -> Any:
    return event("timer.v1", "TIMER_DUE", producer_key="condition:window")


def deadline_due() -> Any:
    return event("timer.v1", "TIMER_DUE", producer_key="study-deadline")


def audit_due() -> Any:
    return event("timer.v1", "TIMER_DUE", producer_key="resource-audit:actuator:traffic-shaping.v1")


def deactivation() -> Any:
    return event(
        "study_condition.v1", "CONDITION_EPOCH_DEACTIVATED", condition_epoch_id=EPOCH
    )


def commit(input_kind: str, *events: Any) -> Any:
    return SimpleNamespace(input_kind=input_kind, events=tuple(events))


def battery_configuration() -> dict:
    value = configuration()
    value["collectors"] = [
        {"id": "battery_state.v1", "profiles": [{"config": {}, "id": "continuous"}], "required": True}
    ]
    value["automations"] = [
        {
            "cases": [{"condition": {"type": "study_session_active"}, "profile_id": "continuous"}],
            "default_profile_id": "continuous",
            "id": "bind-battery",
            "resource": {"id": "battery_state.v1", "kind": "collector"},
            "type": "resource_binding",
        }
    ]
    return value


class ReducerTimerRetirementReasonTest(unittest.TestCase):
    """TIMER_RETIRED.retirement_reason mirrors the runtime call site of each commit."""

    def assert_reason(self, expected: str, value: Any) -> None:
        self.assertEqual(expected, _expected_reducer_timer_retirement_reason(value))

    def test_recovery_resets_before_any_other_rule(self) -> None:
        self.assert_reason(
            "QUALITY_GAP_RESET",
            commit(
                "RECOVERY",
                gap("PROCESS_RECOVERY"),
                lifecycle("STUDY_SAFETY_PAUSE_REQUESTED", "RUNNING", "PAUSING"),
                deactivation(),
                lifecycle("STUDY_SAFETY_PAUSED", "PAUSING", "PAUSED"),
            ),
        )

    def test_safety_failure_and_every_stop_from_running_end_the_lifecycle(self) -> None:
        self.assert_reason(
            "LIFECYCLE_ENDED",
            commit(
                "SAFETY_FAILURE",
                lifecycle("STUDY_SAFETY_PAUSE_REQUESTED", "RUNNING", "PAUSING"),
                lifecycle("STUDY_SAFETY_PAUSED", "PAUSING", "PAUSED"),
            ),
        )
        for event_type in ("STUDY_PAUSE_REQUESTED", "STUDY_COMPLETE_REQUESTED", "STUDY_WITHDRAW_REQUESTED"):
            with self.subTest(event_type=event_type):
                self.assert_reason(
                    "LIFECYCLE_ENDED",
                    commit("LIFECYCLE_COMMAND", lifecycle(event_type, "RUNNING", "PAUSING"), deactivation()),
                )
        # The deadline stop, and the deadline first seen across a wall-clock change.
        self.assert_reason(
            "LIFECYCLE_ENDED",
            commit(
                "TIMER_WAKE",
                deadline_due(),
                lifecycle("STUDY_COMPLETE_REQUESTED", "RUNNING", "PAUSING"),
                deactivation(),
            ),
        )
        self.assert_reason(
            "LIFECYCLE_ENDED",
            commit(
                "TIMER_WAKE",
                gap("WALL_CLOCK_CHANGED"),
                deadline_due(),
                lifecycle("STUDY_COMPLETE_REQUESTED", "RUNNING", "PAUSING"),
                deactivation(),
            ),
        )

    def test_other_quality_gaps_reset(self) -> None:
        self.assert_reason("QUALITY_GAP_RESET", commit("TIMER_WAKE", gap("WALL_CLOCK_CHANGED"), deactivation()))
        self.assert_reason("QUALITY_GAP_RESET", commit("SOURCE_OBSERVATION", gap("PLATFORM_HISTORY_GAP")))

    def test_a_due_automation_timer_fires_only_when_no_epoch_closes(self) -> None:
        self.assert_reason("FIRED", commit("TIMER_WAKE", automation_due()))
        # The commit that opens a resource barrier retires its due timer CANCELLED.
        self.assert_reason("CANCELLED", commit("TIMER_WAKE", automation_due(), deactivation()))
        self.assert_reason("CANCELLED", commit("TIMER_WAKE", audit_due()))

    def test_every_other_commit_cancels(self) -> None:
        for value in (
            commit("RESOURCE_RESULT", lifecycle("STUDY_RUNNING", "ACTIVATING", "RUNNING")),
            commit(
                "LIFECYCLE_COMMAND",
                lifecycle("STUDY_COMPLETE_REQUESTED", "PAUSED", "COMPLETED"),
                lifecycle("STUDY_COMPLETED", "PAUSED", "COMPLETED"),
            ),
            commit(
                "TIMER_WAKE",
                deadline_due(),
                lifecycle("STUDY_COMPLETE_REQUESTED", "PAUSED", "COMPLETED"),
            ),
            commit("RANDOM_SELECTION"),
            commit("SOURCE_OBSERVATION"),
        ):
            with self.subTest(input_kind=value.input_kind):
                self.assert_reason("CANCELLED", value)


class DeadlineRetirementReasonTest(unittest.TestCase):
    def test_deadline_reason_is_fixed_by_the_commit(self) -> None:
        cases = {
            "FIRED": (
                commit("TIMER_WAKE", deadline_due(), lifecycle("STUDY_COMPLETE_REQUESTED", "RUNNING", "PAUSING")),
                commit("TIMER_WAKE", gap("WALL_CLOCK_CHANGED"), deadline_due()),
            ),
            "QUALITY_GAP_RESET": (
                commit("RECOVERY", gap("PROCESS_RECOVERY")),
                commit("TIMER_WAKE", gap("WALL_CLOCK_CHANGED")),
            ),
            "LIFECYCLE_ENDED": (
                commit("LIFECYCLE_COMMAND", lifecycle("STUDY_WITHDRAW_REQUESTED", "RUNNING", "PAUSING")),
            ),
        }
        for expected, values in cases.items():
            for value in values:
                with self.subTest(expected=expected, input_kind=value.input_kind):
                    self.assertEqual(expected, _expected_deadline_retirement_reason(value))

    def test_deadline_retires_in_no_other_commit(self) -> None:
        for value in (
            commit("SAFETY_FAILURE", lifecycle("STUDY_SAFETY_PAUSED", "PAUSING", "PAUSED")),
            commit("RESOURCE_RESULT", lifecycle("STUDY_COMPLETED", "PAUSING", "COMPLETED")),
            commit("TIMER_WAKE", automation_due()),
        ):
            with self.subTest(input_kind=value.input_kind), self.assertRaisesRegex(
                ValidationError, "study deadline retired by an unexpected commit"
            ):
                _expected_deadline_retirement_reason(value)


class AutomationCheckpointCursorTest(unittest.TestCase):
    """The evaluated-through sequence counts reducer inputs, and is 0 exactly in setup states."""

    def verify(
        self,
        *,
        state: str,
        evaluated: int,
        events: int,
        prior: int | None = None,
        next_event_sequence: int = 100,
        lifecycle_state: str = "READY",
        start: int | None = None,
        desired: tuple[tuple[str, str, int, str | None], ...] = (),
        signed: dict | None = None,
    ) -> None:
        component, digest = checkpoint_component(
            evaluated=evaluated,
            lifecycle=lifecycle_state,
            study_start_utc_millis=start,
            desired_resources=desired,
        )
        verifier = EngineReplayVerifier(
            EventSourceRegistry(), signed or configuration(), CONFIGURATION_SHA256
        )
        if prior is not None:
            verifier.checkpoint = {"evaluated_through_sequence": prior}
        verifier._verify_checkpoint(
            SimpleNamespace(
                resulting_checkpoint_sha256=digest,
                successor_projection={"state": state, "next_event_sequence": next_event_sequence},
                events=tuple(range(events)),
            ),
            {("AUTOMATION_CHECKPOINT", "main"): component},
        )

    def test_setup_commits_carry_the_empty_checkpoint(self) -> None:
        for state in ("IMPORTED", "CONFIG_VERIFIED", "CONSENT_PENDING", "ACCESS_SETUP", "READY"):
            with self.subTest(state=state):
                self.verify(state=state, evaluated=0, events=0, signed=battery_configuration())
        with self.assertRaisesRegex(ValidationError, "pre-start automation checkpoint is not the empty"):
            self.verify(
                state="READY",
                evaluated=0,
                events=0,
                desired=(("COLLECTOR", "battery_state.v1", 1, "continuous"),),
                signed=battery_configuration(),
            )
        with self.assertRaisesRegex(ValidationError, "pre-start automation checkpoint is not the empty"):
            self.verify(state="READY", evaluated=0, events=0, lifecycle_state="ACTIVATING", start=1)

    def test_the_cursor_is_zero_exactly_in_setup_states(self) -> None:
        with self.assertRaisesRegex(ValidationError, "cursor contradicts the lifecycle state"):
            self.verify(state="READY", evaluated=1, events=1)
        with self.assertRaisesRegex(ValidationError, "cursor contradicts the lifecycle state"):
            self.verify(state="ACTIVATING", evaluated=0, events=1)

    def test_evaluated_commits_carry_the_complete_desired_vector(self) -> None:
        desired = (("COLLECTOR", "battery_state.v1", 1, "continuous"),)
        self.verify(
            state="ACTIVATING", evaluated=1, events=1, lifecycle_state="ACTIVATING", start=1,
            desired=desired, signed=battery_configuration(),
        )
        with self.assertRaisesRegex(ValidationError, "incomplete desired resource vector"):
            self.verify(
                state="ACTIVATING", evaluated=1, events=1, lifecycle_state="ACTIVATING", start=1,
                signed=battery_configuration(),
            )

    def test_the_cursor_advances_by_at_most_the_commit_event_count(self) -> None:
        self.verify(state="RUNNING", evaluated=12, events=3, prior=10, lifecycle_state="RUNNING", start=1)
        self.verify(state="RUNNING", evaluated=10, events=0, prior=10, lifecycle_state="RUNNING", start=1)
        with self.assertRaisesRegex(ValidationError, "not causally bounded"):
            self.verify(state="RUNNING", evaluated=9, events=3, prior=10, lifecycle_state="RUNNING", start=1)
        with self.assertRaisesRegex(ValidationError, "not causally bounded"):
            self.verify(state="RUNNING", evaluated=14, events=3, prior=10, lifecycle_state="RUNNING", start=1)
        # Commit 1 starts from the known prior cursor 0.
        with self.assertRaisesRegex(ValidationError, "not causally bounded"):
            self.verify(state="ACTIVATING", evaluated=3, events=2, lifecycle_state="ACTIVATING", start=1)
        with self.assertRaisesRegex(ValidationError, "evaluated beyond durable events"):
            self.verify(
                state="RUNNING", evaluated=12, events=3, prior=10, next_event_sequence=12,
                lifecycle_state="RUNNING", start=1,
            )


class CommitEnvelopeEpochTest(unittest.TestCase):
    def verifier(self, active: str | None) -> EngineReplayVerifier:
        verifier = EngineReplayVerifier(EventSourceRegistry(), configuration(), CONFIGURATION_SHA256)
        verifier.active_epoch = None if active is None else SimpleNamespace(id=active)
        return verifier

    def test_every_event_carries_the_predecessor_epoch_or_the_activated_one(self) -> None:
        activation = event(
            "study_condition.v1", "CONDITION_EPOCH_ACTIVATED", NEXT_EPOCH, condition_epoch_id=NEXT_EPOCH
        )
        closing = commit(
            "LIFECYCLE_COMMAND",
            lifecycle("STUDY_COMPLETE_REQUESTED", "RUNNING", "PAUSING"),
            deactivation(),
            event("timer.v1", "TIMER_RETIRED", producer_key="study-deadline"),
        )
        self.verifier(EPOCH)._verify_commit_envelope_epoch(closing)
        self.verifier(None)._verify_commit_envelope_epoch(
            commit("RESOURCE_RESULT", activation, event("study_runtime.v1", "STUDY_RUNNING", NEXT_EPOCH))
        )
        self.verifier(None)._verify_commit_envelope_epoch(
            commit("LIFECYCLE_COMMAND", lifecycle("STUDY_STARTED", "READY", "ACTIVATING", None))
        )
        for value, active in (
            (commit("LIFECYCLE_COMMAND", event("timer.v1", "TIMER_RETIRED", None)), EPOCH),
            (commit("RESOURCE_RESULT", activation, event("study_runtime.v1", "STUDY_RUNNING", EPOCH)), None),
            (commit("TIMER_WAKE", event("timer.v1", "TIMER_DUE", EPOCH)), None),
        ):
            with self.subTest(input_kind=value.input_kind), self.assertRaisesRegex(
                ValidationError, "commit events do not carry the commit's condition epoch"
            ):
                self.verifier(active)._verify_commit_envelope_epoch(value)

    def test_a_commit_records_at_most_one_epoch_transition(self) -> None:
        activation = event(
            "study_condition.v1", "CONDITION_EPOCH_ACTIVATED", NEXT_EPOCH, condition_epoch_id=NEXT_EPOCH
        )
        with self.assertRaisesRegex(ValidationError, "more than one condition epoch transition"):
            self.verifier(EPOCH)._verify_commit_envelope_epoch(
                commit("TIMER_WAKE", deactivation(), activation)
            )


class InterventionEpochTest(unittest.TestCase):
    def test_an_intervention_belongs_to_its_request_by_identity(self) -> None:
        verifier = EngineReplayVerifier(EventSourceRegistry(), configuration(), CONFIGURATION_SHA256)
        occurrence = "d" * 64
        verifier.action_occurrences[occurrence] = ("survey-day-3", "survey-intervention", "1000", EPOCH)
        fields = {
            "intervention_id": "survey-intervention",
            "occurrence_id": occurrence,
            "scheduled_for_utc_millis": "1000",
            "trigger_id": "survey-day-3",
        }
        # A request whose logical time precedes the event's envelope epoch still owns it.
        opened = event("interventions.v1", "SURVEY_OPENED", NEXT_EPOCH, **fields)
        self.assertEqual(EPOCH, verifier._intervention_epoch(opened))
        for name, value in (
            ("occurrence_id", "e" * 64),
            ("trigger_id", "survey-day-4"),
            ("intervention_id", "another-intervention"),
            ("scheduled_for_utc_millis", "1001"),
        ):
            with self.subTest(field=name), self.assertRaisesRegex(
                ValidationError, "intervention event diverges from its action request"
            ):
                verifier._intervention_epoch(
                    event("interventions.v1", "SURVEY_OPENED", NEXT_EPOCH, **(fields | {name: value}))
                )


class RecoveryQualityGapTest(unittest.TestCase):
    def test_recovery_records_exactly_one_process_recovery_gap(self) -> None:
        verifier = EngineReplayVerifier(EventSourceRegistry(), configuration(), CONFIGURATION_SHA256)
        projection = {"source_checkpoints": {}}
        verifier.previous_projection = projection

        def recovery(*events: Any) -> Any:
            return SimpleNamespace(
                input_kind="RECOVERY",
                consumed_pending_input_sha256=None,
                source_observations=(),
                events=tuple(events),
                successor_projection=projection,
            )

        verifier._verify_observations(recovery(gap("PROCESS_RECOVERY")))
        for events in (
            (),
            (gap("PLATFORM_HISTORY_GAP"),),
            (gap("PROCESS_RECOVERY"), gap("PROCESS_RECOVERY")),
            (gap("PROCESS_RECOVERY"), gap("WALL_CLOCK_CHANGED")),
        ):
            with self.subTest(reasons=[item.wire_fields["reason"] for item in events]), self.assertRaisesRegex(
                ValidationError, "exactly one process-recovery quality gap"
            ):
                verifier._verify_observations(recovery(*events))
        with self.assertRaisesRegex(ValidationError, "process-recovery quality gap requires RECOVERY"):
            verifier._verify_observations(
                SimpleNamespace(
                    input_kind="SOURCE_OBSERVATION",
                    consumed_pending_input_sha256=None,
                    source_observations=(),
                    events=(gap("PROCESS_RECOVERY"),),
                    successor_projection=projection,
                )
            )


class RetrospectiveCoverageIntervalTest(unittest.TestCase):
    """Coverage never runs backwards and is empty only as the boundary flush at the close."""

    ACTIVATED = ResearchTime(10_000, 5_000_000, "boot-one")
    CLOSED = ResearchTime(20_000, 15_000_000, "boot-one")

    def check(
        self,
        admission: str,
        start: str,
        end: str,
        *,
        events: int = 0,
        basis: str = "SOURCE_WALL_TIME",
        closed: bool = True,
    ) -> None:
        verifier = EngineReplayVerifier(EventSourceRegistry(), configuration(), CONFIGURATION_SHA256)
        epoch = ConditionEpoch(EPOCH, CONFIGURATION_SHA256, "b" * 64, self.ACTIVATED)
        verifier.known_epochs[EPOCH] = epoch
        verifier.epoch_preparation_bounds[EPOCH] = ResearchTime(9_000, 4_000_000, "boot-one")
        if closed:
            verifier.closed_epochs[EPOCH] = (epoch, self.CLOSED)
        verifier.observations_by_epoch[EPOCH] = [
            SourceObservation(
                1, "usage_events.v1", 1, 1, admission, 0, EPOCH, events,
                1 if events else None, events if events else None,
                SourceCoverage(basis, start, end), "c" * 64,
            )
        ]
        verifier._verify_closed_observation_coverage()

    def test_the_barrier_flush_at_the_close_may_be_empty(self) -> None:
        self.check("BARRIER_FLUSH", "20000", "20000")
        self.check("BARRIER_FLUSH", "19000", "20000", events=2)
        self.check("NORMAL", "9000", "20000")

    def test_every_other_empty_interval_is_rejected(self) -> None:
        for case in (
            {"admission": "NORMAL", "start": "20000", "end": "20000"},
            {"admission": "BARRIER_FLUSH", "start": "15000", "end": "15000"},
            {"admission": "BARRIER_FLUSH", "start": "20000", "end": "20000", "events": 1},
            {
                "admission": "BARRIER_FLUSH", "start": "15000000", "end": "15000000",
                "basis": "SOURCE_MONOTONIC_TIME",
            },
            {"admission": "BARRIER_FLUSH", "start": "20000", "end": "20000", "closed": False},
        ):
            with self.subTest(**case), self.assertRaisesRegex(
                ValidationError, "retrospective coverage is empty outside a boundary flush"
            ):
                self.check(**case)

    def test_coverage_never_runs_backwards_or_leaves_its_epoch(self) -> None:
        for start, end in (("20000", "19999"), ("8999", "12000"), ("19000", "20001")):
            with self.subTest(start=start, end=end), self.assertRaisesRegex(
                ValidationError, "retrospective coverage crosses a condition epoch boundary"
            ):
                self.check("BARRIER_FLUSH", start, end)


if __name__ == "__main__":
    unittest.main()
