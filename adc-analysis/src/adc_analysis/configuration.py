"""Semantic validator for the exact Android Protocol v1 configuration object."""

from __future__ import annotations

import re
from calendar import timegm
from datetime import datetime
from typing import Any
from urllib.parse import urlsplit

from .catalog import CollectorCatalog
from .encoding import base64url_decode, protocol_id
from .errors import ValidationError
from .jcs import canonical_decimal, exact_object

ROOT_KEYS = {
    "schema_version",
    "experiment_id",
    "configuration_id",
    "assigned_participant_id",
    "issued_at",
    "expires_at",
    "platform",
    "minimum_client_version",
    "title",
    "researcher",
    "purpose",
    "duration_hours",
    "consent",
    "collectors",
    "surveys",
    "interventions",
    "storage",
    "signer",
    "export",
    "upload",
}
_PARTICIPANT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
_BCP47 = re.compile(r"[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\Z")
_LOCAL_TIME = re.compile(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]\Z")
_INSTANT = re.compile(
    r"(?P<base>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})"
    r"(?:\.(?P<fraction>[0-9]{3}(?:[0-9]{3})?(?:[0-9]{3})?))?Z\Z"
)


def validate_configuration(value: Any, catalog: CollectorCatalog) -> dict[str, Any]:
    root = exact_object(value, ROOT_KEYS, "configuration")
    _integer(root["schema_version"], "schema_version", 1, 1)
    protocol_id(root["experiment_id"], "experiment ID")
    protocol_id(root["configuration_id"], "configuration ID")
    participant = root["assigned_participant_id"]
    if participant is not None and (
        not isinstance(participant, str)
        or not _PARTICIPANT.fullmatch(participant)
        or len(participant.encode("utf-8")) > 64
    ):
        raise ValidationError("assigned participant ID is invalid")
    issued = _instant(root["issued_at"], "issued_at")
    expires = _instant(root["expires_at"], "expires_at")
    if issued >= expires:
        raise ValidationError("configuration expiry must follow issue time")
    if root["platform"] != "android":
        raise ValidationError("analysis accepts only Android configurations")
    if canonical_decimal(root["minimum_client_version"], "minimum_client_version") < 1:
        raise ValidationError("minimum client version must be positive")
    _bounded_text(root["title"], "title", 1, 120)
    researcher = exact_object(root["researcher"], {"name", "contact"}, "researcher")
    _bounded_text(researcher["name"], "researcher name", 1, 120)
    _bounded_text(researcher["contact"], "researcher contact", 3, 240)
    _bounded_text(root["purpose"], "purpose", 1, 2_000)
    duration = _integer(root["duration_hours"], "duration_hours", 1, 8_760)
    consent = exact_object(root["consent"], {"document_version", "summary"}, "consent")
    _bounded_text(consent["document_version"], "consent document version", 1, 64)
    _bounded_text(consent["summary"], "consent summary", 1, 8_000)

    collectors = _array(root["collectors"], "collectors")
    if not collectors:
        raise ValidationError("at least one collector is required")
    collector_ids = [catalog.validate_collector_config(item) for item in collectors]
    _unique(collector_ids, "collector ID")

    surveys = _array(root["surveys"], "surveys")
    survey_ids = [_validate_survey(item) for item in surveys]
    _unique(survey_ids, "survey ID")
    interventions = _array(root["interventions"], "interventions")
    intervention_ids: list[str] = []
    trigger_ids: list[str] = []
    maximum_occurrences = 0
    for item in interventions:
        intervention_id, ids, occurrences, survey_reference = _validate_intervention(
            item, duration
        )
        intervention_ids.append(intervention_id)
        trigger_ids.extend(ids)
        maximum_occurrences += occurrences
        if survey_reference is not None and survey_reference not in survey_ids:
            raise ValidationError("intervention references an unknown survey")
    _unique(intervention_ids, "intervention ID")
    _unique(trigger_ids, "intervention trigger ID")
    if maximum_occurrences > 512:
        raise ValidationError("too many intervention occurrences")

    storage = exact_object(root["storage"], {"maximum_local_bytes"}, "storage")
    _integer(storage["maximum_local_bytes"], "maximum_local_bytes", 8 << 20, 8 << 30)
    signer = exact_object(root["signer"], {"key_id", "public_key"}, "signer")
    protocol_id(signer["key_id"], "signer key ID")
    base64url_decode(signer["public_key"], 32, "signer public key")
    export = exact_object(
        root["export"], {"researcher_key_id", "hpke_public_key"}, "export"
    )
    protocol_id(export["researcher_key_id"], "researcher key ID")
    base64url_decode(export["hpke_public_key"], 32, "researcher HPKE public key")
    _validate_upload(root["upload"])
    return root


def _validate_upload(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValidationError("upload must be an object")
    if not value:
        return
    upload = exact_object(
        value, {"endpoint", "interval_minutes", "allow_metered"}, "upload"
    )
    endpoint = _bounded_text(upload["endpoint"], "upload endpoint", 8, 2_048)
    if not endpoint.startswith("https://") or any(
        ord(character) <= 0x20 for character in endpoint
    ):
        raise ValidationError("upload endpoint must be an HTTPS authority")
    try:
        parsed = urlsplit(endpoint)
        host = parsed.hostname
    except ValueError as error:
        raise ValidationError("upload endpoint must be an HTTPS authority") from error
    if parsed.scheme != "https" or not host:
        raise ValidationError("upload endpoint must be an HTTPS authority")
    _integer(upload["interval_minutes"], "upload interval", 1, 10_080)
    if not isinstance(upload["allow_metered"], bool):
        raise ValidationError("allow_metered must be boolean")


def _validate_survey(value: Any) -> str:
    root = exact_object(value, {"id", "title", "description", "questions"}, "survey")
    survey_id = protocol_id(root["id"], "survey ID")
    _localized(root["title"], "survey title")
    _localized(root["description"], "survey description")
    questions = _array(root["questions"], "survey questions")
    if not 1 <= len(questions) <= 100:
        raise ValidationError("survey question count is invalid")
    ids = [_validate_question(item) for item in questions]
    _unique(ids, "survey question ID")
    return survey_id


def _validate_question(value: Any) -> str:
    if not isinstance(value, dict):
        raise ValidationError("survey question must be an object")
    kind = value.get("type")
    if not isinstance(kind, str):
        raise ValidationError("unknown survey question type")
    common = {"type", "id", "prompt", "required"}
    extra = {
        "short_text": {"maximum_length"},
        "scale": {"minimum", "maximum", "minimum_label", "maximum_label"},
        "single_choice": {"options"},
        "multiple_choice": {"options", "minimum_selections", "maximum_selections"},
    }.get(kind)
    if extra is None:
        raise ValidationError("unknown survey question type")
    root = exact_object(value, common | extra, "survey question")
    question_id = protocol_id(root["id"], "survey question ID")
    _localized(root["prompt"], "question prompt")
    if not isinstance(root["required"], bool):
        raise ValidationError("survey required must be boolean")
    if kind == "short_text":
        _integer(root["maximum_length"], "maximum_length", 1, 4_000)
    elif kind == "scale":
        minimum = _integer(root["minimum"], "scale minimum", -1_000, 1_000)
        maximum = _integer(root["maximum"], "scale maximum", -1_000, 1_000)
        if minimum >= maximum:
            raise ValidationError("scale bounds are invalid")
        _localized(root["minimum_label"], "minimum label")
        _localized(root["maximum_label"], "maximum label")
    else:
        options = _array(root["options"], "choice options")
        if not 2 <= len(options) <= 50:
            raise ValidationError("choice option count is invalid")
        option_ids = []
        for option in options:
            option = exact_object(option, {"id", "label"}, "choice option")
            option_ids.append(protocol_id(option["id"], "choice option ID"))
            _localized(option["label"], "choice label")
        _unique(option_ids, "choice option ID")
        if kind == "multiple_choice":
            minimum = _integer(
                root["minimum_selections"], "minimum selections", 0, len(options)
            )
            maximum = _integer(
                root["maximum_selections"],
                "maximum selections",
                max(1, minimum),
                len(options),
            )
            if root["required"] and minimum == 0:
                raise ValidationError("required multiple choice needs a selection")
    return question_id


def _validate_intervention(
    value: Any, duration_hours: int
) -> tuple[str, list[str], int, str | None]:
    root = exact_object(value, {"id", "action", "triggers"}, "intervention")
    intervention_id = protocol_id(root["id"], "intervention ID")
    action = root["action"]
    if not isinstance(action, dict):
        raise ValidationError("intervention action must be an object")
    kind = action.get("type")
    keys = {"type", "notification_title", "notification_message"}
    if kind == "survey":
        keys.add("survey_id")
    elif kind != "notification":
        raise ValidationError("unknown intervention action")
    action = exact_object(action, keys, "intervention action")
    _bounded_text(action["notification_title"], "notification title", 1, 120)
    _bounded_text(action["notification_message"], "notification message", 1, 500)
    survey_reference = (
        protocol_id(action["survey_id"], "survey ID") if kind == "survey" else None
    )
    triggers = _array(root["triggers"], "intervention triggers")
    if not triggers:
        raise ValidationError("intervention requires a trigger")
    trigger_ids: list[str] = []
    occurrences = 0
    study_minutes = duration_hours * 60
    for trigger in triggers:
        trigger = exact_object(
            trigger, {"id", "schedule", "availability_minutes"}, "trigger"
        )
        trigger_ids.append(protocol_id(trigger["id"], "trigger ID"))
        _integer(trigger["availability_minutes"], "availability_minutes", 1, 525_600)
        schedule = trigger["schedule"]
        if not isinstance(schedule, dict):
            raise ValidationError("schedule must be an object")
        schedule_type = schedule.get("type")
        if schedule_type == "one_time":
            schedule = exact_object(
                schedule, {"type", "offset_minutes", "clock"}, "one-time schedule"
            )
            offset = _integer(
                schedule["offset_minutes"], "offset_minutes", 0, 2**31 - 1
            )
            _clock(schedule["clock"])
            if offset >= study_minutes:
                raise ValidationError("one-time trigger is outside the study")
            occurrences += 1
        elif schedule_type == "interval":
            schedule = exact_object(
                schedule,
                {"type", "start_offset_minutes", "interval_minutes", "clock"},
                "interval schedule",
            )
            start = _integer(
                schedule["start_offset_minutes"], "start_offset_minutes", 0, 2**31 - 1
            )
            interval = _integer(
                schedule["interval_minutes"], "interval_minutes", 1, 525_600
            )
            _clock(schedule["clock"])
            if start >= study_minutes:
                raise ValidationError("interval trigger is outside the study")
            occurrences += (study_minutes - start + interval - 1) // interval
        elif schedule_type == "daily_local":
            schedule = exact_object(
                schedule, {"type", "local_time"}, "daily-local schedule"
            )
            if not isinstance(schedule["local_time"], str) or not _LOCAL_TIME.fullmatch(
                schedule["local_time"]
            ):
                raise ValidationError("daily local time is invalid")
            occurrences += _maximum_reachable_local_dates(study_minutes)
        elif schedule_type == "random_window":
            schedule = exact_object(
                schedule,
                {
                    "type",
                    "local_windows",
                    "occurrences_per_window",
                    "maximum_occurrences_per_day",
                    "maximum_occurrences_total",
                    "minimum_separation_minutes",
                },
                "random-window schedule",
            )
            windows = _array(schedule["local_windows"], "local windows")
            if not 1 <= len(windows) <= 8:
                raise ValidationError("random-window count is invalid")
            previous_end = None
            window_minutes: list[tuple[int, int]] = []
            for window in windows:
                window = exact_object(
                    window, {"start_local_time", "end_local_time"}, "local window"
                )
                start = window["start_local_time"]
                end = window["end_local_time"]
                if (
                    not isinstance(start, str)
                    or not isinstance(end, str)
                    or not _LOCAL_TIME.fullmatch(start)
                    or not _LOCAL_TIME.fullmatch(end)
                    or start >= end
                    or (previous_end is not None and start < previous_end)
                ):
                    raise ValidationError(
                        "local windows must be sorted, non-overlapping same-day ranges"
                    )
                previous_end = end
                window_minutes.append((_local_minute(start), _local_minute(end)))
            per_window = _integer(
                schedule["occurrences_per_window"], "occurrences_per_window", 1, 8
            )
            per_day = _integer(
                schedule["maximum_occurrences_per_day"],
                "maximum_occurrences_per_day",
                1,
                64,
            )
            if per_day > len(windows) * per_window:
                raise ValidationError("daily occurrence limit exceeds window capacity")
            total = _integer(
                schedule["maximum_occurrences_total"],
                "maximum_occurrences_total",
                1,
                512,
            )
            separation = _integer(
                schedule["minimum_separation_minutes"],
                "minimum_separation_minutes",
                1,
                1_440,
            )
            if any(
                end - start < 1 + (per_window - 1) * separation
                for start, end in window_minutes
            ):
                raise ValidationError(
                    "a random window cannot fit its configured occurrences"
                )
            for index, (_, end) in enumerate(window_minutes):
                next_start = window_minutes[(index + 1) % len(window_minutes)][0]
                if index == len(window_minutes) - 1:
                    next_start += 1_440
                if next_start - (end - 1) < separation:
                    raise ValidationError(
                        "random windows are too close for configured separation"
                    )
            # Repeated wall-clock edits can expose arbitrarily many local dates inside a short
            # monotonic study, so only the signed lifetime cap safely contributes here.
            occurrences += total
        else:
            raise ValidationError("unknown intervention schedule")
    _unique(trigger_ids, "trigger ID")
    return intervention_id, trigger_ids, occurrences, survey_reference


def _localized(value: Any, name: str) -> None:
    root = exact_object(value, {"default", "translations"}, name)
    _bounded_text(root["default"], f"{name} default", 1, 2_000)
    translations = root["translations"]
    if not isinstance(translations, dict) or len(translations) > 32:
        raise ValidationError(f"{name} translations are invalid")
    lowered: set[str] = set()
    for language, text in translations.items():
        if not _BCP47.fullmatch(language) or language.lower() in lowered:
            raise ValidationError(f"{name} language tag is invalid or duplicated")
        lowered.add(language.lower())
        _bounded_text(text, f"{name} translation", 1, 2_000)


def _clock(value: Any) -> None:
    if value not in {"CALENDAR_TIME", "ACTIVE_RUNNING_TIME"}:
        raise ValidationError("relative clock is invalid")


def _local_minute(value: str) -> int:
    return int(value[:2]) * 60 + int(value[3:])


def _maximum_reachable_local_dates(study_minutes: int) -> int:
    """Conservative UTC-18..UTC+18 local-date reach shared by Protocol v1 clients."""
    return (study_minutes + 36 * 60 + 1_439) // 1_440 + 1


def _instant(value: Any, name: str) -> int:
    match = _INSTANT.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        raise ValidationError(f"{name} must be an RFC 3339 UTC instant")
    fraction = match.group("fraction")
    if fraction is not None and (
        int(fraction) == 0 or (len(fraction) > 3 and fraction.endswith("000"))
    ):
        raise ValidationError(f"{name} is not a canonical Java Instant")
    try:
        second = datetime.fromisoformat(match.group("base") + "Z")
    except ValueError as error:
        raise ValidationError(f"{name} must be an RFC 3339 UTC instant") from error
    return timegm(second.timetuple()) * 1_000_000_000 + int(
        (fraction or "0").ljust(9, "0")
    )


def _bounded_text(value: Any, name: str, minimum: int, maximum: int) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{name} must be a string")
    length = len(value.encode("utf-16-le")) // 2
    if not minimum <= length <= maximum:
        raise ValidationError(f"{name} length is invalid")
    return value


def _integer(value: Any, name: str, minimum: int, maximum: int) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not minimum <= value <= maximum
    ):
        raise ValidationError(f"{name} is outside bounds")
    return value


def _array(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValidationError(f"{name} must be an array")
    return value


def _unique(values: list[str], name: str) -> None:
    if len(values) != len(set(values)):
        raise ValidationError(f"duplicate {name}")
