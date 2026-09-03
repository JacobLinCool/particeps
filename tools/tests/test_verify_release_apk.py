from __future__ import annotations

import tempfile
import unittest
import hashlib
import json
import struct
import zipfile
from pathlib import Path
from unittest import mock

from tools import verify_release_apk


class VerifyReleaseApkTest(unittest.TestCase):
    def test_repository_anchor_is_strict_and_workflow_does_not_duplicate_it(self) -> None:
        expected = verify_release_apk.read_expected_certificate_sha256()
        workflow = (verify_release_apk.ROOT / ".github/workflows/release.yml").read_text(
            encoding="utf-8",
        )

        self.assertRegex(expected, r"\A[0-9a-f]{64}\Z")
        self.assertNotIn(expected, workflow)

    def test_anchor_rejects_noncanonical_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "certificate.sha256"
            for invalid in ("a" * 64, "A" * 64 + "\n", "a" * 64 + "\r\n", "a" * 64 + "\n\n"):
                with self.subTest(invalid=repr(invalid)):
                    path.write_text(invalid, encoding="ascii")
                    with self.assertRaisesRegex(
                        verify_release_apk.ReleaseApkVerificationError,
                        "exactly 64 lowercase",
                    ):
                        verify_release_apk.read_expected_certificate_sha256(path)

    def test_exactly_one_expected_signer_is_accepted(self) -> None:
        expected = "a" * 64
        output = (
            "Verifies\n"
            "Number of signers: 1\n"
            f"V2 Signer: certificate SHA-256 digest: {expected}\n"
        )

        self.assertEqual(verify_release_apk.require_expected_signer(output, expected), expected)

    def test_missing_multiple_and_malformed_signers_are_rejected(self) -> None:
        valid_digest = "a" * 64
        cases = {
            "missing count": f"V2 Signer: certificate SHA-256 digest: {valid_digest}\n",
            "multiple signers": (
                "Number of signers: 2\n"
                f"V2 Signer: certificate SHA-256 digest: {valid_digest}\n"
                f"V3 Signer: certificate SHA-256 digest: {'b' * 64}\n"
            ),
            "duplicate certificate reports": (
                "Number of signers: 1\n"
                f"V2 Signer: certificate SHA-256 digest: {valid_digest}\n"
                f"V3 Signer: certificate SHA-256 digest: {valid_digest}\n"
            ),
            "missing digest": "Number of signers: 1\n",
            "malformed digest": (
                "Number of signers: 1\n"
                "V2 Signer: certificate SHA-256 digest: not-a-digest\n"
            ),
        }
        for name, output in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(verify_release_apk.ReleaseApkVerificationError):
                    verify_release_apk.extract_single_signer_certificate_sha256(output)

    def test_unexpected_certificate_is_rejected(self) -> None:
        output = (
            "Number of signers: 1\n"
            f"V2 Signer: certificate SHA-256 digest: {'b' * 64}\n"
        )

        with self.assertRaisesRegex(
            verify_release_apk.ReleaseApkVerificationError,
            "mismatch",
        ):
            verify_release_apk.require_expected_signer(output, "a" * 64)

    def test_apksigner_failure_is_rejected_even_with_matching_output(self) -> None:
        expected = verify_release_apk.read_expected_certificate_sha256()
        result = mock.Mock(
            returncode=1,
            stdout=(
                "Number of signers: 1\n"
                f"V2 Signer: certificate SHA-256 digest: {expected}\n"
            ),
            stderr="verification failed\n",
        )
        with (
            mock.patch.object(verify_release_apk.subprocess, "run", return_value=result),
            mock.patch("builtins.print"),
            self.assertRaisesRegex(
                verify_release_apk.ReleaseApkVerificationError,
                "exited with 1",
            ),
        ):
            verify_release_apk.verify_release_apk(
                Path("/sdk/apksigner"),
                Path("/tmp/final.apk"),
            )

    def test_release_jobs_have_minimum_permissions_and_call_verifier_before_publish(self) -> None:
        workflow = (verify_release_apk.ROOT / ".github/workflows/release.yml").read_text(
            encoding="utf-8",
        )
        header, jobs = workflow.split("\njobs:\n", maxsplit=1)
        instrumented, release = jobs.split("\n  release:\n", maxsplit=1)

        self.assertNotIn("contents: write", header)
        self.assertIn("    permissions:\n      contents: read\n", instrumented)
        self.assertIn("    permissions:\n      contents: write\n", release)
        verifier = 'python3 tools/verify_release_apk.py "$build_tools/apksigner" "$release_apk"'
        self.assertIn(verifier, release)
        self.assertLess(release.index(verifier), release.index("sha256sum"))
        self.assertLess(release.index(verifier), release.index("gh release"))

    def test_apk_contents_require_four_architecture_aligned_abis_and_exact_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "release.apk"
            evidence = fixture_evidence()
            write_release_fixture(apk, evidence=evidence)
            verify_release_apk.verify_apk_contents(apk, evidence)

            write_release_fixture(apk, evidence=evidence, low_alignment_abi="x86_64")
            with self.assertRaisesRegex(
                verify_release_apk.ReleaseApkVerificationError,
                "below 16 KiB alignment",
            ):
                verify_release_apk.verify_apk_contents(apk, evidence)

            write_release_fixture(apk, evidence=evidence, low_alignment_abi="armeabi-v7a")
            with self.assertRaisesRegex(
                verify_release_apk.ReleaseApkVerificationError,
                "below 4 KiB alignment",
            ):
                verify_release_apk.verify_apk_contents(apk, evidence)

    def test_apk_contents_reject_missing_or_stale_supply_chain_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "release.apk"
            evidence = fixture_evidence()
            write_release_fixture(
                apk,
                evidence=evidence,
                stale_asset=verify_release_apk.NATIVE_SBOM_ASSET,
            )
            with self.assertRaisesRegex(
                verify_release_apk.ReleaseApkVerificationError,
                "stale assets/particeps/traffic-shaping-sbom.json",
            ):
                verify_release_apk.verify_apk_contents(apk, evidence)

            write_release_fixture(apk, evidence=evidence, stale_license=True)
            with self.assertRaisesRegex(
                verify_release_apk.ReleaseApkVerificationError,
                "stale module license",
            ):
                verify_release_apk.verify_apk_contents(apk, evidence)

    def test_manifest_contract_and_zip_alignment_are_fail_closed(self) -> None:
        manifest = "\n".join(
            [
                *verify_release_apk.REQUIRED_PERMISSIONS,
                "E: manifest",
                "  E: application",
                "    E: service (line=1)",
                f'      A: android:name(0x1)="{verify_release_apk.VPN_SERVICE}" '
                f'(Raw: "{verify_release_apk.VPN_SERVICE}")',
                '      A: android:permission(0x2)="android.permission.BIND_VPN_SERVICE"',
                "      A: android:exported(0x3)=false",
                "      A: android:foregroundServiceType(0x4)=0x00000400",
                "      E: intent-filter (line=2)",
                "        E: action (line=3)",
                '          A: android:name(0x1)="android.net.VpnService"',
                "      E: meta-data (line=4)",
                '        A: android:name(0x1)="android.net.VpnService.SUPPORTS_ALWAYS_ON"',
                "        A: android:value(0x5)=false",
            ],
        )
        with mock.patch.object(
            verify_release_apk.subprocess,
            "run",
            return_value=mock.Mock(returncode=0, stdout=manifest, stderr=""),
        ) as runner:
            verify_release_apk.verify_manifest(Path("aapt2"), Path("release.apk"))
            self.assertEqual(
                runner.call_args.args[0],
                [
                    "aapt2",
                    "dump",
                    "xmltree",
                    "--file",
                    "AndroidManifest.xml",
                    "release.apk",
                ],
            )
            verify_release_apk.verify_zip_alignment(Path("zipalign"), Path("release.apk"))
            self.assertEqual(
                runner.call_args.args[0],
                ["zipalign", "-c", "-P", "16", "4", "release.apk"],
            )

        with mock.patch.object(
            verify_release_apk.subprocess,
            "run",
            return_value=mock.Mock(returncode=1, stdout="", stderr="bad"),
        ), self.assertRaises(verify_release_apk.ReleaseApkVerificationError):
            verify_release_apk.verify_zip_alignment(Path("zipalign"), Path("release.apk"))

    def test_release_source_rejects_tracked_native_binaries_and_sensitive_logging(self) -> None:
        verify_release_apk.verify_repository_release_contracts()
        with self.assertRaisesRegex(
            verify_release_apk.ReleaseApkVerificationError,
            "tracked native binary",
        ):
            verify_release_apk.verify_repository_release_contracts(
                tracked_files=(Path("native/opaque/libtraffic.so"),),
            )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native = root / "native/traffic-shaping"
            android = root / "actuator/traffic-shaping/src/main/example"
            native.mkdir(parents=True)
            android.mkdir(parents=True)
            (native / "engine.go").write_text(
                "func start() { installSilentLogger(); iobased.New(); tunnel.New(); "
                "core.CreateStack(); fmt.Printf(\"destination=%s\", value) }\n",
                encoding="utf-8",
            )
            (android / "Adapter.kt").write_text("package example\n", encoding="utf-8")
            with self.assertRaisesRegex(
                verify_release_apk.ReleaseApkVerificationError,
                "production logging",
            ):
                verify_release_apk.verify_repository_release_contracts(root, (Path("README.md"),))


def elf64(alignment: int) -> bytes:
    encoded = bytearray(120)
    encoded[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", encoded, 32, 64)
    struct.pack_into("<H", encoded, 54, 56)
    struct.pack_into("<H", encoded, 56, 1)
    struct.pack_into("<I", encoded, 64, 1)
    struct.pack_into("<Q", encoded, 64 + 48, alignment)
    return bytes(encoded)


def write_release_fixture(
    apk: Path,
    evidence: dict[str, bytes],
    stale_asset: str | None = None,
    low_alignment_abi: str | None = None,
    stale_license: bool = False,
) -> None:
    licenses = {
        member: (b"stale" if stale_license and index == 0 else value)
        for index, (member, value) in enumerate(fixture_licenses().items())
    }
    with zipfile.ZipFile(apk, "w") as archive:
        for abi in verify_release_apk.EXPECTED_ABIS:
            required_alignment = verify_release_apk.MINIMUM_ELF_PAGE_ALIGNMENT_BY_ABI[abi]
            archive.writestr(
                f"lib/{abi}/libgojni.so",
                elf64(required_alignment // 2 if abi == low_alignment_abi else required_alignment),
            )
        for member, value in evidence.items():
            archive.writestr(member, b"stale" if member == stale_asset else value)
        for member, value in licenses.items():
            archive.writestr(member, value)


def fixture_licenses() -> dict[str, bytes]:
    return {
        "assets/particeps/licenses/example.org_one.txt": b"one license\n",
        "assets/particeps/licenses/example.org_two.txt": b"two license\n",
    }


def fixture_evidence() -> dict[str, bytes]:
    licenses = fixture_licenses()
    components = []
    for index, (member, encoded) in enumerate(licenses.items(), start=1):
        module = member.removeprefix(verify_release_apk.MODULE_LICENSE_ASSET_PREFIX).removesuffix(
            ".txt",
        ).replace("_", "/", 1)
        components.append(
            {
                "license_file": "LICENSE",
                "license_sha256": hashlib.sha256(encoded).hexdigest(),
                "module": module,
                "version": f"v1.0.{index}",
            },
        )
    return {
        verify_release_apk.REGISTRY_DIGEST_ASSET: b"registry\n",
        verify_release_apk.NATIVE_SBOM_ASSET: json.dumps(
            {"components": components, "format": 1},
            separators=(",", ":"),
        ).encode(),
        verify_release_apk.NATIVE_NOTICES_ASSET: b"notices\n",
        verify_release_apk.TUN2SOCKS_NOTICE_ASSET: b"tun2socks\n",
    }


if __name__ == "__main__":
    unittest.main()
