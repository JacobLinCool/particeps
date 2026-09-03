"""Independent, deterministic Protocol v1 automation reducer."""

from __future__ import annotations

from copy import deepcopy
from typing import Any

from .automation_checkpoint import action_id, deterministic_digest, timer_id
from .automation_model import (
    ActionRequest,
    AutomationAudit,
    AutomationCheckpoint,
    AutomationEvent,
    CompiledAutomationProgram,
    CooldownMark,
    DesiredProfile,
    DurableTimer,
    MaterializedTimerSummary,
    ReducerClock,
    ReducerInput,
    ReductionResult,
    ResearchTime,
    ResourceKey,
    SequencePartial,
    TimerIntent,
    TimerProductionRequest,
    TimerTarget,
    WindowEntry,
)
from .automation_timers import require_valid_schedule_timer
from .errors import ValidationError
from .registry import decode_event_wire_field, decode_predicate_field

_NANO = 1_000_000_000
_ACTIVE = {"ACTIVATING", "RUNNING"}
_RESET = {"PAUSING", "PAUSED", "COMPLETED", "WITHDRAWN"}
_CONDITION_PREFIX = "condition:"


def reduce_automation_batch(
    program: CompiledAutomationProgram,
    checkpoint: AutomationCheckpoint,
    inputs: list[ReducerInput] | tuple[ReducerInput, ...],
) -> ReductionResult:
    if not inputs:
        raise ValidationError("automation reducer batch is empty")
    state = _State(checkpoint)
    actions: list[ActionRequest] = []
    audits: list[AutomationAudit] = []
    intents: list[TimerIntent] = []
    for index, input_value in enumerate(inputs):
        if input_value.sequence_number != checkpoint.evaluated_through_sequence + index + 1:
            raise ValidationError("automation reducer input is not contiguous")
        state.begin(input_value)
        due_kind, due_timer = "NONE", None
        if input_value.type == "EVENT":
            if _required(input_value.event).sequence_number != input_value.sequence_number:
                raise ValidationError("automation nested event sequence diverges")
        elif input_value.type == "LIFECYCLE":
            state.apply_lifecycle(_required(input_value.state), input_value.clock, intents)
        elif input_value.type == "TIMER_DUE":
            due_kind, due_timer = state.accept_due(input_value, intents)
        elif input_value.type == "TIMER_MATERIALIZED":
            state.materialize(program, input_value, intents)
        elif input_value.type == "QUALITY_GAP":
            state.reset_session(intents)
        elif input_value.type == "CLOCK_DISCONTINUITY":
            state.reset_session(intents)
            state.reset_calendar(intents)
            state.restart_resources(program, input_value.restart_resources)
        else:
            raise ValidationError("unknown automation reducer input")
        if due_kind == "STALE":
            automation = next(
                (
                    item
                    for item in program.occurrence_automations
                    if item["id"] == input_value.automation_id
                    and item["trigger"]["type"] == "schedule"
                ),
                None,
            )
            if automation is not None:
                audits.append(
                    AutomationAudit(
                        automation["id"],
                        False,
                        "STALE_TIMER",
                        f"timer:{input_value.timer_id}",
                    )
                )
        for automation in program.occurrence_automations:
            root = f"occurrence:{automation['id']}"
            guard = (
                state.condition(
                    program,
                    automation["guard"],
                    f"{root}:guard",
                    input_value,
                    intents,
                    automation["id"],
                )
                if automation["guard"] is not None
                else True
            )
            matches = (
                state.trigger(
                    program,
                    automation,
                    root,
                    input_value,
                    due_timer,
                    intents,
                )
                if state.lifecycle == "RUNNING"
                and input_value.type not in {"QUALITY_GAP", "CLOCK_DISCONTINUITY"}
                else []
            )
            for match in matches:
                request, audit = state.request_action(
                    program, automation, match, guard, input_value.clock
                )
                audits.append(audit)
                if request is not None:
                    actions.append(request)
        for binding in program.resource_bindings:
            for case_index, case in enumerate(binding["cases"]):
                path = f"binding:{binding['id']}:case:{case_index}"
                state.latest[path] = state.condition(
                    program,
                    case["condition"],
                    path,
                    input_value,
                    intents,
                    binding["id"],
                )
        state.finish(input_value)
    changes = state.reconcile(program)
    production = state.production_requests(program, inputs[-1].clock)
    return ReductionResult(
        state.freeze(),
        tuple(actions),
        tuple(_dedupe_sort_intents(intents)),
        tuple(production),
        changes,
        tuple(audits),
    )


class _State:
    def __init__(self, checkpoint: AutomationCheckpoint) -> None:
        copied = deepcopy(checkpoint)
        self.evaluated = copied.evaluated_through_sequence
        self.lifecycle = copied.lifecycle
        self.study_start = copied.study_start_utc_millis
        self.last_active = copied.last_active_elapsed_nanos
        self.last_calendar = copied.last_calendar_elapsed_nanos
        self.latch = copied.latch_values
        self.presence = copied.presence_keys
        self.held = copied.held_since_nanos
        self.prior = copied.prior_condition_values
        self.windows = copied.windows
        self.sequences = copied.sequences
        self.counts = copied.activation_counts
        self.cooldowns = copied.cooldown_marks
        self.desired = copied.desired_resources
        self.timers = copied.timers
        self.generations = copied.timer_generations
        self.materialized = copied.materialized_timers
        self.latest: dict[str, bool] = {}
        self.forced_restarts: set[ResourceKey] = set()
        self.current_sequence = 0

    def begin(self, input_value: ReducerInput) -> None:
        if (
            input_value.clock.active_elapsed_nanos < self.last_active
            or input_value.clock.calendar_elapsed_nanos < self.last_calendar
        ):
            raise ValidationError("automation clock moved backward")
        self.current_sequence = input_value.sequence_number
        self.latest.clear()

    def finish(self, input_value: ReducerInput) -> None:
        self.evaluated = input_value.sequence_number
        self.last_active = input_value.clock.active_elapsed_nanos
        self.last_calendar = input_value.clock.calendar_elapsed_nanos

    def apply_lifecycle(
        self, state: str, clock: ReducerClock, intents: list[TimerIntent]
    ) -> None:
        if state != self.lifecycle and state not in _allowed_destinations(self.lifecycle):
            raise ValidationError("automation lifecycle transition is invalid")
        self.lifecycle = state
        if state == "ACTIVATING" and self.study_start is None:
            self.study_start = clock.now.wall_time_utc_millis
        if state in _RESET:
            self.reset_session(intents)

    def reset_session(self, intents: list[TimerIntent]) -> None:
        self.latch.clear()
        self.presence.clear()
        self.held.clear()
        self.prior.clear()
        self.windows.clear()
        self.sequences.clear()
        for timer in list(self.timers.values()):
            if timer.producer_key.startswith(_CONDITION_PREFIX):
                intents.append(TimerIntent("RETIRE", timer_id=timer.id, generation=timer.generation))
                del self.timers[timer.id]

    def reset_calendar(self, intents: list[TimerIntent]) -> None:
        retired = [timer for timer in self.timers.values() if timer.target.type == "CALENDAR_UTC"]
        for timer in retired:
            intents.append(TimerIntent("RETIRE", timer_id=timer.id, generation=timer.generation))
            del self.timers[timer.id]
        keys = {timer.producer_key for timer in retired}
        for automation in list(self.materialized):
            kept = [item for item in self.materialized[automation] if item.producer_key not in keys]
            if kept:
                self.materialized[automation] = kept
            else:
                del self.materialized[automation]

    def restart_resources(
        self,
        program: CompiledAutomationProgram,
        resources: tuple[ResourceKey, ...],
    ) -> None:
        declared = {
            ResourceKey(item["resource"]["kind"].upper(), item["resource"]["id"])
            for item in program.resource_bindings
        }
        if any(resource not in declared for resource in resources):
            raise ValidationError("clock discontinuity references an undeclared resource")
        self.forced_restarts.update(resources)

    def accept_due(
        self, input_value: ReducerInput, intents: list[TimerIntent]
    ) -> tuple[str, DurableTimer | None]:
        timer = self.timers.get(_required(input_value.timer_id))
        if timer is None or timer.generation != input_value.generation:
            intents.append(
                TimerIntent(
                    "RETIRE",
                    timer_id=input_value.timer_id,
                    generation=input_value.generation,
                )
            )
            return "STALE", None
        if (
            timer.automation_id != input_value.automation_id
            or timer.causal_sequence != input_value.causal_sequence
            or timer.target != input_value.target
        ):
            raise ValidationError("automation due timer diverges")
        logical_due = _required(input_value.logical_due)
        if logical_due != _timer_audit_coordinate(timer):
            raise ValidationError("automation due timer logical target diverges")
        if self.lifecycle != "RUNNING":
            return "DEFERRED", None
        if not _is_due(timer.target, input_value.clock):
            raise ValidationError("automation timer fired before its target")
        del self.timers[timer.id]
        intents.append(TimerIntent("RETIRE", timer_id=timer.id, generation=timer.generation))
        summaries = self.materialized.get(timer.automation_id)
        if summaries is not None:
            self.materialized[timer.automation_id] = [
                MaterializedTimerSummary(
                    item.producer_key,
                    item.selected_utc_millis,
                    True if item.producer_key == timer.producer_key else item.terminal,
                )
                for item in summaries
            ]
        return "ACCEPTED", timer

    def materialize(
        self,
        program: CompiledAutomationProgram,
        input_value: ReducerInput,
        intents: list[TimerIntent],
    ) -> None:
        timer = _required(input_value.timer)
        if self.lifecycle != "RUNNING":
            raise ValidationError("automation timer materialized while not running")
        automation = next(
            (item for item in program.occurrence_automations if item["id"] == timer.automation_id),
            None,
        )
        if automation is None or automation["trigger"]["type"] != "schedule":
            raise ValidationError("automation timer references a non-schedule")
        if timer.id != timer_id(program.configuration_sha256, timer.automation_id, timer.producer_key):
            raise ValidationError("automation timer identity diverges")
        existing = self.timers.get(timer.id)
        if existing is not None:
            if existing != timer:
                raise ValidationError("automation timer materialization conflicts")
            return
        if timer.generation != self.generations.get(timer.automation_id, 0) + 1:
            raise ValidationError("automation timer generation is stale")
        if any(
            item.producer_key == timer.producer_key
            for item in self.materialized.get(timer.automation_id, [])
        ):
            raise ValidationError("automation timer producer key was reused")
        if timer.causal_sequence > self.evaluated:
            raise ValidationError("automation timer causal sequence was not evaluated")
        if self.study_start is None:
            raise ValidationError("automation timer has no study start")
        require_valid_schedule_timer(
            TimerProductionRequest(
                program.configuration_sha256,
                automation,
                automation["trigger"]["schedule"],
                input_value.clock,
                self.study_start,
                self.study_start + program.study_duration_seconds * 1_000,
                timer.causal_sequence,
                self.generations.get(timer.automation_id, 0),
                self.lifecycle,
                None,
                tuple(self.materialized.get(timer.automation_id, [])),
            ),
            timer,
        )
        self.timers[timer.id] = timer
        self.generations[timer.automation_id] = timer.generation
        self.materialized.setdefault(timer.automation_id, []).append(
            MaterializedTimerSummary(
                timer.producer_key,
                timer.logical_deadline_utc_millis or 0,
                False,
            )
        )
        intents.append(TimerIntent("SCHEDULE", timer=timer))

    def trigger(
        self,
        program: CompiledAutomationProgram,
        automation: dict[str, Any],
        root: str,
        input_value: ReducerInput,
        due_timer: DurableTimer | None,
        intents: list[TimerIntent],
    ) -> list[dict[str, Any]]:
        trigger = automation["trigger"]
        kind = trigger["type"]
        if kind == "event_match":
            event = input_value.event if input_value.type == "EVENT" else None
            return (
                [_event_match(_required(event), trigger["evaluation_clock"], "event_match")]
                if event is not None and _matches(program, trigger["selector"], event)
                else []
            )
        if kind == "sequence":
            return self._sequence(program, automation["id"], trigger, input_value)
        if kind == "window_threshold":
            path = f"{root}:trigger:window"
            value = self._window(program, path, trigger, input_value, intents, automation["id"])
            edge = f"{root}:trigger:window-edge"
            previous = self.prior.get(edge, False)
            self.prior[edge] = value
            entries = self.windows.get(path, [])
            event = input_value.event if input_value.type == "EVENT" else None
            return (
                [
                    {
                        "causal_identity": f"range:{entries[0].sequence_number}:{event.sequence_number}",
                        "logical_time": _event_time(event, trigger["evaluation_clock"]),
                        "logical_deadline_utc_millis": None,
                        "trigger_kind": "window_threshold",
                    }
                ]
                if not previous and value and event is not None and entries
                else []
            )
        if kind == "condition_rising_edge":
            path = f"{root}:trigger:condition"
            value = self.condition(
                program,
                trigger["condition"],
                path,
                input_value,
                intents,
                automation["id"],
            )
            edge = f"{path}-edge"
            previous = self.prior.get(edge, False)
            self.prior[edge] = value
            return [_condition_match(input_value, due_timer)] if not previous and value else []
        return (
            [_timer_match(input_value, due_timer)]
            if due_timer is not None
            and due_timer.automation_id == automation["id"]
            and not due_timer.producer_key.startswith(_CONDITION_PREFIX)
            else []
        )

    def condition(
        self,
        program: CompiledAutomationProgram,
        condition: dict[str, Any],
        path: str,
        input_value: ReducerInput,
        intents: list[TimerIntent],
        automation_id: str,
    ) -> bool:
        kind = condition["type"]
        if kind == "study_session_active":
            return self.lifecycle in _ACTIVE
        if kind == "event_latch":
            event = input_value.event if input_value.type == "EVENT" else None
            if event is not None:
                reset = any(_matches(program, matcher, event) for matcher in condition["reset_when"])
                set_value = any(_matches(program, matcher, event) for matcher in condition["set_when"])
                if reset:
                    self.latch[path] = False
                elif set_value:
                    self.latch[path] = True
            return self.latch.get(path, False)
        if kind == "keyed_presence":
            keys = self.presence.setdefault(path, set())
            event = input_value.event if input_value.type == "EVENT" else None
            if event is not None:
                exits = any(_matches(program, matcher, event) for matcher in condition["exit_when"])
                enters = any(_matches(program, matcher, event) for matcher in condition["enter_when"])
                key = event.fields.get(condition["key_field"])
                if (exits or enters) and key is None:
                    raise ValidationError("automation presence key is missing")
                if exits:
                    keys.discard(key)
                elif enters:
                    if len(keys) >= 256 and key not in keys:
                        raise ValidationError("automation presence key bound exceeded")
                    keys.add(_required(key))
            return bool(keys)
        if kind == "held_for":
            child = self.condition(
                program,
                condition["condition"],
                f"{path}:child",
                input_value,
                intents,
                automation_id,
            )
            now = _duration_nanos(condition["clock"], input_value.clock)
            if not child:
                self.held.pop(path, None)
                self._retire_condition(path, intents)
                return False
            since = self.held.setdefault(path, now)
            due = since + condition["duration_seconds"] * _NANO
            if now >= due:
                self._retire_condition(path, intents)
                return True
            self._ensure_condition(program, automation_id, path, condition["clock"], due, intents)
            return False
        if kind == "elapsed_at_least":
            now = _duration_nanos(condition["clock"], input_value.clock)
            due = condition["duration_seconds"] * _NANO
            if now >= due:
                self._retire_condition(path, intents)
                return True
            self._ensure_condition(program, automation_id, path, condition["clock"], due, intents)
            return False
        if kind == "window_threshold":
            return self._window(program, path, condition, input_value, intents, automation_id)
        if kind == "all":
            values = [
                self.condition(program, child, f"{path}:{index}", input_value, intents, automation_id)
                for index, child in enumerate(condition["conditions"])
            ]
            return all(values)
        if kind == "any":
            # Evaluate every child: Kotlin mapIndexed(...).any executes all children first.
            values = [
                self.condition(program, child, f"{path}:{index}", input_value, intents, automation_id)
                for index, child in enumerate(condition["conditions"])
            ]
            return any(values)
        if kind == "not":
            return not self.condition(
                program,
                condition["condition"],
                f"{path}:not",
                input_value,
                intents,
                automation_id,
            )
        raise ValidationError("unknown compiled automation condition")

    def request_action(
        self,
        program: CompiledAutomationProgram,
        automation: dict[str, Any],
        match: dict[str, Any],
        guard: bool,
        clock: ReducerClock,
    ) -> tuple[ActionRequest | None, AutomationAudit]:
        count = self.counts.get(automation["id"], 0)
        suppression = None
        if count >= automation["maximum_activations"]:
            suppression = "MAXIMUM_ACTIVATIONS"
        elif not guard:
            suppression = "GUARD_FALSE"
        elif self._cooldown_active(automation, clock):
            suppression = "COOLDOWN"
        elif (
            match["logical_time"].wall_time_utc_millis
            + automation["availability_seconds"] * 1000
            <= clock.now.wall_time_utc_millis
        ):
            suppression = "EXPIRED"
        audit = AutomationAudit(
            automation["id"], True, suppression, match["causal_identity"]
        )
        if suppression is not None:
            return None, audit
        deadline = match["logical_deadline_utc_millis"]
        identifier = action_id(
            program.configuration_sha256,
            automation["id"],
            automation["intervention_id"],
            match["trigger_kind"],
            match["causal_identity"],
            "" if deadline is None else str(deadline),
        )
        study_deadline = _required(self.study_start) + program.study_duration_seconds * 1000
        expires = min(
            match["logical_time"].wall_time_utc_millis
            + automation["availability_seconds"] * 1000,
            study_deadline,
        )
        self.counts[automation["id"]] = count + 1
        self.cooldowns[automation["id"]] = CooldownMark(
            clock.active_elapsed_nanos, clock.calendar_elapsed_nanos
        )
        return (
            ActionRequest(
                identifier,
                automation["id"],
                automation["intervention_id"],
                match["causal_identity"],
                deadline,
                expires,
            ),
            audit,
        )

    def reconcile(
        self, program: CompiledAutomationProgram
    ) -> dict[ResourceKey, DesiredProfile]:
        changes = {}
        bindings = sorted(
            program.resource_bindings,
            key=lambda item: (item["resource"]["kind"], item["resource"]["id"]),
        )
        for binding in bindings:
            selected = None
            if self.lifecycle in _ACTIVE:
                selected_case = next(
                    (
                        case
                        for index, case in enumerate(binding["cases"])
                        if self.latest[f"binding:{binding['id']}:case:{index}"]
                    ),
                    None,
                )
                selected = (
                    selected_case["profile_id"]
                    if selected_case is not None
                    else binding["default_profile_id"]
                )
            key = ResourceKey(binding["resource"]["kind"].upper(), binding["resource"]["id"])
            previous = self.desired.get(key)
            force_restart = (
                key in self.forced_restarts
                and previous is not None
                and previous.profile_id is not None
                and selected is not None
            )
            if previous is None or previous.profile_id != selected or force_restart:
                desired = DesiredProfile(1 if previous is None else previous.generation + 1, selected)
                self.desired[key] = desired
                changes[key] = desired
        return changes

    def production_requests(
        self, program: CompiledAutomationProgram, clock: ReducerClock
    ) -> list[TimerProductionRequest]:
        if self.lifecycle != "RUNNING" or self.study_start is None:
            return []
        deadline = self.study_start + program.study_duration_seconds * 1000
        result = []
        for automation in program.occurrence_automations:
            trigger = automation["trigger"]
            if trigger["type"] != "schedule" or self.counts.get(automation["id"], 0) >= automation["maximum_activations"]:
                continue
            pending = next(
                (
                    timer
                    for timer in self.timers.values()
                    if timer.automation_id == automation["id"]
                    and not timer.producer_key.startswith(_CONDITION_PREFIX)
                ),
                None,
            )
            result.append(
                TimerProductionRequest(
                    program.configuration_sha256,
                    automation,
                    trigger["schedule"],
                    clock,
                    self.study_start,
                    deadline,
                    self.evaluated,
                    self.generations.get(automation["id"], 0),
                    self.lifecycle,
                    pending,
                    tuple(self.materialized.get(automation["id"], [])),
                )
            )
        return result

    def freeze(self) -> AutomationCheckpoint:
        return AutomationCheckpoint(
            self.evaluated,
            self.lifecycle,
            self.study_start,
            self.last_active,
            self.last_calendar,
            dict(sorted(self.latch.items())),
            {key: set(value) for key, value in sorted(self.presence.items()) if value},
            dict(sorted(self.held.items())),
            dict(sorted(self.prior.items())),
            {key: list(value) for key, value in sorted(self.windows.items()) if value},
            {key: list(value) for key, value in sorted(self.sequences.items()) if value},
            dict(sorted(self.counts.items())),
            dict(sorted(self.cooldowns.items())),
            dict(sorted(self.desired.items(), key=lambda item: item[0].sort_key())),
            dict(sorted(self.timers.items())),
            dict(sorted(self.generations.items())),
            {key: list(value) for key, value in sorted(self.materialized.items())},
        )

    def _sequence(
        self,
        program: CompiledAutomationProgram,
        automation_id: str,
        trigger: dict[str, Any],
        input_value: ReducerInput,
    ) -> list[dict[str, Any]]:
        if input_value.type != "EVENT":
            return []
        event = _required(input_value.event)
        time = _event_time(event, trigger["evaluation_clock"])
        path = f"occurrence:{automation_id}:trigger:sequence"
        retained = self.sequences.setdefault(path, [])
        next_values = []
        found = []
        window = trigger["within_seconds"] * _NANO
        for partial in retained:
            if partial.boot_session_id != time.boot_session_id:
                continue
            if time.elapsed_realtime_nanos < partial.first_time_nanos:
                raise ValidationError("automation sequence time moved backward")
            if time.elapsed_realtime_nanos - partial.first_time_nanos > window:
                continue
            if _matches(program, trigger["steps"][partial.next_step], event):
                if partial.next_step == len(trigger["steps"]) - 1:
                    found.append(
                        {
                            "causal_identity": f"range:{partial.first_sequence_number}:{event.sequence_number}",
                            "logical_time": time,
                            "logical_deadline_utc_millis": None,
                            "trigger_kind": "sequence",
                        }
                    )
                else:
                    next_values.append(
                        SequencePartial(
                            partial.next_step + 1,
                            partial.first_sequence_number,
                            event.sequence_number,
                            partial.first_time_nanos,
                            partial.boot_session_id,
                        )
                    )
            else:
                next_values.append(partial)
        if _matches(program, trigger["steps"][0], event):
            next_values.append(
                SequencePartial(
                    1,
                    event.sequence_number,
                    event.sequence_number,
                    time.elapsed_realtime_nanos,
                    time.boot_session_id,
                )
            )
        if len(next_values) > 4096:
            raise ValidationError("automation sequence bound exceeded")
        self.sequences[path] = next_values
        return found

    def _window(
        self,
        program: CompiledAutomationProgram,
        path: str,
        value: dict[str, Any],
        input_value: ReducerInput,
        intents: list[TimerIntent],
        automation_id: str,
    ) -> bool:
        event = input_value.event if input_value.type == "EVENT" else None
        reference = (
            _event_time(event, value["evaluation_clock"])
            if event is not None
            else input_value.clock.now
        )
        earliest = reference.elapsed_realtime_nanos - value["window_seconds"] * _NANO
        entries = [
            entry
            for entry in self.windows.get(path, [])
            if entry.boot_session_id == reference.boot_session_id
            and entry.time_nanos > earliest
        ]
        if event is not None:
            if entries and reference.elapsed_realtime_nanos < entries[-1].time_nanos:
                raise ValidationError("automation window time moved backward")
            if _matches(program, value["selector"], event):
                numeric = (
                    1
                    if value["aggregate"]["type"] == "count"
                    else int(event.fields[value["aggregate"]["field"]])
                )
                entries.append(
                    WindowEntry(
                        event.sequence_number,
                        reference.elapsed_realtime_nanos,
                        reference.boot_session_id,
                        numeric,
                    )
                )
                if len(entries) > 4096:
                    raise ValidationError("automation window bound exceeded")
        self.windows[path] = entries
        if entries:
            self._ensure_target(
                program,
                automation_id,
                path,
                TimerTarget(
                    "SAME_BOOT_MONOTONIC",
                    boot_session_id=entries[0].boot_session_id,
                    elapsed_realtime_nanos=entries[0].time_nanos
                    + value["window_seconds"] * _NANO,
                ),
                intents,
            )
        else:
            self._retire_condition(path, intents)
        aggregate = (
            len(entries)
            if value["aggregate"]["type"] == "count"
            else sum(entry.numeric_value for entry in entries)
        )
        return _compare_integer(
            aggregate,
            value["comparison"]["operator"],
            int(value["comparison"]["value"]),
        )

    def _cooldown_active(
        self, automation: dict[str, Any], clock: ReducerClock
    ) -> bool:
        cooldown = automation["cooldown"]
        mark = self.cooldowns.get(automation["id"])
        if cooldown is None or mark is None:
            return False
        elapsed = (
            clock.active_elapsed_nanos - mark.active_elapsed_nanos
            if cooldown["clock"] == "ACTIVE_RUNNING_TIME"
            else clock.calendar_elapsed_nanos - mark.calendar_elapsed_nanos
        )
        return elapsed < cooldown["duration_seconds"] * _NANO

    def _ensure_condition(
        self,
        program: CompiledAutomationProgram,
        automation_id: str,
        path: str,
        clock: str,
        due: int,
        intents: list[TimerIntent],
    ) -> None:
        if self.lifecycle not in _ACTIVE:
            return
        target = (
            TimerTarget("ACTIVE_ELAPSED", elapsed_nanos=due)
            if clock == "ACTIVE_RUNNING_TIME"
            else TimerTarget(
                "CALENDAR_UTC", utc_millis=_required(self.study_start) + due // 1_000_000
            )
        )
        self._ensure_target(program, automation_id, path, target, intents)

    def _ensure_target(
        self,
        program: CompiledAutomationProgram,
        automation_id: str,
        path: str,
        target: TimerTarget,
        intents: list[TimerIntent],
    ) -> None:
        if self.lifecycle not in _ACTIVE:
            return
        producer = _condition_producer(path)
        identifier = timer_id(program.configuration_sha256, automation_id, producer)
        existing = self.timers.get(identifier)
        if existing is not None and existing.target == target:
            return
        if existing is not None:
            del self.timers[identifier]
            intents.append(
                TimerIntent("RETIRE", timer_id=existing.id, generation=existing.generation)
            )
        generation = self.generations.get(producer, 0) + 1
        timer = DurableTimer(
            identifier,
            automation_id,
            generation,
            self.current_sequence,
            producer,
            target,
            target.utc_millis if target.type == "CALENDAR_UTC" else None,
            None,
        )
        self.timers[identifier] = timer
        self.generations[producer] = generation
        intents.append(TimerIntent("SCHEDULE", timer=timer))

    def _retire_condition(self, path: str, intents: list[TimerIntent]) -> None:
        producer = _condition_producer(path)
        timer = next(
            (item for item in self.timers.values() if item.producer_key == producer), None
        )
        if timer is not None:
            del self.timers[timer.id]
            intents.append(
                TimerIntent("RETIRE", timer_id=timer.id, generation=timer.generation)
            )


def _matches(
    program: CompiledAutomationProgram,
    matcher: dict[str, Any],
    event: AutomationEvent,
) -> bool:
    identity = matcher["event"]
    if (
        event.source_id != identity["source_id"]
        or event.schema_version != identity["schema_version"]
        or event.event_type != identity["event_type"]
    ):
        return False
    contract = program.contracts[(event.source_id, event.schema_version, event.event_type)]
    for predicate in matcher["predicates"]:
        raw = event.fields.get(predicate["field"])
        if raw is None:
            # Missing fields are false for every operator, including ne.
            return False
        descriptor = contract.fields[predicate["field"]]
        actual = decode_event_wire_field(
            predicate["field"],
            raw,
            descriptor,
            contract.maximum_encoded_event_bytes,
        )
        if predicate["operator"] == "in":
            if not any(
                _compare(
                    actual,
                    decode_predicate_field(
                        predicate["field"],
                        expected,
                        descriptor,
                        contract.maximum_encoded_event_bytes,
                    ),
                    "eq",
                )
                for expected in predicate["values"]
            ):
                return False
        elif not _compare(
            actual,
            decode_predicate_field(
                predicate["field"],
                predicate["value"],
                descriptor,
                contract.maximum_encoded_event_bytes,
            ),
            predicate["operator"],
        ):
            return False
    return True


def _compare(left: Any, right: Any, operator: str) -> bool:
    if type(left) is not type(right):
        raise ValidationError("automation field types diverge")
    if operator == "eq":
        return left == right
    if operator == "ne":
        return left != right
    if operator == "lt":
        return left < right
    if operator == "lte":
        return left <= right
    if operator == "gt":
        return left > right
    if operator == "gte":
        return left >= right
    raise ValidationError("automation comparison operator is invalid")


def _compare_integer(left: int, operator: str, right: int) -> bool:
    return _compare(left, right, operator)


def _duration_nanos(clock: str, value: ReducerClock) -> int:
    return (
        value.active_elapsed_nanos
        if clock == "ACTIVE_RUNNING_TIME"
        else value.calendar_elapsed_nanos
    )


def _event_time(event: AutomationEvent, clock: str) -> ResearchTime:
    return (
        event.observed_time
        if clock == "OBSERVED_RESEARCH_TIME"
        else _required(event.primary_source_time)
    )


def _event_match(event: AutomationEvent, clock: str, kind: str) -> dict[str, Any]:
    return {
        "causal_identity": f"event:{event.sequence_number}",
        "logical_time": _event_time(event, clock),
        "logical_deadline_utc_millis": None,
        "trigger_kind": kind,
    }


def _condition_match(
    input_value: ReducerInput, timer: DurableTimer | None
) -> dict[str, Any]:
    if input_value.type == "TIMER_DUE" and timer is not None:
        result = _timer_match(input_value, timer)
        result["trigger_kind"] = "condition_rising_edge"
        return result
    return {
        "causal_identity": f"event:{input_value.sequence_number}",
        "logical_time": input_value.clock.now,
        "logical_deadline_utc_millis": None,
        "trigger_kind": "condition_rising_edge",
    }


def _timer_match(input_value: ReducerInput, timer: DurableTimer) -> dict[str, Any]:
    return {
        "causal_identity": f"timer:{timer.id}",
        "logical_time": (
            _required(input_value.logical_due)
            if input_value.type == "TIMER_DUE"
            and timer.logical_deadline_utc_millis is not None
            else input_value.clock.now
        ),
        "logical_deadline_utc_millis": timer.logical_deadline_utc_millis,
        "trigger_kind": "schedule",
    }


def _timer_audit_coordinate(timer: DurableTimer) -> ResearchTime:
    target = timer.target
    if target.type == "CALENDAR_UTC":
        return ResearchTime(int(_required(target.utc_millis)), 0, "calendar-time")
    if target.type == "ACTIVE_ELAPSED":
        return ResearchTime(
            0, int(_required(target.elapsed_nanos)), "active-running-time"
        )
    return ResearchTime(
        timer.logical_deadline_utc_millis or 0,
        int(_required(target.elapsed_realtime_nanos)),
        str(_required(target.boot_session_id)),
    )


def _condition_producer(path: str) -> str:
    return _CONDITION_PREFIX + deterministic_digest(
        "particeps-condition-timer-key-v1", path
    )[:40]


def _allowed_destinations(state: str) -> set[str]:
    return {
        "READY": {"ACTIVATING", "WITHDRAWN"},
        "ACTIVATING": {"RUNNING", "PAUSING"},
        "RUNNING": {"PAUSING", "COMPLETED", "WITHDRAWN"},
        "PAUSING": {"PAUSED", "COMPLETED", "WITHDRAWN"},
        "PAUSED": {"ACTIVATING", "COMPLETED", "WITHDRAWN"},
        "COMPLETED": set(),
        "WITHDRAWN": set(),
    }[state]


def _is_due(target: TimerTarget, clock: ReducerClock) -> bool:
    if target.type == "CALENDAR_UTC":
        return clock.now.wall_time_utc_millis >= _required(target.utc_millis)
    if target.type == "ACTIVE_ELAPSED":
        return clock.active_elapsed_nanos >= _required(target.elapsed_nanos)
    return (
        clock.now.boot_session_id == target.boot_session_id
        and clock.now.elapsed_realtime_nanos >= _required(target.elapsed_realtime_nanos)
    )


def _dedupe_sort_intents(intents: list[TimerIntent]) -> list[TimerIntent]:
    unique = []
    for intent in intents:
        if intent not in unique:
            unique.append(intent)
    return sorted(
        unique,
        key=lambda intent: (
            intent.timer_id if intent.type == "RETIRE" else _required(intent.timer).id,
            0 if intent.type == "RETIRE" else 1,
        ),
    )


def _required(value: Any) -> Any:
    if value is None:
        raise ValidationError("automation required value is absent")
    return value
