"""Minimal RFC 9180 base-mode receiver for the fixed Protocol v1 suite."""

from __future__ import annotations

import hashlib
import hmac

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .errors import ValidationError

KEM_ID = 0x0020
KDF_ID = 0x0001
AEAD_ID = 0x0002
_KEM_SUITE = b"KEM" + KEM_ID.to_bytes(2, "big")
_SUITE = (
    b"HPKE"
    + KEM_ID.to_bytes(2, "big")
    + KDF_ID.to_bytes(2, "big")
    + AEAD_ID.to_bytes(2, "big")
)
_VERSION = b"HPKE-v1"


def public_key(private_key: bytes) -> bytes:
    try:
        key = X25519PrivateKey.from_private_bytes(private_key)
    except ValueError as error:
        raise ValidationError("invalid X25519 private key") from error
    return key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )


def open_base(private_key: bytes, wrapped: bytes, info: bytes) -> bytes:
    """Open `enc || ciphertext` with empty AAD and sequence number zero."""

    if len(private_key) != 32 or len(wrapped) != 80:
        raise ValidationError("invalid HPKE key or wrapped-key length")
    enc, ciphertext = wrapped[:32], wrapped[32:]
    try:
        recipient = X25519PrivateKey.from_private_bytes(private_key)
        ephemeral = X25519PublicKey.from_public_bytes(enc)
        dh = recipient.exchange(ephemeral)
        recipient_public = public_key(private_key)
        shared_secret = _extract_and_expand(dh, enc + recipient_public)
        key, nonce = _key_schedule(shared_secret, info)
        return AESGCM(key).decrypt(nonce, ciphertext, b"")
    except (ValueError, InvalidTag) as error:
        raise ValidationError("HPKE authentication failed") from error


def _extract_and_expand(dh: bytes, kem_context: bytes) -> bytes:
    eae_prk = _labeled_extract(b"", _KEM_SUITE, b"eae_prk", dh)
    return _labeled_expand(eae_prk, _KEM_SUITE, b"shared_secret", kem_context, 32)


def _key_schedule(shared_secret: bytes, info: bytes) -> tuple[bytes, bytes]:
    psk_id_hash = _labeled_extract(b"", _SUITE, b"psk_id_hash", b"")
    info_hash = _labeled_extract(b"", _SUITE, b"info_hash", info)
    key_schedule_context = b"\x00" + psk_id_hash + info_hash
    secret = _labeled_extract(shared_secret, _SUITE, b"secret", b"")
    key = _labeled_expand(secret, _SUITE, b"key", key_schedule_context, 32)
    nonce = _labeled_expand(secret, _SUITE, b"base_nonce", key_schedule_context, 12)
    return key, nonce


def _labeled_extract(salt: bytes, suite: bytes, label: bytes, ikm: bytes) -> bytes:
    return _hkdf_extract(salt, _VERSION + suite + label + ikm)


def _labeled_expand(
    prk: bytes, suite: bytes, label: bytes, info: bytes, length: int
) -> bytes:
    labeled_info = length.to_bytes(2, "big") + _VERSION + suite + label + info
    return _hkdf_expand(prk, labeled_info, length)


def _hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac.new(
        salt or bytes(hashlib.sha256().digest_size), ikm, hashlib.sha256
    ).digest()


def _hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    if length > 255 * hashlib.sha256().digest_size:
        raise ValidationError("HPKE expand length is invalid")
    output = bytearray()
    previous = b""
    counter = 1
    while len(output) < length:
        previous = hmac.new(
            prk, previous + info + bytes([counter]), hashlib.sha256
        ).digest()
        output.extend(previous)
        counter += 1
    return bytes(output[:length])
