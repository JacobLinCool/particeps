#!/usr/bin/env python3
"""Accept only the documented API 37 revision 5 SurfaceFlinger platform failure."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


KNOWN_ASSERTION = "Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma"
KNOWN_MAPPER = "mapper.ranchu.so"
KNOWN_PROCESS = re.compile(r"surfaceflinger", re.IGNORECASE)
PRODUCT_FAILURES = (
    re.compile(r"java\.lang\.AssertionError"),
    re.compile(r"FAILURES!!!"),
    re.compile(r"shortMsg="),
    re.compile(r"Host scenario failed"),
    re.compile(r"(?:FATAL EXCEPTION|Process:)\s*cool\.jacoblin\.particeps", re.IGNORECASE),
    re.compile(r"cool\.jacoblin\.particeps.*(?:FATAL EXCEPTION|assertion)", re.IGNORECASE),
    re.compile(r"(?:libgojni|particeps).*?(?:SIGABRT|SIGSEGV|fatal)", re.IGNORECASE),
    re.compile(r"(?:SIGABRT|SIGSEGV|fatal).*?(?:libgojni|particeps)", re.IGNORECASE),
    re.compile(r"(?:Cmdline:|>>>)\s*cool\.jacoblin\.particeps(?:[.:\s]|$)", re.IGNORECASE),
)
PLATFORM_FALLOUT = re.compile(
    r"(?:device offline|no devices/emulators found|device ['\"]?.+?['\"]? not found|"
    r"transport error|connection reset|broken pipe|Can't find service: (?:package|activity|input))",
    re.IGNORECASE,
)
LOGCAT_PREFIX = re.compile(
    r"^\s*(?:\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}|"
    r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}|"
    r"[VDIWEFAS]/[^\n]+\(\s*\d+\):|[VDIWEFAS]\s+[^:\n]+:)",
)


def product_failure_reason(evidence: str) -> str | None:
    return next((pattern.pattern for pattern in PRODUCT_FAILURES if pattern.search(evidence)), None)


def classify(
    evidence: str,
    platform_evidence: str,
    transport_evidence: str,
    result_label: str = "QUARANTINED",
) -> tuple[bool, str]:
    product_failure = product_failure_reason(evidence + "\n" + platform_evidence + "\n" + transport_evidence)
    if product_failure is not None:
        return False, f"Particeps/app/test failure evidence is blocking: {product_failure}"
    missing = [
        label
        for label, present in (
            (KNOWN_MAPPER, KNOWN_MAPPER in platform_evidence),
            (KNOWN_ASSERTION, KNOWN_ASSERTION in platform_evidence),
            ("SurfaceFlinger process identity", KNOWN_PROCESS.search(platform_evidence) is not None),
        )
        if not present
    ]
    if missing:
        return False, "Failure is not the quarantined platform defect; missing: " + ", ".join(missing)
    if not any(
        PLATFORM_FALLOUT.search(line) is not None and LOGCAT_PREFIX.match(line) is None
        for line in transport_evidence.splitlines()
    ):
        return False, "The current command did not report a recognized emulator transport failure"
    return True, (
        f"{result_label}: exact API 37 revision 5 mapper.ranchu.so / SurfaceFlinger "
        "readback assertion matched, with no Particeps, VPN, native, or test assertion failure."
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--result-label",
        choices=("QUARANTINED", "RETRYABLE"),
        default="QUARANTINED",
    )
    parser.add_argument(
        "--platform-evidence",
        type=Path,
        help="Require the exact platform signature in this current-phase log only.",
    )
    parser.add_argument(
        "--transport-evidence",
        type=Path,
        help="Require transport failure in this current command output, excluding logcat lines.",
    )
    mode.add_argument(
        "--check-product-failures",
        action="store_true",
        help="Reject app/test crash evidence even when the command reported success.",
    )
    parser.add_argument("evidence", nargs="+", type=Path)
    arguments = parser.parse_args()
    combined = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in arguments.evidence)
    if arguments.check_product_failures:
        failure = product_failure_reason(combined)
        if failure is not None:
            print(f"Particeps/app/test failure evidence is blocking: {failure}")
            return 1
        print("No Particeps/app/test failure found in the complete evidence.")
        return 0
    if arguments.platform_evidence is None:
        parser.error("classification requires --platform-evidence from the current phase")
    if arguments.transport_evidence is None:
        parser.error("classification requires --transport-evidence from the current command")
    platform_evidence = arguments.platform_evidence.read_text(encoding="utf-8", errors="replace")
    transport_evidence = arguments.transport_evidence.read_text(encoding="utf-8", errors="replace")
    accepted, reason = classify(combined, platform_evidence, transport_evidence, arguments.result_label)
    print(reason)
    return 0 if accepted else 1


if __name__ == "__main__":
    raise SystemExit(main())
