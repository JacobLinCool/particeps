#!/usr/bin/env python3
"""Short, process-bound debug broadcasts; long work is admitted once and polled."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

PACKAGE = "cool.jacoblin.particeps"
QUERY = f"{PACKAGE}.HOST_HARNESS_QUERY"


class ControlFailure(RuntimeError):
    pass


def receipt_from_broadcast(output: str) -> dict:
    matches = re.findall(r'^Broadcast completed: result=-1, data="(.*)"\s*$', output, re.M)
    if len(matches) != 1:
        raise ControlFailure("Broadcast did not return one successful control receipt")
    value = json.loads(matches[0])
    if not isinstance(value, dict) or type(value.get("schema_version")) is not int or value["schema_version"] != 1:
        raise ControlFailure("Invalid control receipt schema")
    if str(uuid.UUID(value["process_id"])) != value["process_id"] or not isinstance(value.get("status"), str):
        raise ControlFailure("Invalid control process identity/status")
    return value


class HostControl:
    def __init__(self, adb: list[str], *, run=subprocess.run, clock=time.monotonic, sleep=time.sleep):
        self.adb, self.run, self.clock, self.sleep = adb, run, clock, sleep
        self.pid: str | None = None
        self.process_id: str | None = None
        self.trace: list[dict] = []

    def command(self, arguments: list[str], deadline: float, maximum: float = 5.0) -> str:
        remaining = deadline - self.clock()
        if remaining <= 0:
            raise ControlFailure("Host control deadline expired; outcome must not be retried")
        completed = self.run(self.adb + arguments, capture_output=True, text=True,
                             timeout=min(maximum, remaining), check=False)
        if completed.returncode != 0:
            raise ControlFailure("ADB control command failed; outcome must not be retried")
        return completed.stdout

    def check_pid(self, deadline: float) -> None:
        pid = self.command(["shell", "pidof", PACKAGE], deadline).strip()
        if not re.fullmatch(r"[0-9]+", pid):
            raise ControlFailure("Particeps process is unavailable")
        if self.pid is not None and self.pid != pid:
            raise ControlFailure("Particeps process changed; unknown operation outcome")
        self.pid = pid

    def broadcast(self, action: str, extras: list[str], deadline: float) -> dict:
        self.check_pid(deadline)
        output = self.command(["shell", "am", "broadcast", "--include-stopped-packages", "--receiver-foreground",
                               "-a", action, "-p", PACKAGE] + extras, deadline)
        self.check_pid(deadline)
        value = receipt_from_broadcast(output)
        if self.process_id is not None and value["process_id"] != self.process_id:
            raise ControlFailure("Particeps process identity changed; unknown operation outcome")
        self.process_id = value["process_id"]
        self.trace.append({"host_monotonic_seconds": self.clock(), "status": value["status"],
                           "process_id": self.process_id, "operation_id": value.get("operation_id")})
        return value

    def ready(self, *, start: bool, timeout: float = 105.0) -> None:
        deadline = self.clock() + timeout
        if start:
            # Explicit cold setup only. Measurement/state/profile queries never launch an activity.
            self.command(["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity"], deadline, 30.0)
        while True:
            value = self.broadcast(QUERY, ["--ez", "readiness", "true"], deadline)
            if value["status"] == "READY":
                return
            if value["status"] != "INITIALIZING" or not start:
                raise ControlFailure("Particeps session is not initialized")
            self.sleep(min(0.25, max(0.0, deadline - self.clock())))

    def execute(self, operation: str, *, envelope: str | None = None, timeout: float = 90.0) -> str:
        if self.process_id is None:
            raise ControlFailure("Control requires a ready process")
        deadline = self.clock() + timeout
        identity = ["--es", "process_id", self.process_id]
        if operation == "state":
            value = self.broadcast(QUERY, identity, deadline)
        else:
            operation_id = str(uuid.uuid4())
            extras = identity + ["--es", "operation_id", operation_id]
            if operation in ("profile", "native", "safety-pause"):
                action = QUERY
                if operation == "safety-pause":
                    extras += ["--ez", "include_safety_pause_proof", "true"]
                else:
                    extras += ["--ez", "include_applied_profile", "true"]
                    if operation == "native":
                        extras += ["--ez", "include_native_counters", "true"]
            elif operation in ("reset", "provision"):
                action = f"{PACKAGE}.HOST_HARNESS_{operation.upper()}"
                if operation == "provision":
                    if not envelope:
                        raise ControlFailure("Provision requires a signed fixture")
                    extras += ["--es", "signed_envelope_base64", envelope]
            else:
                raise ControlFailure("Unknown control operation")
            # Exactly one admission. A lost response or process death is an unknown outcome, not
            # permission to execute another reset/import/start transaction.
            value = self.broadcast(action, extras, deadline)
            while True:
                if value.get("operation_id") != operation_id:
                    raise ControlFailure("Control receipt operation identity mismatch")
                if value["status"] != "RUNNING":
                    break
                self.sleep(min(0.1, max(0.0, deadline - self.clock())))
                value = self.broadcast(QUERY, identity + ["--es", "operation_id", operation_id,
                                       "--ez", "operation_status", "true"], deadline)
        if value["status"] != "SUCCEEDED" or not isinstance(value.get("result"), str):
            raise ControlFailure(f"Control operation failed: {value['status']}:{value.get('result')}")
        return value["result"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial")
    parser.add_argument("--expected-pid")
    parser.add_argument("--expected-process-id")
    parser.add_argument("--identity-file", type=Path)
    parser.add_argument("--evidence-directory", type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    parser.add_argument("operation", choices=("state", "profile", "native", "safety-pause", "reset", "provision", "prepare", "identity"))
    parser.add_argument("--envelope", type=Path)
    args = parser.parse_args()
    if not 0 < args.timeout_seconds <= 90:
        parser.error("Operation deadline must be in (0, 90] seconds")
    if args.operation == "provision" and args.envelope is None:
        parser.error("Provision requires --envelope")
    if args.expected_pid is not None and not re.fullmatch(r"[0-9]+", args.expected_pid):
        parser.error("Invalid expected process ID")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    control = HostControl(adb)
    control.pid = args.expected_pid
    control.process_id = args.expected_process_id
    try:
        cold = args.operation in ("reset", "provision", "prepare")
        if not cold and args.identity_file is not None:
            identity = json.loads(args.identity_file.read_text())
            if args.expected_pid is not None and args.expected_pid != identity["pid"]:
                raise ControlFailure("Pinned process ID differs from caller's original process")
            if args.expected_process_id is not None and args.expected_process_id != identity["process_id"]:
                raise ControlFailure("Pinned process UUID differs from caller's original process")
            control.pid, control.process_id = identity["pid"], identity["process_id"]
        deadline = control.clock() + args.timeout_seconds
        control.ready(start=args.operation in ("reset", "provision", "prepare"),
                      timeout=105.0 if args.operation in ("reset", "provision") else args.timeout_seconds)
        if cold and args.identity_file is not None:
            identity = {"pid": control.pid, "process_id": control.process_id}
            temporary = args.identity_file.with_name(f".{args.identity_file.name}.{uuid.uuid4()}.pending")
            temporary.write_text(json.dumps(identity, sort_keys=True) + "\n")
            temporary.replace(args.identity_file)
        remaining = args.timeout_seconds if args.operation in ("reset", "provision") else deadline - control.clock()
        if remaining <= 0:
            raise ControlFailure("Host control shared deadline expired")
        result = control.pid if args.operation in ("prepare", "identity") else control.execute(args.operation, timeout=remaining,
                                 envelope=args.envelope.read_text().strip() if args.envelope else None)
        print(result)
    except (ControlFailure, ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired) as failure:
        detail = str(failure) if isinstance(failure, ControlFailure) else type(failure).__name__
        print(f"Host control failed: {detail}", file=sys.stderr)
        raise SystemExit(1) from None
    finally:
        if args.evidence_directory is not None:
            args.evidence_directory.mkdir(parents=True, exist_ok=True)
            record = {"operation": args.operation, "pid": control.pid, "receipts": control.trace}
            with (args.evidence_directory / f"{uuid.uuid4()}.json").open("x") as stream:
                json.dump(record, stream, sort_keys=True, separators=(",", ":"))
                stream.write("\n")


if __name__ == "__main__":
    main()
