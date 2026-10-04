#!/usr/bin/env python3
"""Establish the foreground UI prerequisites on the isolated API 34 CI emulator."""

import argparse
import re
import shutil
import subprocess
import time
from pathlib import Path


STOCK_HOME_PACKAGE = "com.google.android.apps.nexuslauncher"
STOCK_HOME = f"{STOCK_HOME_PACKAGE}/{STOCK_HOME_PACKAGE}.NexusLauncherActivity"


def home_process_state(processes: str) -> tuple[int, bool]:
    """Read Android 14's current records, including ANRs whose dialog is still pending.

    ProcessErrorStateRecord sets mNotResponding before collecting ANR traces and posting
    the dialog. Its dump omits the entire error line for a healthy process. Require the
    surrounding complete process dump rather than treating an empty response as healthy.
    """
    if not processes.startswith("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"):
        raise RuntimeError("Missing Android 14 process dump header")
    records = list(re.finditer(
        r"^  \*(?:APP|PERS)\* UID \d+ ProcessRecord\{[^\s{}]+ (\d+):([^/\s]+)/[^}\s]+\}[ \t]*$",
        processes, re.M,
    ))
    if not records or len(records) != len(re.findall(r"^  \*(?:APP|PERS)\*", processes, re.M)):
        raise RuntimeError("Missing or ambiguous Android 14 process records")
    home = []
    accounted_errors = 0
    for index, record in enumerate(records):
        body = processes[record.end():records[index + 1].start() if index + 1 < len(records) else len(processes)]
        boundary = re.search(r"^ {0,3}\S", body, re.M)
        if boundary:
            body = body[:boundary.start()]
        name = record[2]
        errors = re.findall(r"^\s+mCrashing=(true|false) (.*?) mNotResponding=(true|false) (.*?) bad=(true|false)(?: errorReportReceiver=\S+)?\s*$", body, re.M)
        accounted_errors += len(errors)
        if len(errors) > 1 or len(re.findall(r"^    user #\d+ uid=", body, re.M)) != 1:
            raise RuntimeError(f"Incomplete or ambiguous process state: {name}")
        anr = False
        if errors:
            crashing, crash_dialogs, not_responding, _, bad = errors[0]
            if name != STOCK_HOME_PACKAGE or crashing != "false" or crash_dialogs != "null" or not_responding != "true" or bad != "false":
                raise RuntimeError(f"Unexpected process error state: {name}")
            anr = True
        if name == STOCK_HOME_PACKAGE:
            if re.findall(r"^    user #(\d+) uid=", body, re.M) != ["0"]:
                raise RuntimeError("Stock HOME is not running as user 0")
            home.append((int(record[1]), anr))
    if any(accounted_errors != len(re.findall(rf"\b{field}=", processes)) for field in ("mCrashing", "mNotResponding")):
        raise RuntimeError("Unrecognized process error state")
    if len(home) != 1 or home[0][0] <= 0:
        raise RuntimeError("Expected one live stock HOME process")
    return home[0]


def home_focus(displays: str) -> str:
    """Use only the live displays dump; a full window dump also contains historical ANRs."""
    if not displays.startswith("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"):
        raise RuntimeError("Missing live window display header")
    if re.findall(r"^\s*Display: mDisplayId=(\d+)\b", displays, re.M) != ["0"]:
        raise RuntimeError("Expected exactly one display, display 0")
    focuses = re.findall(r"^\s*mCurrentFocus=(.+)$", displays, re.M)
    if len(focuses) != 1:
        raise RuntimeError("Missing or ambiguous live window focus")
    if focuses == ["null"]:
        return "none"
    match = re.fullmatch(r"Window\{[^\s{}]+ u0 (.+)\}", focuses[0])
    if match and match[1] == STOCK_HOME:
        return "home"
    if match and match[1] == f"Application Not Responding: {STOCK_HOME_PACKAGE}":
        return "stock_anr"
    raise RuntimeError(f"Unexpected foreground window: {focuses[0]}")


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
    deadline = time.monotonic() + timeout_seconds

    def remaining() -> float:
        budget = deadline - time.monotonic()
        if budget <= 0:
            raise RuntimeError("API 34 UI precondition exceeded its total time budget")
        return budget

    def command(*arguments: str) -> str:
        return subprocess.run(
            [*target, *arguments], check=True, capture_output=True, text=True, timeout=min(15, remaining()),
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

    def snapshot(prefix: str) -> tuple[list[str], str, str]:
        def read(name: str, *arguments: str) -> str:
            content = command(*arguments)
            (evidence / f"{prefix}-{name}.txt").write_text(content + "\n")
            return content

        power = read("power", "shell", "dumpsys", "power")
        policy = read("window-policy", "shell", "dumpsys", "window", "policy")
        user_state = read("user-state", "shell", "am", "get-started-user-state", "0")
        displays = read("window-displays", "shell", "dumpsys", "window", "displays")
        processes = read("processes", "shell", "dumpsys", "activity", "-a", "processes")
        return ui_state_problems(power, policy, user_state), displays, processes

    _, _, before_processes = snapshot("before")
    home_process_state(before_processes)
    # These commands use the normal wake/dismiss path. Do not change power policy, disable
    # keyguard, or grant access; later background and screen-state tests keep their own semantics.
    command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    command("shell", "wm", "dismiss-keyguard")
    # Android 14 --brief includes resolution metadata; --components emits only the component.
    resolved = command("shell", "cmd", "package", "resolve-activity", "--components", "--user", "0",
                       "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    (evidence / "resolved-home.txt").write_text(resolved + "\n")
    if resolved not in (STOCK_HOME, f"{STOCK_HOME_PACKAGE}/.NexusLauncherActivity"):
        raise RuntimeError(f"Unexpected default HOME: {resolved!r}")
    recovered_pid = None
    started_home = False
    sequence = 0

    def start_home() -> None:
        result = command("shell", "am", "start", "-W", "--user", "0", "-n", STOCK_HOME,
                         "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        (evidence / f"home-start-{sequence:03d}.txt").write_text(result + "\n")
        if re.findall(r"^Status: (\S+)\s*$", result, re.M) != ["ok"]:
            raise RuntimeError("Normal HOME launch did not report Status: ok")

    while True:
        prefix = f"state-{sequence:03d}"
        sequence += 1
        problems, displays, processes = snapshot(prefix)
        pid, anr = home_process_state(processes)
        focus = home_focus(displays)
        if anr:
            if recovered_pid is not None:
                raise RuntimeError("Stock HOME ANR persisted or recurred after the one allowed recovery")
            recovered_pid = pid
            (evidence / "stock-home-recovery.txt").write_text(f"Confirmed stock HOME ANR, pid={pid}; one normal force-stop and HOME launch.\n")
            command("shell", "am", "force-stop", "--user", "0", STOCK_HOME_PACKAGE)
            start_home()
            started_home = True
            continue
        if focus == "stock_anr":
            raise RuntimeError("Stock ANR dialog has no matching current process error state")
        if recovered_pid == pid:
            raise RuntimeError("Stock HOME recovery did not replace the failing process")
        if not problems and not started_home:
            start_home()
            started_home = True
            continue
        if not problems and focus == "home":
            remaining()
            for source in evidence.glob(f"{prefix}-*.txt"):
                shutil.copyfile(source, evidence / source.name.replace(prefix, "after", 1))
            (evidence / "result.txt").write_text("PASS: API 34 emulator awake; user 0 unlocked; unsecured keyguard dismissed; live stock HOME focused without process errors.\n")
            return
        (evidence / "pending-problems.txt").write_text("; ".join(problems + (["HOME has no focus"] if focus != "home" else [])) + "\n")
        time.sleep(min(0.1, remaining()))


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
