#!/usr/bin/env python3
"""Check the finite storage model and require both known-bad variants to fail.

No simulation or timeout is accepted as an exhaustive pass. The pinned checker
is test tooling only; no TLA+/property-testing dependency enters the APK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TLC_URL = "https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar"
TLC_SHA256 = "936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"


def checker(path: Path) -> Path:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(TLC_URL, timeout=20) as response:
            data = response.read(8 * 1024 * 1024 + 1)
        if hashlib.sha256(data).hexdigest() != TLC_SHA256:
            raise RuntimeError("Downloaded TLC does not match its pinned SHA-256")
        path.write_bytes(data)
    if hashlib.sha256(path.read_bytes()).hexdigest() != TLC_SHA256:
        raise RuntimeError("Cached TLC does not match its pinned SHA-256")
    return path.resolve()


def verify_result(result: subprocess.CompletedProcess[str], expected_failure: str | None) -> dict:
    if expected_failure is not None:
        if result.returncode != 12 or f"Error: Invariant {expected_failure} is violated." not in result.stdout:
            raise RuntimeError(f"Expected counterexample for {expected_failure}; see TLC log")
        return {"counterexample": expected_failure}
    counts = re.search(r"([\d,]+) states generated, ([\d,]+) distinct states found, (\d+) states left on queue", result.stdout)
    if (result.returncode != 0 or counts is None or counts[3] != "0"
            or "Model checking completed. No error has been found." not in result.stdout):
        raise RuntimeError("TLC did not complete exhaustive verification; see TLC log")
    return {"generated_states": int(counts[1].replace(",", "")),
            "distinct_states": int(counts[2].replace(",", "")), "remaining_states": 0}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / ".gradle/verification/tla2tools-1.7.4.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/storage-model")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "timing.json").unlink(missing_ok=True)
    started = time.perf_counter()
    jar = checker(args.jar)
    setup_seconds = time.perf_counter() - started
    config = (ROOT / "verification/storage/CommitRecovery.cfg").read_text()
    runs = []
    for name, flag, violation in (
        ("correct", None, None),
        ("missing-readback-sync", "SyncReadback", "AcknowledgedDurable"),
        ("missing-recovery-sync", "SyncRecovery", "SnapshotBackedByDurableLog"),
    ):
        with tempfile.TemporaryDirectory(prefix="particeps-tlc-") as directory:
            work = Path(directory)
            shutil.copyfile(ROOT / "verification/storage/CommitRecovery.tla", work / "CommitRecovery.tla")
            (work / "CommitRecovery.cfg").write_text(
                config if flag is None else config.replace(f"{flag} = TRUE", f"{flag} = FALSE"),
            )
            run_started = time.perf_counter()
            try:
                result = subprocess.run(
                    ["java", "-XX:+UseParallelGC", "-Xmx256m", "-jar", str(jar),
                     "-workers", "1", "-seed", "1", "-fp", "0", "CommitRecovery"],
                    cwd=work, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=15, check=False,
                )
            except subprocess.TimeoutExpired as failure:
                (args.output / f"{name}.log").write_bytes(failure.stdout or b"")
                raise RuntimeError(f"{name}: model checking exceeded 15 seconds; verification incomplete") from failure
            (args.output / f"{name}.log").write_text(result.stdout)
            outcome = verify_result(result, violation)
            runs.append({"name": name, "seconds": round(time.perf_counter() - run_started, 3), **outcome})
    report = {"tlc_sha256": TLC_SHA256, "setup_seconds": round(setup_seconds, 3),
              "total_seconds": round(time.perf_counter() - started, 3), "runs": runs}
    (args.output / "timing.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
