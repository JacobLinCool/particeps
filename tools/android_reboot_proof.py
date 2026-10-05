#!/usr/bin/env python3
"""Qualify one ordinary app launch after an owned synthetic-device reboot."""

from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import uuid
from pathlib import Path

from tools.android_host_control import ControlFailure, HostControl, PACKAGE

BOOT_ID_COMMAND = ["shell", "cat", "/proc/sys/kernel/random/boot_id"]
EVENTS_COMMAND = ["logcat", "-b", "events", "-d", "-v", "threadtime", "-v", "epoch"]
EVENT_TAGS = {"am_proc_start", "am_proc_died", "am_anr", "am_crash"}
EVENT_LINE = re.compile(r"^\s*([0-9]+\.[0-9]+)\s+[0-9]+\s+[0-9]+\s+[VDIWEFAS]\s+(\w+)\s*:\s?(.*)$")


def boot_identity(raw: str) -> str:
    value = raw.strip()
    if str(uuid.UUID(value)) != value:
        raise ControlFailure("Invalid device boot identity")
    return value


def safety_pause_proof(raw: str) -> dict:
    def unique_fields(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ControlFailure("Duplicate safety-pause proof field")
            value[key] = item
        return value

    value = json.loads(raw, object_pairs_hook=unique_fields)
    if not isinstance(value, dict) or set(value) != {"schema_version", "status", "observations"}:
        raise ControlFailure("Invalid safety-pause proof fields")
    if type(value["schema_version"]) is not int or value["schema_version"] != 1 or value["status"] != "VERIFIED_SAFETY_PAUSED":
        raise ControlFailure("Safety-pause proof was not verified")
    observations = value["observations"]
    fields = {"initialized", "state", "lifetime_data_event_count", "elapsed_realtime_millis",
              "notification_tag", "notification_body", "expected_notification_body"}
    if not isinstance(observations, list) or len(observations) != 2:
        raise ControlFailure("Safety-pause proof requires exactly two observations")
    for observation in observations:
        if not isinstance(observation, dict) or set(observation) != fields:
            raise ControlFailure("Invalid safety-pause observation fields")
        if observation["initialized"] is not True or observation["state"] != "PAUSED":
            raise ControlFailure("Study is not initialized and safety-paused")
        for field in ("lifetime_data_event_count", "elapsed_realtime_millis"):
            if type(observation[field]) is not int or not 0 <= observation[field] <= 2**63 - 1:
                raise ControlFailure(f"Invalid safety-pause {field}")
        body, expected = observation["notification_body"], observation["expected_notification_body"]
        if observation["notification_tag"] != "particeps-recovery" or not isinstance(body, str) or not body.strip() or body != expected:
            raise ControlFailure("Required recovery notification was not observed")
    first, last = observations
    if last["elapsed_realtime_millis"] - first["elapsed_realtime_millis"] < 1000:
        raise ControlFailure("Safety-pause observations are less than one second apart")
    if first["lifetime_data_event_count"] != last["lifetime_data_event_count"]:
        raise ControlFailure("Data admission advanced while safety-paused")
    return value


def process_events(raw: str, pinned_pid: str) -> list[dict]:
    """Read AOSP EventLogTags fields, not human ActivityManager prose.

    am_proc_start is [user,pid,uid,process,type,component]; the other three tags
    start [user,pid,process,...]. These prefixes are specified in frameworks/base
    services/core/java/com/android/server/am/EventLogTags.logtags and observed in
    the stock API 37 all-buffer capture. The explicit threadtime+epoch format is
    defined by system/logging/liblog/logprint.cpp. No wall-time filtering hides
    earlier process failures: this dump belongs to the newly verified boot.
    """
    if not re.fullmatch(r"[0-9]+", pinned_pid):
        raise ControlFailure("Missing pinned application process")
    events = []
    for line in raw.splitlines():
        if not line or line.startswith("--------- beginning of "):
            continue
        match = EVENT_LINE.fullmatch(line)
        if match is None:
            if any(re.search(rf"\b{tag}\s*:", line) for tag in EVENT_TAGS):
                raise ControlFailure("Unrecognized process event format")
            continue
        timestamp, tag, payload = match.groups()
        if tag not in EVENT_TAGS:
            continue
        if tag == "am_proc_start":
            prefix = re.match(r"^\[([0-9]+),([0-9]+),([0-9]+),([^,\[\]\s]+),", payload)
        else:
            prefix = re.match(r"^\[([0-9]+),([0-9]+),([^,\[\]\s]+),", payload)
        if prefix is None:
            raise ControlFailure("Malformed process event fields")
        user, pid, *tail = prefix.groups()
        process = tail[-1]
        if process != PACKAGE and not process.startswith(PACKAGE + ":"):
            continue
        event = {"epoch_seconds": timestamp, "tag": tag, "user": int(user), "pid": pid, "process": process}
        events.append(event)
        if tag != "am_proc_start":
            raise ControlFailure(f"Application process failure observed in this boot: {tag}")
    starts = [event for event in events if event["process"] == PACKAGE]
    if len(starts) != 1 or starts[0]["pid"] != pinned_pid or starts[0]["user"] != 0:
        raise ControlFailure("This boot must contain exactly one matching main-process start")
    return events


def qualify(control: HostControl, previous_boot_id: str, output: Path, timeout: float = 105.0) -> dict:
    if not math.isfinite(timeout) or not 0 < timeout <= 105:
        raise ValueError("Qualification deadline must be in (0, 105] seconds")
    previous_boot_id = boot_identity(previous_boot_id)
    output.mkdir(parents=True, exist_ok=False)
    started = control.clock()
    deadline = started + timeout
    record = {"schema_version": 1, "status": "FAILED", "previous_boot_id": previous_boot_id,
              "timeout_seconds": timeout,
              "scope": "One owned synthetic reboot; current-boot events, one ordinary MainActivity launch, no instrumentation or recovery retry."}

    def remaining(maximum: float = 90.0) -> float:
        left = deadline - control.clock()
        if left <= 0:
            raise ControlFailure("Reboot qualification shared deadline expired")
        return min(maximum, left)

    def capture_events(capture_deadline: float, filename: str) -> str:
        events = control.command(EVENTS_COMMAND, capture_deadline, 10.0)
        (output / filename).write_text(events)
        return events

    try:
        boot_before = boot_identity(control.command(BOOT_ID_COMMAND, deadline))
        record["boot_id"] = boot_before
        if boot_before == previous_boot_id:
            raise ControlFailure("Device has not entered a new boot")
        control.ready(start=True, timeout=remaining())
        raw_proof = control.execute("safety-pause", timeout=remaining())
        (output / "safety-pause-proof.json").write_text(raw_proof + "\n")
        proof = safety_pause_proof(raw_proof)
        events = capture_events(deadline, "events.txt")
        record["process_events"] = process_events(events, control.pid)
        boot_after = boot_identity(control.command(BOOT_ID_COMMAND, deadline))
        record["final_boot_id"] = boot_after
        if boot_after != boot_before:
            raise ControlFailure("Device rebooted again during qualification")
        control.check_pid(deadline)
        remaining()
        record.update(status="VERIFIED_SAFETY_PAUSED", pid=control.pid, process_id=control.process_id,
                      proof=proof)
    except (ControlFailure, ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired) as failure:
        record["failure"] = str(failure) if isinstance(failure, ControlFailure) else type(failure).__name__
        # A separate bounded diagnostic capture cannot turn expired qualification into PASS.
        # Do not launch, re-admit an operation, or replace the original failure if capture fails.
        try:
            capture_events(control.clock() + 10.0, "failure-events.txt")
        except (ControlFailure, OSError, subprocess.TimeoutExpired) as diagnostic_failure:
            record["diagnostic_capture_failure"] = type(diagnostic_failure).__name__
        raise
    finally:
        original_failure = sys.exc_info()[1]
        record["elapsed_host_seconds"] = control.clock() - started
        record["receipts"] = control.trace
        try:
            (output / "result.json").write_text(json.dumps(record, sort_keys=True, indent=2) + "\n")
        except OSError as evidence_failure:
            if original_failure is None:
                raise
            print(f"Reboot proof evidence could not be saved: {type(evidence_failure).__name__}", file=sys.stderr)
    return record


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial")
    parser.add_argument("--previous-boot-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=105.0)
    args = parser.parse_args()
    if not math.isfinite(args.timeout_seconds) or not 0 < args.timeout_seconds <= 105:
        parser.error("Qualification deadline must be in (0, 105] seconds")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    try:
        result = qualify(HostControl(adb), args.previous_boot_id, args.output, args.timeout_seconds)
        print(json.dumps(result, sort_keys=True))
    except (ControlFailure, ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired) as failure:
        detail = str(failure) if isinstance(failure, ControlFailure) else type(failure).__name__
        print(f"Reboot proof failed: {detail}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
