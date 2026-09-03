"""Closed-world schedule production and materialization validation.

Random entropy is intentionally outside replay.  Replay proves that the committed
selection is the next signed producer slot and belongs to its exact eligible set.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .automation_checkpoint import timer_id
from .automation_model import DurableTimer, TimerProductionRequest, TimerTarget
from .errors import ValidationError

_MINUTE_MS = 60_000
_SECOND_MS = 1_000
_NANO = 1_000_000_000
_UTC = UTC


def require_valid_schedule_timer(request: TimerProductionRequest, timer: DurableTimer) -> None:
    if request.pending_timer is not None:
        raise ValidationError("automation schedule already has a pending timer")
    schedule = request.schedule
    if schedule["type"] == "random_window":
        candidate = _next_random_candidates(request)
        if candidate is None:
            raise ValidationError("automation random schedule is exhausted")
        producer_key, eligible = candidate
        if timer.producer_key != producer_key:
            raise ValidationError("automation random producer key is not next")
        if timer.target.type != "CALENDAR_UTC" or timer.target.utc_millis not in eligible:
            raise ValidationError("automation random selection is ineligible")
        expected = _calendar_timer(request, producer_key, _required(timer.target.utc_millis))
    else:
        expected = _produce_standard(request)
        if expected is None:
            raise ValidationError("automation standard schedule is not eligible")
    if timer != expected:
        raise ValidationError("automation timer diverges from the signed schedule")


def _produce_standard(request: TimerProductionRequest) -> DurableTimer | None:
    if request.pending_timer is not None:
        return request.pending_timer
    if request.session_state != "RUNNING":
        return None
    schedule = request.schedule
    kind = schedule["type"]
    if kind == "one_time":
        producer = "one-time"
        if _has_key(request, producer):
            return None
        if schedule["clock"] == "CALENDAR_TIME":
            deadline = request.study_start_utc_millis + schedule["offset_minutes"] * _MINUTE_MS
            return _calendar_timer(request, producer, deadline) if _calendar_eligible(request, deadline) else None
        return _active_timer(request, producer, schedule["offset_minutes"] * 60 * _NANO)
    if kind == "interval":
        keys = {item.producer_key for item in request.materialized}
        for ordinal in range(request.automation["maximum_activations"]):
            producer = f"interval:{ordinal}"
            if producer in keys:
                continue
            offset = schedule["start_offset_minutes"] + ordinal * schedule["interval_minutes"]
            if schedule["clock"] == "CALENDAR_TIME":
                deadline = request.study_start_utc_millis + offset * _MINUTE_MS
                if deadline >= request.study_deadline_utc_millis:
                    return None
                if _minimum_expiry(request, deadline) <= request.clock.now.wall_time_utc_millis:
                    continue
                return _calendar_timer(request, producer, deadline)
            return _active_timer(request, producer, offset * 60 * _NANO)
        return None
    if kind == "daily_local":
        zone = _zone(request.clock.zone_id)
        local_time = time.fromisoformat(schedule["local_time"])
        current = max(
            _utc_datetime(request.study_start_utc_millis).astimezone(zone).date(),
            _utc_datetime(request.clock.now.wall_time_utc_millis).astimezone(zone).date(),
        )
        end = _utc_datetime(request.study_deadline_utc_millis - 1).astimezone(zone).date()
        keys = {item.producer_key for item in request.materialized}
        while current <= end:
            producer = f"daily:{current.isoformat()}"
            if producer not in keys:
                instant = _first_instant(current, local_time, zone)
                if instant is not None:
                    deadline = _millis(instant)
                    if _calendar_eligible(request, deadline):
                        return _calendar_timer(request, producer, deadline)
            current += timedelta(days=1)
        return None
    raise ValidationError("automation standard producer received a random schedule")


def _next_random_candidates(request: TimerProductionRequest) -> tuple[str, tuple[int, ...]] | None:
    schedule = request.schedule
    if request.session_state != "RUNNING" or len(request.materialized) >= schedule["maximum_occurrences_total"]:
        return None
    zone = _zone(request.clock.zone_id)
    current = _utc_datetime(request.study_start_utc_millis).astimezone(zone).date()
    end = _utc_datetime(request.study_deadline_utc_millis - 1).astimezone(zone).date()
    keys = {item.producer_key for item in request.materialized}
    chronological_floor = max((item.selected_utc_millis for item in request.materialized), default=None)
    separation = schedule["minimum_separation_minutes"] * _MINUTE_MS
    while current <= end:
        prefix = f"random:{current.isoformat()}:"
        remaining_daily = schedule["maximum_occurrences_per_day"] - sum(
            item.producer_key.startswith(prefix) for item in request.materialized
        )
        if remaining_daily > 0:
            for window_index, window in enumerate(schedule["local_windows"]):
                start = _parse_minute(window["start_local_time"])
                end_minute = _parse_minute(window["end_local_time"])
                for ordinal in range(schedule["occurrences_per_window"]):
                    total_remaining = schedule["maximum_occurrences_total"] - len(request.materialized)
                    if total_remaining <= 0:
                        return None
                    producer = f"random:{current.isoformat()}:{window_index}:{ordinal}"
                    if producer in keys:
                        continue
                    later = sum(
                        f"random:{current.isoformat()}:{window_index}:{candidate}" not in keys
                        for candidate in range(ordinal + 1, schedule["occurrences_per_window"])
                    )
                    reserved = min(later, remaining_daily - 1, total_remaining - 1)
                    latest = end_minute - 1 - reserved * schedule["minimum_separation_minutes"]
                    if latest < start:
                        continue
                    preceding_key = f"random:{current.isoformat()}:{window_index}:{ordinal - 1}"
                    preceding = next(
                        (item.selected_utc_millis for item in request.materialized if item.producer_key == preceding_key),
                        None,
                    ) if ordinal else None
                    eligible: list[int] = []
                    for minute in range(start, latest + 1):
                        instant = _first_instant(current, time(minute // 60, minute % 60), zone)
                        if instant is None:
                            continue
                        candidate = _millis(instant)
                        if (
                            candidate < request.study_start_utc_millis
                            or candidate < request.clock.now.wall_time_utc_millis
                            or candidate >= request.study_deadline_utc_millis
                            or (chronological_floor is not None and candidate <= chronological_floor)
                            or (preceding is not None and candidate - preceding < separation)
                            or any(abs(candidate - item.selected_utc_millis) < separation for item in request.materialized)
                        ):
                            continue
                        eligible.append(candidate)
                    if eligible:
                        return producer, tuple(eligible)
        current += timedelta(days=1)
    return None


def _active_timer(request: TimerProductionRequest, producer: str, target: int) -> DurableTimer | None:
    duration = (request.study_deadline_utc_millis - request.study_start_utc_millis) * 1_000_000
    if target < 0 or target >= duration:
        return None
    return DurableTimer(
        timer_id(request.configuration_sha256, request.automation["id"], producer),
        request.automation["id"], request.current_generation + 1, request.causal_sequence,
        producer, TimerTarget("ACTIVE_ELAPSED", elapsed_nanos=target), None, None,
    )


def _calendar_timer(request: TimerProductionRequest, producer: str, deadline: int) -> DurableTimer:
    return DurableTimer(
        timer_id(request.configuration_sha256, request.automation["id"], producer),
        request.automation["id"], request.current_generation + 1, request.causal_sequence,
        producer, TimerTarget("CALENDAR_UTC", utc_millis=deadline), deadline,
        _minimum_expiry(request, deadline),
    )


def _calendar_eligible(request: TimerProductionRequest, deadline: int) -> bool:
    return (
        request.study_start_utc_millis <= deadline < request.study_deadline_utc_millis
        and _minimum_expiry(request, deadline) > request.clock.now.wall_time_utc_millis
    )


def _minimum_expiry(request: TimerProductionRequest, deadline: int) -> int:
    return min(deadline + request.automation["availability_seconds"] * _SECOND_MS, request.study_deadline_utc_millis)


def _has_key(request: TimerProductionRequest, producer: str) -> bool:
    return any(item.producer_key == producer for item in request.materialized)


def _first_instant(local_date: date, local_time: time, zone: ZoneInfo) -> datetime | None:
    naive = datetime.combine(local_date, local_time)
    valid: list[datetime] = []
    for fold in (0, 1):
        candidate = naive.replace(tzinfo=zone, fold=fold).astimezone(_UTC)
        if candidate.astimezone(zone).replace(tzinfo=None) == naive:
            valid.append(candidate)
    return min(valid) if valid else None


def _utc_datetime(millis: int) -> datetime:
    return datetime.fromtimestamp(millis / 1_000, _UTC)


def _millis(value: datetime) -> int:
    return int(value.timestamp() * 1_000)


def _zone(value: str) -> ZoneInfo:
    try:
        return ZoneInfo(value)
    except ZoneInfoNotFoundError as error:
        raise ValidationError("automation clock zone is unknown") from error


def _parse_minute(value: str) -> int:
    hour, minute = (int(part) for part in value.split(":"))
    return hour * 60 + minute


def _required(value: int | None) -> int:
    if value is None:
        raise ValidationError("automation calendar timer target is absent")
    return value
