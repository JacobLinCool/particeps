from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from adc_analysis.bundle import BundleVerifier
from adc_analysis.catalog import CollectorCatalog
from adc_analysis.configuration import validate_configuration
from adc_analysis.crypto import open_base
from adc_analysis.encoding import base64url_decode, sha256_hex, uuid4_text
from adc_analysis.errors import ValidationError
from adc_analysis.jcs import canonical_decimal, canonicalize, exact_object, parse
from adc_analysis.models import InventoryObject

REPOSITORY = Path(__file__).resolve().parents[2]
PROTOCOL = REPOSITORY / "protocol" / "v1"


class ProtocolConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.corpus = json.loads((PROTOCOL / "conformance-vectors.json").read_text())
        cls.catalog = CollectorCatalog(PROTOCOL / "collector-catalog.json")

    def test_valid_configuration_and_bundle(self) -> None:
        signed = self.corpus["valid"]["signed_configuration"]
        configuration_bytes = bytes.fromhex(signed["canonical_jcs_utf8_hex"])
        validate_configuration(parse(configuration_bytes), self.catalog)
        _validate_signed_configuration(
            bytes.fromhex(signed["envelope_hex"]), self.catalog
        )
        parse(
            bytes.fromhex(
                self.corpus["valid"]["canonical_json"]["canonical_jcs_utf8_hex"]
            )
        )
        bundle = self.corpus["valid"]["bundle"]
        verified = self._verify_container(bytes.fromhex(bundle["container_hex"]))
        self.assertEqual(bundle["bundle_id"], verified.bundle_id)
        self.assertEqual(verified.event_count, len(verified.events))

    def test_hpke_golden_material(self) -> None:
        bundle = self.corpus["valid"]["bundle"]
        key = base64url_decode(
            bundle["researcher_private_key_base64url"], 32, "private key"
        )
        plaintext = open_base(
            key,
            bytes.fromhex(bundle["hpke_wrapped_content_key_hex"]),
            bytes.fromhex(bundle["context_jcs_utf8_hex"]),
        )
        self.assertEqual(bundle["content_key_hex"], plaintext.hex())

    def test_every_hostile_vector_is_consumed_and_rejected(self) -> None:
        consumed: set[str] = set()
        for vector in self.corpus["hostile"]:
            entrypoint = vector["entrypoint"]
            encoded = bytes.fromhex(vector["input_hex"])
            with (
                self.subTest(vector=vector["id"]),
                self.assertRaises((ValidationError, ValueError)),
            ):
                if entrypoint == "canonical_json":
                    parse(encoded)
                elif entrypoint == "configuration_jcs":
                    validate_configuration(parse(encoded), self.catalog)
                elif entrypoint == "signed_configuration":
                    _validate_signed_configuration(encoded, self.catalog)
                elif entrypoint == "bundle":
                    self._verify_container(encoded)
                elif entrypoint == "bundle_unwrap_context":
                    bundle = self.corpus["valid"]["bundle"]
                    open_base(
                        base64url_decode(
                            bundle["researcher_private_key_base64url"],
                            32,
                            "private key",
                        ),
                        bytes.fromhex(bundle["hpke_wrapped_content_key_hex"]),
                        encoded,
                    )
                elif entrypoint == "receipt":
                    _validate_receipt(encoded)
                else:
                    self.fail(f"unhandled conformance entrypoint: {entrypoint}")
            consumed.add(vector["id"])
        self.assertEqual({item["id"] for item in self.corpus["hostile"]}, consumed)

    def test_manual_export_streams_events_and_receiver_origin_rejects_it(self) -> None:
        bundle = self.corpus["valid"]["bundle"]
        encoded = bytes.fromhex(bundle["container_hex"])
        document = json.loads(bytes.fromhex(bundle["document_jcs_utf8_hex"]))
        document["bundle_kind"] = "manual_export"
        key_length = int.from_bytes(encoded[56:58], "big")
        ciphertext_start = 70 + key_length + 80
        manual = encoded[:ciphertext_start] + AESGCM(
            bytes.fromhex(bundle["content_key_hex"])
        ).encrypt(
            bytes.fromhex(bundle["content_nonce_hex"]),
            canonicalize(document),
            bytes.fromhex(bundle["context_jcs_utf8_hex"]),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "manual.adcexp"
            path.write_bytes(manual)
            verifier = BundleVerifier(
                self.catalog,
                {
                    "vector-hpke": base64url_decode(
                        bundle["researcher_private_key_base64url"], 32, "private key"
                    )
                },
                root / "staging",
            )
            local = InventoryObject(
                path.as_uri(),
                hashlib.sha256(manual).hexdigest(),
                len(manual),
                path,
                None,
            )
            verified = verifier.verify(local)
            try:
                self.assertEqual("manual_export", verified.bundle_kind)
                self.assertEqual(verified.event_count, len(list(verified.events)))
            finally:
                verified.events.close()

            receiver = InventoryObject(
                path.as_uri(),
                hashlib.sha256(manual).hexdigest(),
                len(manual),
                path,
                None,
                "receiver",
            )
            with self.assertRaises(ValidationError):
                verifier.verify(receiver)

    def _verify_container(self, encoded: bytes):
        bundle = self.corpus["valid"]["bundle"]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "vector.adcexp"
            path.write_bytes(encoded)
            source = InventoryObject(
                path.as_uri(),
                hashlib.sha256(encoded).hexdigest(),
                len(encoded),
                path,
                None,
            )
            verifier = BundleVerifier(
                self.catalog,
                {
                    "vector-hpke": base64url_decode(
                        bundle["researcher_private_key_base64url"], 32, "private key"
                    )
                },
                Path(temporary) / "staging",
            )
            return verifier.verify(source)


def _validate_signed_configuration(encoded: bytes, catalog: CollectorCatalog) -> None:
    if len(encoded) < 14 + 64 or encoded[:8] != b"ADCCFG01":
        raise ValidationError("invalid signed configuration framing")
    key_length = int.from_bytes(encoded[8:10], "big")
    config_length = int.from_bytes(encoded[10:14], "big")
    if not 3 <= key_length <= 64 or not 2 <= config_length <= 1_048_576:
        raise ValidationError("invalid signed configuration length")
    if len(encoded) != 14 + key_length + config_length + 64:
        raise ValidationError("signed configuration has trailing or truncated bytes")
    try:
        key_id = encoded[14 : 14 + key_length].decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ValidationError("signer key ID is malformed UTF-8") from error
    configuration_bytes = encoded[14 + key_length : 14 + key_length + config_length]
    configuration = parse(configuration_bytes)
    validate_configuration(configuration, catalog)
    if key_id != configuration["signer"]["key_id"]:
        raise ValidationError("signer key ID mismatch")
    public = base64url_decode(configuration["signer"]["public_key"], 32, "signer key")
    signature = encoded[-64:]
    try:
        Ed25519PublicKey.from_public_bytes(public).verify(
            signature, configuration_bytes
        )
    except (ValueError, InvalidSignature) as error:
        raise ValidationError("configuration signature failed") from error


def _validate_receipt(encoded: bytes) -> None:
    receipt = exact_object(
        parse(encoded),
        {
            "bundle_id",
            "byte_count",
            "configuration_sha256",
            "event_count",
            "first_sequence_number",
            "last_sequence_number",
            "sha256",
        },
        "receipt",
    )
    uuid4_text(receipt["bundle_id"], "receipt bundle ID")
    sha256_hex(receipt["configuration_sha256"], "configuration digest")
    sha256_hex(receipt["sha256"], "ciphertext digest")
    byte_count = canonical_decimal(
        receipt["byte_count"], "byte_count", maximum=33_554_432
    )
    first = canonical_decimal(receipt["first_sequence_number"], "first_sequence_number")
    last = canonical_decimal(receipt["last_sequence_number"], "last_sequence_number")
    count = canonical_decimal(receipt["event_count"], "event_count")
    if (
        byte_count == 0
        or first == 0
        or last != (first - 1 if count == 0 else first + count - 1)
    ):
        raise ValidationError("receipt arithmetic mismatch")


if __name__ == "__main__":
    unittest.main()
