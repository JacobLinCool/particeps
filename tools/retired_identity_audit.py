#!/usr/bin/env python3
"""Fail the build if the retired Android Data Collector identity reappears.

Two things are checked, and they are the same thing seen from two sides.

The first is a repository-wide audit. Every retired spelling — the product name, the package
segment, the frame magics, the artifact extensions, the join scheme, the bundle format, the media
type, the routing headers, the Python package — is searched for across every tracked file. A match
is an error unless the file appears in ALLOWED below with a reason, so the surviving occurrences
stay what they are meant to be: hostile-rejection fixtures and prose that documents the
retirement. Adding a file to that list is a deliberate act with a written justification, which is
the review the rename issue asked for.

The second is the fresh-install boundary. Moving `applicationId` to `cool.jacoblin.particeps` is what
makes a pre-rename install a different application that Android will not upgrade, and it is also
what made renaming the Keystore aliases, work names, and storage suffixes safe. Both halves are
asserted here: the identity is pinned, and no source file may read the retired namespace.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PATTERNS: dict[str, str] = {
    "product_name": r"Android[ -]Data[ -]Collector|androiddatacollector",
    "config_magic": r"ADCCFG01",
    "export_magic": r"ADCEXP01",
    "storage_magic": r"ADC(?:MET|TXN|EVT|ACT|OUT)01",
    "config_extension": r"\.adccfg",
    "export_extension": r"\.adcexp",
    "segment_extension": r"\.adcs(?![0-9A-Za-z])",
    "join_scheme": r"adc://",
    "bundle_format": r"(?<![0-9A-Za-z-])research-bundle-v1",
    "media_type": r"vnd\.adc\.",
    "routing_header": r"[Xx]-[Aa][Dd][Cc]-",
    "python_package": r"adc[-_]analysis",
    "bare_token": r"(?<![0-9A-Za-z_])[Aa][Dd][Cc](?![0-9A-Za-z_])",
    # The second retired namespace contains no ADC spelling, so without its own pattern it
    # would be enforced only on the six suffixes the boundary check reads.
    "retired_namespace": r"cool[./]linc[./]particeps",
}

# path -> why the retired spelling belongs there. Nothing else may carry one.
ALLOWED: dict[str, str] = {
    "README.md": "documents that pre-rename artifacts and installs are unsupported",
    "app/src/androidTest/kotlin/cool/jacoblin/particeps/AndroidConfigurationImportTest.kt":
        "retired-identity rejection fixture: import must fail closed on the old magic",
    "docs/maintainers/release.md": "records the cutover a maintainer still has to finish",
    "docs/p0-p2-implementation-contract.md": "invariant naming exactly which inputs are rejected",
    "docs/participant-guide.md": "tells a participant what the app they already installed was called",
    "docs/researcher-guide.md": "states that a pre-rename configuration or export is refused",
    "protocol/v1/README.md": "normative statement of the retired identity's rejection",
    "protocol/v1/join-link-vectors.json": "retired-scheme rejection fixture",
    "receiver/README.md": "documents that the retired request vocabulary has no standing",
    "receiver/tests/receiver.test.ts": "retired-identity rejection fixtures",
    "tools/generate_protocol_vectors.mjs": "builds the retired-identity hostile vectors",
    "tools/retired_identity_audit.py": "this audit; the patterns are the retired spellings",
    "tools/tests/test_retired_identity_audit.py": "exercises this audit with retired spellings",
    "web/tests/bundle.spec.ts": "retired-identity rejection fixture",
    "web/tests/compat.spec.ts": "retired-identity rejection fixture",
    "web/tests/join.spec.ts": "retired-identity rejection fixture",
}

APPLICATION_ID = "cool.jacoblin.particeps"
# Every namespace this application has ever shipped under. Each one is a different application to
# Android, so none of them may be read: there is no install to migrate from, only data that the
# uninstall of that build already destroyed.
RETIRED_NAMESPACES = ("cool.linc.androiddatacollector", "cool.linc.particeps")

SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".zip", ".keystore", ".jks"}


class AuditError(Exception):
    pass


def tracked_files(root: Path) -> list[Path]:
    listing = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [Path(name) for name in listing.split("\0") if name]


def audit_tracked_files(root: Path, allowed: dict[str, str]) -> list[str]:
    """Return one message per retired spelling found outside the allow-list."""
    problems: list[str] = []
    for name in tracked_files(root):
        if name.suffix.lower() in SKIP_SUFFIXES or str(name) in allowed:
            continue
        try:
            text = (root / name).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for line_number, line in enumerate(text.splitlines(), start=1):
            for label, pattern in PATTERNS.items():
                match = re.search(pattern, line)
                if match:
                    problems.append(
                        f"{name}:{line_number}: retired {label} {match.group(0)!r}. "
                        "Rename it, or add the file to ALLOWED with the reason it must stay."
                    )
                    break
    return problems


def audit_fresh_install_boundary(root: Path) -> list[str]:
    """The applicationId change is the whole boundary; assert it cannot be undone quietly."""
    problems: list[str] = []
    build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    for field in ("namespace", "applicationId"):
        if not re.search(rf'{field}\s*=\s*"{re.escape(APPLICATION_ID)}"', build):
            problems.append(
                f"app/build.gradle.kts: {field} is not {APPLICATION_ID!r}. A pre-rename install "
                "would become upgradable again, and the renamed Keystore aliases, work names, and "
                "storage suffixes would then read as data loss rather than a fresh install."
            )
    for name in tracked_files(root):
        if name.suffix not in {".kt", ".kts", ".xml", ".pro", ".json", ".gradle"}:
            continue
        try:
            text = (root / name).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for namespace in RETIRED_NAMESPACES:
            if namespace in text:
                problems.append(
                    f"{name}: references {namespace}. Nothing may read a retired namespace; "
                    "there is no migration path from any of them."
                )
    return problems


def audit(root: Path = ROOT, allowed: dict[str, str] | None = None) -> None:
    problems = audit_tracked_files(root, ALLOWED if allowed is None else allowed)
    problems += audit_fresh_install_boundary(root)
    if problems:
        raise AuditError("\n".join(problems))


def main() -> int:
    try:
        audit()
    except (AuditError, OSError, subprocess.CalledProcessError) as error:
        print(f"retired identity audit failed:\n{error}", file=sys.stderr)
        return 1
    print(
        f"retired identity audit passed: {len(ALLOWED)} reviewed exceptions, "
        f"applicationId pinned to {APPLICATION_ID}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
