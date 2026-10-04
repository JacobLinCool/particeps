#!/usr/bin/env python3
"""Bound cleanup of the Linux CI runner's own emulator and temporary AVD."""

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time


COMMAND_TIMEOUT_SECONDS = 5
TERM_GRACE_SECONDS = 5
KILL_GRACE_SECONDS = 2


def process_identity(stat: str) -> tuple[int, int, int, str]:
    pid, separator, rest = stat.partition(" (")
    _, closing, fields = rest.rpartition(") ")
    values = fields.split()
    if not separator or not closing or len(values) < 20:
        raise ValueError("Malformed Linux process identity")
    return int(pid), int(values[1]), int(values[19]), values[0]


def owned_state(pid: int, parent_pid: int, saved_identity: tuple[int, int, int, str]) -> str:
    try:
        current = process_identity(Path(f"/proc/{pid}/stat").read_text())
    except FileNotFoundError:
        return "gone"
    if saved_identity[:2] != (pid, parent_pid) or current[:3] != saved_identity[:3]:
        raise RuntimeError("Emulator PID no longer matches this runner's child identity; refusing to signal it")
    return "exited" if current[3] == "Z" else "alive"


def signal_owned(pid: int, parent_pid: int, identity: tuple[int, int, int, str], sig: int) -> None:
    # Linux pidfds retain the original process identity even if it exits and its PID is reused.
    try:
        descriptor = os.pidfd_open(pid)
    except ProcessLookupError:
        return
    try:
        if owned_state(pid, parent_pid, identity) == "alive":
            try:
                signal.pidfd_send_signal(descriptor, sig)
            except ProcessLookupError:
                pass
    finally:
        os.close(descriptor)


def bounded_command(command: list[str], evidence: Path, label: str) -> dict:
    result = {"phase": label, "command": command, "timeout_seconds": COMMAND_TIMEOUT_SECONDS}
    started = time.monotonic()
    with (evidence / f"{label}.stdout.txt").open("wb") as stdout, (evidence / f"{label}.stderr.txt").open("wb") as stderr:
        try:
            process = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True)
        except OSError as error:
            result.update(status="error", error=str(error), elapsed_seconds=time.monotonic() - started)
            return result
        try:
            result["returncode"] = process.wait(timeout=COMMAND_TIMEOUT_SECONDS)
            result["status"] = "ok" if result["returncode"] == 0 else "command_failed"
        except subprocess.TimeoutExpired:
            result["status"] = "timeout"
            # This process group belongs to the command we just created, not the emulator.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                result["returncode"] = process.wait(timeout=KILL_GRACE_SECONDS)
            except subprocess.TimeoutExpired:
                result["reap_timed_out"] = True
    result["elapsed_seconds"] = time.monotonic() - started
    return result


def cleanup(*, adb: str, avdmanager: str, avd_name: str, emulator_pid: int | None,
            parent_pid: int, identity_file: Path, evidence: Path, original_exit: int) -> int:
    evidence.mkdir(parents=True, exist_ok=True)
    ready_to_reap = evidence / "emulator-ready-to-reap"
    ready_to_reap.unlink(missing_ok=True)
    events = []
    avd_deleted = False

    def record(event: dict) -> None:
        events.append(event)
        (evidence / "events.json").write_text(json.dumps(events, indent=2) + "\n")

    def wait_for_exit(identity: tuple[int, int, int, str], seconds: float) -> bool:
        deadline = time.monotonic() + seconds
        while True:
            if owned_state(emulator_pid, parent_pid, identity) != "alive":
                ready_to_reap.write_text("Owned child exited; parent may reap without waiting.\n")
                return True
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(0.05, remaining))

    if emulator_pid is not None:
        try:
            identity = process_identity(identity_file.read_text())
            state = owned_state(emulator_pid, parent_pid, identity)
            record({"phase": "emulator_identity", "pid": emulator_pid, "state": state})
            if state == "alive":
                event = bounded_command([adb, "-s", "emulator-5554", "emu", "avd", "name"], evidence, "console-identity")
                if event["status"] == "ok" and (evidence / "console-identity.stdout.txt").read_text().splitlines() != [avd_name, "OK"]:
                    event["status"] = "identity_mismatch"
                record(event)
                if event["status"] == "ok" and owned_state(emulator_pid, parent_pid, identity) == "alive":
                    event = bounded_command([adb, "-s", "emulator-5554", "emu", "kill"], evidence, "console-kill")
                    record(event)
                if owned_state(emulator_pid, parent_pid, identity) == "alive":
                    signal_owned(emulator_pid, parent_pid, identity, signal.SIGTERM)
                    record({"phase": "emulator_term", "pid": emulator_pid})
                if not wait_for_exit(identity, TERM_GRACE_SECONDS):
                    record({"phase": "emulator_term_wait", "status": "timeout", "timeout_seconds": TERM_GRACE_SECONDS})
                    # Recheck the original child token immediately before the escalation.
                    if owned_state(emulator_pid, parent_pid, identity) == "alive":
                        signal_owned(emulator_pid, parent_pid, identity, signal.SIGKILL)
                        record({"phase": "emulator_kill", "pid": emulator_pid})
                    if not wait_for_exit(identity, KILL_GRACE_SECONDS):
                        record({"phase": "emulator_kill_wait", "status": "timeout", "timeout_seconds": KILL_GRACE_SECONDS})
            else:
                ready_to_reap.write_text("Owned child already exited; parent may reap without waiting.\n")
        except (OSError, ValueError, RuntimeError) as error:
            record({"phase": "emulator_cleanup", "status": "error", "error": str(error)})
    # Deleting a running/unknown AVD can race its writes. No PID means launch never happened.
    emulator_stopped = emulator_pid is None or ready_to_reap.is_file()
    if emulator_stopped:
        try:
            event = bounded_command([avdmanager, "delete", "avd", "--name", avd_name], evidence, "avd-delete")
            record(event)
            avd_deleted = event["status"] == "ok"
        except OSError as error:
            record({"phase": "avd-delete", "status": "error", "error": str(error)})
    else:
        record({"phase": "avd-delete", "status": "skipped", "reason": "Owned emulator exit was not confirmed"})
    commands_reaped = not any(event.get("reap_timed_out") for event in events)
    failed = not (emulator_stopped and avd_deleted and commands_reaped)
    result = original_exit if original_exit != 0 else int(failed)
    record({"phase": "result", "original_exit": original_exit, "emulator_stopped": emulator_stopped,
            "avd_deleted": avd_deleted, "commands_reaped": commands_reaped, "cleanup_failed": failed, "exit": result})
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--avdmanager", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--emulator-pid", type=int)
    parser.add_argument("--parent-pid", type=int, required=True)
    parser.add_argument("--identity-file", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--original-exit", type=int, required=True)
    raise SystemExit(cleanup(**vars(parser.parse_args())))


if __name__ == "__main__":
    main()
