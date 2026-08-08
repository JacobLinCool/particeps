from __future__ import annotations

import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
