"""Strict Protocol v1 text encodings."""

from __future__ import annotations

import base64
import re
import uuid

from .errors import ValidationError

ID = re.compile(r"[a-z0-9][a-z0-9-]{2,63}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_BASE64URL = re.compile(r"[A-Za-z0-9_-]*\Z")


def base64url_decode(value: object, length: int, name: str) -> bytes:
    if not isinstance(value, str) or "=" in value or not _BASE64URL.fullmatch(value):
        raise ValidationError(f"{name} is not unpadded base64url")
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except ValueError as error:
        raise ValidationError(f"{name} is not unpadded base64url") from error
    if len(decoded) != length or base64url_encode(decoded) != value:
        raise ValidationError(f"{name} must encode exactly {length} bytes")
    return decoded


def base64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def protocol_id(value: object, name: str) -> str:
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise ValidationError(f"{name} is invalid")
    return value


def sha256_hex(value: object, name: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise ValidationError(f"{name} must be lowercase SHA-256")
    return value


def uuid4_text(value: object, name: str) -> str:
    value = uuid_text(value, name)
    parsed = uuid.UUID(value)
    if parsed.version != 4 or parsed.variant != uuid.RFC_4122:
        raise ValidationError(f"{name} must be a lowercase RFC 4122 version-4 UUID")
    return value


def uuid_text(value: object, name: str) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{name} must be a UUID")
    try:
        parsed = uuid.UUID(value)
    except ValueError as error:
        raise ValidationError(f"{name} must be a UUID") from error
    if str(parsed) != value:
        raise ValidationError(f"{name} must be a lowercase UUID")
    return value
