from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CERTIFICATE_SHA256_PATH = ROOT / ".github" / "android-release-signing-certificate.sha256"
EXPECTED_SHA256_PATTERN = re.compile(rb"[0-9a-f]{64}\n")
ACTUAL_SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
SIGNER_COUNT_PATTERN = re.compile(r"^Number of signers: ([0-9]+)$", re.MULTILINE)
CERTIFICATE_SHA256_LABEL = "certificate SHA-256 digest: "


class ReleaseApkVerificationError(ValueError):
    pass


def read_expected_certificate_sha256(path: Path = DEFAULT_CERTIFICATE_SHA256_PATH) -> str:
    value = path.read_bytes()
    if EXPECTED_SHA256_PATTERN.fullmatch(value) is None:
        raise ReleaseApkVerificationError(
            f"{path} must contain exactly 64 lowercase hexadecimal characters and one newline",
        )
    return value[:-1].decode("ascii")


def extract_single_signer_certificate_sha256(apksigner_output: str) -> str:
    signer_counts = SIGNER_COUNT_PATTERN.findall(apksigner_output)
    if signer_counts != ["1"]:
        raise ReleaseApkVerificationError(
            f"expected exactly one APK signer, found reports {signer_counts or 'none'}",
        )

    certificate_lines = [
        line
        for line in apksigner_output.splitlines()
        if CERTIFICATE_SHA256_LABEL in line
    ]
    if len(certificate_lines) != 1:
        raise ReleaseApkVerificationError(
            f"expected exactly one signer certificate SHA-256, found {len(certificate_lines)}",
        )
    actual = certificate_lines[0].split(CERTIFICATE_SHA256_LABEL, maxsplit=1)[1]
    if ACTUAL_SHA256_PATTERN.fullmatch(actual) is None:
        raise ReleaseApkVerificationError("apksigner returned a malformed certificate SHA-256")
    return actual.lower()


def require_expected_signer(apksigner_output: str, expected_sha256: str) -> str:
    actual_sha256 = extract_single_signer_certificate_sha256(apksigner_output)
    if actual_sha256 != expected_sha256:
        raise ReleaseApkVerificationError(
            "release APK signer certificate SHA-256 mismatch: "
            f"expected {expected_sha256}, found {actual_sha256}",
        )
    return actual_sha256


def verify_release_apk(
    apksigner: Path,
    apk: Path,
    certificate_sha256_path: Path = DEFAULT_CERTIFICATE_SHA256_PATH,
) -> str:
    expected_sha256 = read_expected_certificate_sha256(certificate_sha256_path)
    result = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    if result.stderr:
        print(result.stderr, end="" if result.stderr.endswith("\n") else "\n", file=sys.stderr)
    if result.returncode != 0:
        raise ReleaseApkVerificationError(f"apksigner verify exited with {result.returncode}")

    actual_sha256 = require_expected_signer(result.stdout, expected_sha256)
    print(f"Release APK signer certificate SHA-256 matches repository anchor: {actual_sha256}")
    return actual_sha256


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify an APK and require the repository-pinned production signing certificate.",
    )
    parser.add_argument("apksigner", type=Path)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--certificate-sha256",
        type=Path,
        default=DEFAULT_CERTIFICATE_SHA256_PATH,
    )
    arguments = parser.parse_args()
    try:
        verify_release_apk(
            arguments.apksigner,
            arguments.apk,
            arguments.certificate_sha256,
        )
    except (OSError, ReleaseApkVerificationError) as failure:
        parser.exit(1, f"Release APK verification failed: {failure}\n")


if __name__ == "__main__":
    main()
