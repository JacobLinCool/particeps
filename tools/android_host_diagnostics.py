#!/usr/bin/env python3
"""Bounded, read-only diagnostics for synthetic emulator throughput measurements."""

from __future__ import annotations

import argparse
import json
import re
import signal
import subprocess
import threading
import time
from pathlib import Path

from tools.android_host_profile import compare_observations, observation_from_broadcast

PACKAGES = (
    ("target", 0, "cool.jacoblin.particeps.fixture.targeta"),
    ("target", 1, "cool.jacoblin.particeps.fixture.targetb"),
    ("control", 0, "cool.jacoblin.particeps.fixture.control"),
)
COUNTER_FIELDS = (
    "sample_started_elapsed_realtime_nanos", "sample_completed_elapsed_realtime_nanos",
    "native_generation", "uplink_bytes", "uplink_packets", "downlink_bytes",
    "downlink_packets", "uplink_throttled_nanos", "downlink_throttled_nanos",
)


def native_observation(output: str, expected: dict) -> dict:
    value = observation_from_broadcast(output)
    if value.get("status") != "VERIFIED" or value.get("admission_open") is not True:
        raise ValueError("Native counters require verified open admission")
    compare_observations(expected, value, transition=False)
    counters = value["native_counters"]
    if not isinstance(counters, dict):
        raise ValueError("Missing native counters")
    for name in COUNTER_FIELDS:
        if type(counters.get(name)) is not int or counters[name] < 0:
            raise ValueError(f"Invalid native counter {name}")
    if counters["sample_completed_elapsed_realtime_nanos"] < counters["sample_started_elapsed_realtime_nanos"]:
        raise ValueError("Native sample time moved backwards")
    if counters.get("profile_sha256") != expected["applied_profile_sha256"]:
        raise ValueError("Native counter profile mismatch")
    if not isinstance(counters.get("vpn_generation_id"), str) or not counters["vpn_generation_id"]:
        raise ValueError("Missing VPN generation")
    return value


def progress_observation(output: str) -> dict:
    value = json.loads(output)
    if not isinstance(value, dict) or value.get("schema_version") != 1:
        raise ValueError("Invalid fixture progress schema")
    if value.get("stage") not in {"CONNECTING", "AWAITING_BARRIER", "WRITING", "FINISHED"}:
        raise ValueError("Invalid fixture progress stage")
    for name in ("sample_elapsed_realtime_nanos", "completed_bytes", "completed_writes", "completed_write_nanos", "longest_write_nanos"):
        if type(value.get(name)) is not int or value[name] < 0:
            raise ValueError(f"Invalid fixture progress {name}")
    for name in ("write_started_elapsed_realtime_nanos", "current_write_elapsed_nanos", "last_completed_elapsed_realtime_nanos", "error_errno"):
        if name not in value or (value[name] is not None and (type(value[name]) is not int or value[name] < 0)):
            raise ValueError(f"Invalid fixture progress {name}")
    if value.get("error_type") is not None and not re.fullmatch(r"[A-Za-z0-9_$]+", value["error_type"]):
        raise ValueError("Fixture errors may contain only exception class names")
    return value


def kernel_observation(output: str) -> dict:
    lines = output.splitlines()
    result = {}
    for index, line in enumerate(lines):
        if line.startswith("Tcp:") and "RetransSegs" in line:
            if index + 1 == len(lines):
                raise ValueError("Missing kernel TCP counter values")
            names, values = line.split()[1:], lines[index + 1].split()[1:]
            if len(names) != len(values):
                raise ValueError("Malformed kernel TCP counters")
            fields = dict(zip(names, map(int, values), strict=True))
            result["tcp"] = {name: fields[name] for name in ("InSegs", "OutSegs", "RetransSegs", "InErrs", "OutRsts")}
        if line.startswith("tun") and ":" in line:
            name, raw = line.split(":", 1)
            values = list(map(int, raw.split()))
            if not re.fullmatch(r"tun[0-9]+", name) or len(values) != 16:
                raise ValueError("Malformed synthetic TUN counters")
            result.setdefault("tun", {})[name] = {
                "rx_bytes": values[0], "rx_packets": values[1], "rx_dropped": values[3],
                "tx_bytes": values[8], "tx_packets": values[9], "tx_dropped": values[11],
            }
    if "tcp" not in result or "tun" not in result:
        raise ValueError("Kernel TCP/TUN counters unavailable")
    return result


def process_observation(output: str) -> dict:
    lines = output.splitlines()
    found = {}
    selected = False
    for line in lines:
        if "ProcessRecord{" in line:
            selected = bool(re.search(r"\d+:cool\.jacoblin\.particeps/", line))
        if selected:
            for name, value in re.findall(
                r"\b(curAdj|setAdj|curRawAdj|setRawAdj|curProcState|setProcState|hasForegroundServices|cached|empty|frozen|freezeExempt|pendingFreeze)=([^\s,}]+)", line,
            ):
                found[name] = value
    if not found:
        raise ValueError("Particeps process importance unavailable")
    return found


def observed(command: list[str], parser, *, timeout: float = 5.0) -> dict:
    result = {"host_started_monotonic_ns": time.monotonic_ns()}
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
        if completed.returncode != 0:
            result.update(status="command_failed", returncode=completed.returncode)
        else:
            try:
                result.update(status="ok", value=parser(completed.stdout))
            except (ValueError, KeyError, TypeError) as failure:
                result.update(status="invalid_observation", error_type=type(failure).__name__)
    except subprocess.TimeoutExpired:
        result.update(status="timeout")
    except OSError as failure:
        result.update(status="command_unavailable", error_errno=failure.errno)
    result["host_completed_monotonic_ns"] = time.monotonic_ns()
    return result


def monitor(adb: list[str], expected: dict, output: Path, stop_file: Path, *, maximum_seconds: float = 120.0) -> None:
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    started = time.monotonic()
    deadline = started + maximum_seconds

    def read(command: list[str], parser) -> dict:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            now = time.monotonic_ns()
            return {"status": "sampling_deadline", "host_started_monotonic_ns": now, "host_completed_monotonic_ns": now}
        return observed(command, parser, timeout=min(5.0, remaining))

    with output.open("x") as stream:
        while not stopped.is_set() and time.monotonic() < deadline:
            sampled = time.monotonic()
            native = read(adb + [
                "shell", "am", "broadcast", "--include-stopped-packages", "--receiver-foreground",
                "-a", "cool.jacoblin.particeps.HOST_HARNESS_QUERY", "-p", "cool.jacoblin.particeps",
                "--ez", "include_applied_profile", "true", "--ez", "include_native_counters", "true",
            ], lambda value: native_observation(value, expected))
            fixtures = []
            for role, index, package in PACKAGES:
                fixtures.append({"role": role, "index": index, **read(
                    adb + ["shell", "run-as", package, "cat", "files/saturation-progress.json"],
                    progress_observation,
                )})
            kernel = read(adb + ["shell", "cat /proc/net/snmp; cat /proc/net/dev | sed -n '/tun[0-9]:/s/^[[:space:]]*//p'"], kernel_observation)
            process = read(adb + ["shell", "dumpsys", "activity", "processes"], process_observation)
            stream.write(json.dumps({"native": native, "fixtures": fixtures, "kernel": kernel, "process": process}, sort_keys=True, separators=(",", ":")) + "\n")
            stream.flush()
            if stop_file.exists():
                break
            # Slow queries leave a visible sampling gap; never synthesize missing observations.
            stopped.wait(max(0.0, min(sampled + 1.0, deadline) - time.monotonic()))


def capture(adb: list[str], output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for name, arguments in (
        ("logcat.txt", ["logcat", "-d", "-v", "threadtime"]),
        ("processes.txt", ["shell", "ps", "-A"]),
        ("tcp-counters.txt", ["shell", "cat", "/proc/net/snmp"]),
        ("interface-counters.txt", ["shell", "cat", "/proc/net/dev"]),
    ):
        destination = output / name
        with destination.open("wb") as stream:
            try:
                result = subprocess.run(adb + arguments, stdout=stream, stderr=subprocess.STDOUT, timeout=15, check=False)
                results.append({"file": name, "status": "ok" if result.returncode == 0 else "command_failed", "returncode": result.returncode})
            except subprocess.TimeoutExpired:
                results.append({"file": name, "status": "timeout"})
            except OSError as failure:
                results.append({"file": name, "status": "command_unavailable", "error_errno": failure.errno})
    (output / "capture-result.json").write_text(json.dumps(results, sort_keys=True) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    commands = parser.add_subparsers(dest="operation", required=True)
    sample = commands.add_parser("monitor")
    sample.add_argument("--expected", type=Path, required=True)
    sample.add_argument("--output", type=Path, required=True)
    sample.add_argument("--stop-file", type=Path, required=True)
    sample.add_argument("--maximum-seconds", type=int, choices=(120, 360), required=True)
    snapshot = commands.add_parser("capture")
    snapshot.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Synthetic diagnostics require an explicit emulator serial")
    adb = [args.adb, "-s", args.serial]
    if args.operation == "monitor":
        monitor(adb, json.loads(args.expected.read_text()), args.output, args.stop_file, maximum_seconds=args.maximum_seconds)
    else:
        capture(adb, args.output)


if __name__ == "__main__":
    main()
