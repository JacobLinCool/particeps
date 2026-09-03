"""Exact Java DataOutput-compatible automation checkpoint wire and digest."""

from __future__ import annotations

import base64
import hashlib
import struct
from collections.abc import Callable, Iterable
from typing import TypeVar

from .automation_model import (
    AutomationCheckpoint,
    CooldownMark,
    DesiredProfile,
    DurableTimer,
    MaterializedTimerSummary,
    ResourceKey,
    SequencePartial,
    TimerTarget,
    WindowEntry,
)
from .errors import ValidationError

_PREFIX = "automation-checkpoint-v1:"
T = TypeVar("T")


class _Writer:
    def __init__(self) -> None:
        self.data = bytearray()

    def byte(self, value: int) -> None:
        self.data.extend(struct.pack(">B", value))

    def boolean(self, value: bool) -> None:
        self.byte(1 if value else 0)

    def integer(self, value: int) -> None:
        self.data.extend(struct.pack(">i", value))

    def long(self, value: int) -> None:
        self.data.extend(struct.pack(">q", value))

    def string(self, value: str) -> None:
        raw = value.encode("utf-8")
        if len(raw) > 512 * 1024:
            raise ValidationError("automation checkpoint string is too large")
        self.integer(len(raw))
        self.data.extend(raw)

    def nullable_long(self, value: int | None) -> None:
        self.boolean(value is not None)
        if value is not None:
            self.long(value)

    def nullable_string(self, value: str | None) -> None:
        self.boolean(value is not None)
        if value is not None:
            self.string(value)

    def ulong(self, value: int) -> None:
        if not 0 <= value < 1 << 64:
            raise ValidationError("automation checkpoint unsigned integer is invalid")
        self.string(str(value))


class _Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.offset = 0

    def _take(self, count: int) -> bytes:
        if count < 0 or self.offset + count > len(self.data):
            raise ValidationError("automation checkpoint is truncated")
        value = self.data[self.offset : self.offset + count]
        self.offset += count
        return value

    def byte(self) -> int:
        return struct.unpack(">B", self._take(1))[0]

    def boolean(self) -> bool:
        value = self.byte()
        if value not in (0, 1):
            raise ValidationError("automation checkpoint boolean is invalid")
        return value == 1

    def integer(self) -> int:
        return struct.unpack(">i", self._take(4))[0]

    def long(self) -> int:
        return struct.unpack(">q", self._take(8))[0]

    def string(self) -> str:
        size = self.integer()
        if not 0 <= size <= 512 * 1024:
            raise ValidationError("automation checkpoint string size is invalid")
        try:
            return self._take(size).decode("utf-8")
        except UnicodeDecodeError as error:
            raise ValidationError("automation checkpoint string is not UTF-8") from error

    def nullable_long(self) -> int | None:
        return self.long() if self.boolean() else None

    def nullable_string(self) -> str | None:
        return self.string() if self.boolean() else None

    def ulong(self) -> int:
        text = self.string()
        if not text.isdigit() or (len(text) > 1 and text.startswith("0")):
            raise ValidationError("automation checkpoint unsigned integer is not canonical")
        value = int(text)
        if value >= 1 << 64:
            raise ValidationError("automation checkpoint unsigned integer is too large")
        return value


def _write_list(writer: _Writer, values: Iterable[T], encode: Callable[[T], None]) -> None:
    items = list(values)
    if len(items) > 4096:
        raise ValidationError("automation checkpoint collection is too large")
    writer.integer(len(items))
    for value in items:
        encode(value)


def _read_list(reader: _Reader, decode: Callable[[], T]) -> list[T]:
    size = reader.integer()
    if not 0 <= size <= 4096:
        raise ValidationError("automation checkpoint collection size is invalid")
    return [decode() for _ in range(size)]


def _write_map(writer: _Writer, values: dict[T, object], key: Callable[[T], None], value: Callable[[object], None]) -> None:
    if len(values) > 4096:
        raise ValidationError("automation checkpoint map is too large")
    writer.integer(len(values))
    for item_key, item_value in values.items():
        key(item_key)
        value(item_value)


def _read_map(reader: _Reader, key: Callable[[], T], value: Callable[[], object]) -> dict[T, object]:
    size = reader.integer()
    if not 0 <= size <= 4096:
        raise ValidationError("automation checkpoint map size is invalid")
    result: dict[T, object] = {}
    for _ in range(size):
        item_key = key()
        if item_key in result:
            raise ValidationError("automation checkpoint map key is duplicated")
        result[item_key] = value()
    return result


def _write_target(writer: _Writer, target: TimerTarget) -> None:
    if target.type == "CALENDAR_UTC":
        writer.byte(0)
        writer.long(_required(target.utc_millis))
    elif target.type == "ACTIVE_ELAPSED":
        writer.byte(1)
        writer.long(_required(target.elapsed_nanos))
    else:
        writer.byte(2)
        writer.string(_required(target.boot_session_id))
        writer.long(_required(target.elapsed_realtime_nanos))


def _read_target(reader: _Reader) -> TimerTarget:
    kind = reader.byte()
    if kind == 0:
        return TimerTarget("CALENDAR_UTC", utc_millis=reader.long())
    if kind == 1:
        return TimerTarget("ACTIVE_ELAPSED", elapsed_nanos=reader.long())
    if kind == 2:
        return TimerTarget(
            "SAME_BOOT_MONOTONIC",
            boot_session_id=reader.string(),
            elapsed_realtime_nanos=reader.long(),
        )
    raise ValidationError("automation checkpoint timer target is unknown")


def _write_timer(writer: _Writer, timer: DurableTimer) -> None:
    writer.string(timer.id)
    writer.string(timer.automation_id)
    writer.ulong(timer.generation)
    writer.long(timer.causal_sequence)
    writer.string(timer.producer_key)
    _write_target(writer, timer.target)
    writer.nullable_long(timer.logical_deadline_utc_millis)
    writer.nullable_long(timer.expires_at_utc_millis)


def _read_timer(reader: _Reader) -> DurableTimer:
    return DurableTimer(
        reader.string(),
        reader.string(),
        reader.ulong(),
        reader.long(),
        reader.string(),
        _read_target(reader),
        reader.nullable_long(),
        reader.nullable_long(),
    )


def encode_automation_checkpoint(checkpoint: AutomationCheckpoint) -> str:
    writer = _Writer()
    writer.integer(1)
    writer.long(checkpoint.evaluated_through_sequence)
    writer.string(checkpoint.lifecycle)
    writer.nullable_long(checkpoint.study_start_utc_millis)
    writer.long(checkpoint.last_active_elapsed_nanos)
    writer.long(checkpoint.last_calendar_elapsed_nanos)
    _write_map(writer, dict(sorted(checkpoint.latch_values.items())), writer.string, writer.boolean)
    _write_map(
        writer,
        dict(sorted(checkpoint.presence_keys.items())),
        writer.string,
        lambda values: _write_list(writer, sorted(values), writer.string),
    )
    _write_map(writer, dict(sorted(checkpoint.held_since_nanos.items())), writer.string, writer.long)
    _write_map(writer, dict(sorted(checkpoint.prior_condition_values.items())), writer.string, writer.boolean)
    _write_map(
        writer,
        dict(sorted(checkpoint.windows.items())),
        writer.string,
        lambda entries: _write_list(writer, entries, lambda entry: _write_window(writer, entry)),
    )
    _write_map(
        writer,
        dict(sorted(checkpoint.sequences.items())),
        writer.string,
        lambda values: _write_list(writer, values, lambda value: _write_sequence(writer, value)),
    )
    _write_map(writer, dict(sorted(checkpoint.activation_counts.items())), writer.string, writer.integer)
    _write_map(
        writer,
        dict(sorted(checkpoint.cooldown_marks.items())),
        writer.string,
        lambda mark: (writer.long(mark.active_elapsed_nanos), writer.long(mark.calendar_elapsed_nanos)),
    )
    resources = dict(sorted(checkpoint.desired_resources.items(), key=lambda item: item[0].sort_key()))
    _write_map(
        writer,
        resources,
        lambda key: (writer.string(key.kind), writer.string(key.id)),
        lambda desired: (writer.ulong(desired.generation), writer.nullable_string(desired.profile_id)),
    )
    _write_map(writer, dict(sorted(checkpoint.timers.items())), writer.string, lambda timer: _write_timer(writer, timer))
    _write_map(writer, dict(sorted(checkpoint.timer_generations.items())), writer.string, writer.ulong)
    _write_map(
        writer,
        dict(sorted(checkpoint.materialized_timers.items())),
        writer.string,
        lambda values: _write_list(writer, values, lambda value: _write_materialized(writer, value)),
    )
    encoded = base64.urlsafe_b64encode(writer.data).rstrip(b"=").decode("ascii")
    return _PREFIX + encoded


def decode_automation_checkpoint(encoded: str) -> AutomationCheckpoint:
    if not encoded.startswith(_PREFIX):
        raise ValidationError("automation checkpoint prefix is invalid")
    payload = encoded.removeprefix(_PREFIX)
    try:
        data = base64.b64decode(payload + "=" * ((4 - len(payload) % 4) % 4), altchars=b"-_", validate=True)
    except ValueError as error:
        raise ValidationError("automation checkpoint base64url is invalid") from error
    reader = _Reader(data)
    if reader.integer() != 1:
        raise ValidationError("automation checkpoint version is unsupported")
    checkpoint = AutomationCheckpoint(
        evaluated_through_sequence=reader.long(),
        lifecycle=reader.string(),  # type: ignore[arg-type]
        study_start_utc_millis=reader.nullable_long(),
        last_active_elapsed_nanos=reader.long(),
        last_calendar_elapsed_nanos=reader.long(),
        latch_values=_read_map(reader, reader.string, reader.boolean),  # type: ignore[arg-type]
        presence_keys=_read_map(reader, reader.string, lambda: set(_read_list(reader, reader.string))),  # type: ignore[arg-type]
        held_since_nanos=_read_map(reader, reader.string, reader.long),  # type: ignore[arg-type]
        prior_condition_values=_read_map(reader, reader.string, reader.boolean),  # type: ignore[arg-type]
        windows=_read_map(reader, reader.string, lambda: _read_list(reader, lambda: _read_window(reader))),  # type: ignore[arg-type]
        sequences=_read_map(reader, reader.string, lambda: _read_list(reader, lambda: _read_sequence(reader))),  # type: ignore[arg-type]
        activation_counts=_read_map(reader, reader.string, reader.integer),  # type: ignore[arg-type]
        cooldown_marks=_read_map(reader, reader.string, lambda: CooldownMark(reader.long(), reader.long())),  # type: ignore[arg-type]
        desired_resources=_read_map(
            reader,
            lambda: ResourceKey(reader.string(), reader.string()),  # type: ignore[arg-type]
            lambda: DesiredProfile(reader.ulong(), reader.nullable_string()),
        ),  # type: ignore[arg-type]
        timers=_read_map(reader, reader.string, lambda: _read_timer(reader)),  # type: ignore[arg-type]
        timer_generations=_read_map(reader, reader.string, reader.ulong),  # type: ignore[arg-type]
        materialized_timers=_read_map(
            reader, reader.string, lambda: _read_list(reader, lambda: _read_materialized(reader))
        ),  # type: ignore[arg-type]
    )
    if reader.offset != len(data) or encode_automation_checkpoint(checkpoint) != encoded:
        raise ValidationError("automation checkpoint is not canonical")
    return checkpoint


def automation_checkpoint_digest(checkpoint: AutomationCheckpoint) -> str:
    components = [
        f"evaluated={checkpoint.evaluated_through_sequence}",
        f"lifecycle={checkpoint.lifecycle}",
        f"start={'' if checkpoint.study_start_utc_millis is None else checkpoint.study_start_utc_millis}",
        f"active={checkpoint.last_active_elapsed_nanos}",
        f"calendar={checkpoint.last_calendar_elapsed_nanos}",
    ]
    components += [f"latch:{_escape(key)}={str(value).lower()}" for key, value in sorted(checkpoint.latch_values.items())]
    components += [f"presence:{_escape(key)}:{_escape(value)}" for key, values in sorted(checkpoint.presence_keys.items()) for value in sorted(values)]
    components += [f"held:{_escape(key)}={value}" for key, value in sorted(checkpoint.held_since_nanos.items())]
    components += [f"prior:{_escape(key)}={str(value).lower()}" for key, value in sorted(checkpoint.prior_condition_values.items())]
    components += [f"window:{_escape(key)}:{value.sequence_number}:{value.time_nanos}:{_escape(value.boot_session_id)}:{value.numeric_value}" for key, values in sorted(checkpoint.windows.items()) for value in values]
    components += [f"sequence:{_escape(key)}:{value.next_step}:{value.first_sequence_number}:{value.last_sequence_number}:{value.first_time_nanos}:{_escape(value.boot_session_id)}" for key, values in sorted(checkpoint.sequences.items()) for value in values]
    components += [f"activation:{_escape(key)}={value}" for key, value in sorted(checkpoint.activation_counts.items())]
    components += [f"cooldown:{_escape(key)}:{value.active_elapsed_nanos}:{value.calendar_elapsed_nanos}" for key, value in sorted(checkpoint.cooldown_marks.items())]
    components += [f"resource:{key.kind}:{_escape(key.id)}:{value.generation}:{_escape(value.profile_id or '')}" for key, value in sorted(checkpoint.desired_resources.items(), key=lambda item: item[0].sort_key())]
    components += [_timer_component(timer) for _, timer in sorted(checkpoint.timers.items())]
    components += [f"timer-generation:{_escape(key)}:{value}" for key, value in sorted(checkpoint.timer_generations.items())]
    components += [f"materialized:{_escape(key)}:{_escape(value.producer_key)}:{value.selected_utc_millis}:{str(value.terminal).lower()}" for key, values in sorted(checkpoint.materialized_timers.items()) for value in values]
    return deterministic_digest("particeps-automation-checkpoint-v1", *components)


def deterministic_digest(domain: str, *components: str) -> str:
    return hashlib.sha256("\0".join((domain, *components)).encode()).hexdigest()


def action_id(configuration: str, automation: str, intervention: str, trigger: str, causal: str, deadline: str) -> str:
    return deterministic_digest("particeps-action-v1", configuration, automation, intervention, trigger, causal, deadline)


def timer_id(configuration: str, automation: str, producer: str) -> str:
    return deterministic_digest("particeps-timer-v1", configuration, automation, producer)


def _write_window(writer: _Writer, entry: WindowEntry) -> None:
    writer.long(entry.sequence_number); writer.long(entry.time_nanos); writer.string(entry.boot_session_id); writer.string(str(entry.numeric_value))


def _read_window(reader: _Reader) -> WindowEntry:
    return WindowEntry(reader.long(), reader.long(), reader.string(), int(reader.string()))


def _write_sequence(writer: _Writer, value: SequencePartial) -> None:
    writer.integer(value.next_step); writer.long(value.first_sequence_number); writer.long(value.last_sequence_number); writer.long(value.first_time_nanos); writer.string(value.boot_session_id)


def _read_sequence(reader: _Reader) -> SequencePartial:
    return SequencePartial(reader.integer(), reader.long(), reader.long(), reader.long(), reader.string())


def _write_materialized(writer: _Writer, value: MaterializedTimerSummary) -> None:
    writer.string(value.producer_key); writer.long(value.selected_utc_millis); writer.boolean(value.terminal)


def _read_materialized(reader: _Reader) -> MaterializedTimerSummary:
    return MaterializedTimerSummary(reader.string(), reader.long(), reader.boolean())


def _timer_component(timer: DurableTimer) -> str:
    target = timer.target
    if target.type == "CALENDAR_UTC":
        encoded_target = f"calendar:{target.utc_millis}"
    elif target.type == "ACTIVE_ELAPSED":
        encoded_target = f"active:{target.elapsed_nanos}"
    else:
        encoded_target = f"monotonic:{_escape(_required(target.boot_session_id))}:{target.elapsed_realtime_nanos}"
    deadline = "" if timer.logical_deadline_utc_millis is None else timer.logical_deadline_utc_millis
    expiry = "" if timer.expires_at_utc_millis is None else timer.expires_at_utc_millis
    return f"timer:{timer.id}:{_escape(timer.automation_id)}:{timer.generation}:{timer.causal_sequence}:{_escape(timer.producer_key)}:{encoded_target}:{deadline}:{expiry}"


def _escape(value: str) -> str:
    return value.replace("%", "%25").replace("\0", "%00").replace(":", "%3a").replace("=", "%3d")


def _required(value: T | None) -> T:
    if value is None:
        raise ValidationError("automation checkpoint required value is absent")
    return value
