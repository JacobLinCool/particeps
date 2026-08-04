"""The bounded RFC 8785 subset used by Protocol v1.

Protocol JSON never contains floating-point JSON numbers. Sensor floats are strings,
so rejecting every JSON float gives a considerably smaller and safer implementation.
"""

from __future__ import annotations

import json
from typing import Any

from .errors import ValidationError


def _reject_constant(value: str) -> None:
    raise ValidationError(f"non-finite JSON value: {value}")


def _reject_float(value: str) -> None:
    raise ValidationError(f"non-integral JSON number: {value}")


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def parse(data: bytes, *, require_canonical: bool = True) -> Any:
    """Parse one strict UTF-8 JSON value and optionally require exact JCS bytes."""

    try:
        text = data.decode("utf-8", errors="strict")
        value = json.loads(
            text,
            object_pairs_hook=_pairs,
            parse_float=_reject_float,
            parse_constant=_reject_constant,
        )
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        TypeError,
        ValueError,
        OverflowError,
        RecursionError,
    ) as error:
        if isinstance(error, ValidationError):
            raise
        raise ValidationError("malformed JSON") from error
    if require_canonical and canonicalize(value) != data:
        raise ValidationError("JSON is not canonical JCS")
    return value


def parse_embedded_json(text: str) -> Any:
    """Parse a strict payload field which is JSON text but is not required to be JCS."""

    try:
        return json.loads(
            text,
            object_pairs_hook=_pairs,
            parse_constant=_reject_constant,
        )
    except (
        json.JSONDecodeError,
        TypeError,
        ValueError,
        OverflowError,
        RecursionError,
    ) as error:
        if isinstance(error, ValidationError):
            raise
        raise ValidationError("malformed embedded JSON") from error


def canonicalize(value: Any) -> bytes:
    """Encode the Protocol v1 integral-only JCS subset."""

    return _encode(value).encode("utf-8")


def _encode(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        raise ValidationError("floating-point JSON numbers are forbidden")
    if isinstance(value, str):
        _validate_unicode(value)
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(_encode(item) for item in value) + "]"
    if isinstance(value, dict):
        if not all(isinstance(key, str) for key in value):
            raise ValidationError("JSON object keys must be strings")
        keys = sorted(value, key=lambda key: key.encode("utf-16-be"))
        return (
            "{"
            + ",".join(_encode(key) + ":" + _encode(value[key]) for key in keys)
            + "}"
        )
    raise ValidationError(f"unsupported JSON type: {type(value).__name__}")


def _validate_unicode(value: str) -> None:
    try:
        value.encode("utf-8", errors="strict")
        value.encode("utf-16-be", errors="strict")
    except UnicodeEncodeError as error:
        raise ValidationError("JSON contains an unpaired surrogate") from error


def exact_object(value: Any, keys: set[str], name: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        actual = sorted(value) if isinstance(value, dict) else type(value).__name__
        raise ValidationError(f"{name} keys mismatch: {actual}")
    return value


def canonical_decimal(value: Any, name: str, *, maximum: int = 2**63 - 1) -> int:
    if (
        not isinstance(value, str)
        or not value
        or (value != "0" and (value[0] == "0" or not value.isascii()))
    ):
        raise ValidationError(f"{name} must be a canonical unsigned decimal string")
    if not value.isdigit():
        raise ValidationError(f"{name} must be a canonical unsigned decimal string")
    number = int(value)
    if number > maximum:
        raise ValidationError(f"{name} is outside the supported range")
    return number
