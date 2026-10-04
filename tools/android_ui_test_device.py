#!/usr/bin/env python3
"""Establish the foreground UI prerequisites on the isolated API 34 CI emulator."""

import argparse
import re
import shutil
import subprocess
import time
from pathlib import Path


def ui_state_problems(power: str, policy: str, user_state: str) -> list[str]:
    """Fail closed on missing/ambiguous dumpsys fields, including a hidden secure keyguard."""
    problems = []
    for name, actual, expected in (
        ("power wakefulness", re.findall(r"^\s*mWakefulness=(\S+)\s*$", power, re.M), ["Awake"]),
        ("interactive HAL", re.findall(r"^\s*mHalInteractiveModeEnabled=(\S+)\s*$", power, re.M), ["true"]),
        ("user 0", [user_state.strip()], ["RUNNING_UNLOCKED"]),
    ):
        if actual != expected:
            problems.append(f"{name}: expected {expected}, got {actual}")
    delegate = re.search(r"^\s*KeyguardServiceDelegate\s*\n(?P<state>.*?)^\s*KeyguardStateMonitor\s*$", policy, re.M | re.S)
    for name in ("showing", "inputRestricted", "secure"):
        actual = re.findall(rf"^\s*{name}=(\S+)\s*$", delegate["state"] if delegate else "", re.M)
        if actual != ["false"]:
            problems.append(f"keyguard {name}: expected false, got {actual}")
    return problems


def prepare(adb: str, evidence: Path, timeout_seconds: float = 30) -> None:
    evidence.mkdir(parents=True, exist_ok=True)
    for marker in ("result.txt", "device-serial.txt"):
        (evidence / marker).unlink(missing_ok=True)
    target = [adb]

    def command(*arguments: str) -> str:
        return subprocess.run(
            [*target, *arguments], check=True, capture_output=True, text=True, timeout=15,
        ).stdout.strip()

    # Do not wake/unlock a physical phone or apply this API-specific policy to another lane.
    serial = command("get-serialno")
    if not re.fullmatch(r"emulator-\d+", serial):
        raise RuntimeError(f"UI preparation requires an isolated emulator, got {serial!r}")
    target.extend(("-s", serial))
    if command("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("UI preparation requires ro.kernel.qemu=1")
    if command("shell", "getprop", "ro.build.version.sdk") != "34":
        raise RuntimeError("UI preparation is only defined for the API 34 product lane")
    (evidence / "device-serial.txt").write_text(serial + "\n")

    def snapshot(prefix: str) -> list[str]:
        power = command("shell", "dumpsys", "power")
        policy = command("shell", "dumpsys", "window", "policy")
        user_state = command("shell", "am", "get-started-user-state", "0")
        for name, content in (("power", power), ("window-policy", policy), ("user-state", user_state)):
            (evidence / f"{prefix}-{name}.txt").write_text(content + "\n")
        return ui_state_problems(power, policy, user_state)

    snapshot("before")
    # These commands use the normal wake/dismiss path. Do not change power policy, disable
    # keyguard, or grant access; later background and screen-state tests keep their own semantics.
    command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    command("shell", "wm", "dismiss-keyguard")
    deadline = time.monotonic() + timeout_seconds
    while True:
        problems = snapshot("after")
        if not problems:
            (evidence / "result.txt").write_text("PASS: API 34 emulator awake; user 0 unlocked; unsecured keyguard dismissed.\n")
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("API 34 UI precondition failed: " + "; ".join(problems))
        time.sleep(0.1)


def capture(adb: str, evidence: Path, serial_file: Path, repository: Path) -> None:
    """Collect best-effort diagnostics without retrying a failed test or waiting on a dead device."""
    evidence.mkdir(parents=True, exist_ok=True)
    errors = []
    serial = serial_file.read_text().strip() if serial_file.is_file() else ""
    if not re.fullmatch(r"emulator-\d+", serial):
        errors.append("No validated API 34 emulator serial; device diagnostics were not collected.")
    else:
        for name, arguments in (
            ("logcat.txt", ("logcat", "-b", "all", "-d", "-v", "threadtime")),
            ("window.txt", ("shell", "dumpsys", "window")),
            ("power.txt", ("shell", "dumpsys", "power")),
            ("activities.txt", ("shell", "dumpsys", "activity", "activities")),
            ("screenshot.png", ("exec-out", "screencap", "-p")),
        ):
            try:
                result = subprocess.run([adb, "-s", serial, *arguments], capture_output=True, timeout=15)
                (evidence / name).write_bytes(result.stdout)
                if result.returncode != 0 or result.stderr:
                    (evidence / f"{name}.stderr.txt").write_bytes(result.stderr)
                    errors.append(f"{name}: adb exit {result.returncode}")
            except subprocess.TimeoutExpired as failure:
                (evidence / name).write_bytes(failure.stdout or b"")
                errors.append(f"{name}: adb timed out after 15 seconds")
            except OSError as failure:
                errors.append(f"{name}: {failure}")
    # Android modules are one or two directory levels below the root (app, core/storage, etc.).
    # Copy the original paths so similarly named module reports cannot overwrite each other.
    for module_pattern in ("*", "*/*"):
        for report_path in ("build/outputs/androidTest-results/connected", "build/reports/androidTests/connected"):
            for source in sorted(repository.glob(f"{module_pattern}/{report_path}")):
                if source.is_dir():
                    destination = evidence / "connected-reports" / source.relative_to(repository)
                    try:
                        shutil.copytree(source, destination, dirs_exist_ok=True)
                    except OSError as failure:
                        errors.append(f"{source.relative_to(repository)}: {failure}")
    (evidence / "capture-errors.txt").write_text("\n".join(errors) + ("\n" if errors else ""))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "capture"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--evidence-directory", type=Path, required=True)
    parser.add_argument("--serial-file", type=Path)
    parser.add_argument("--repository", type=Path)
    arguments = parser.parse_args()
    if arguments.operation == "prepare":
        prepare(arguments.adb, arguments.evidence_directory)
    else:
        if arguments.serial_file is None or arguments.repository is None:
            parser.error("capture requires --serial-file and --repository")
        capture(arguments.adb, arguments.evidence_directory, arguments.serial_file, arguments.repository)


if __name__ == "__main__":
    main()
