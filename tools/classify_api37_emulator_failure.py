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
)
PLATFORM_FALLOUT = re.compile(
    r"(?:device offline|no devices/emulators found|device ['\"]?.+?['\"]? not found|"
    r"transport error|connection reset|broken pipe)",
    re.IGNORECASE,
)


def classify(evidence: str) -> tuple[bool, str]:
    product_failure = next((pattern.pattern for pattern in PRODUCT_FAILURES if pattern.search(evidence)), None)
    if product_failure is not None:
        return False, f"Particeps/app/test failure evidence is blocking: {product_failure}"
    missing = [
        label
        for label, present in (
            (KNOWN_MAPPER, KNOWN_MAPPER in evidence),
            (KNOWN_ASSERTION, KNOWN_ASSERTION in evidence),
            ("SurfaceFlinger process identity", KNOWN_PROCESS.search(evidence) is not None),
        )
        if not present
    ]
    if missing:
        return False, "Failure is not the quarantined platform defect; missing: " + ", ".join(missing)
    if PLATFORM_FALLOUT.search(evidence) is None:
        return False, "The platform signature did not cause a recognized emulator transport failure"
    return True, (
        "QUARANTINED: exact API 37 revision 5 mapper.ranchu.so / SurfaceFlinger "
        "readback assertion matched, with no Particeps, VPN, native, or test assertion failure."
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", nargs="+", type=Path)
    arguments = parser.parse_args()
    combined = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in arguments.evidence)
    accepted, reason = classify(combined)
    print(reason)
    return 0 if accepted else 1


if __name__ == "__main__":
    raise SystemExit(main())
