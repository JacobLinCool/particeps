#!/usr/bin/env python3
"""Count host-test fixture bytes without retaining traffic or network identifiers."""

from __future__ import annotations

import argparse
import json
import os
import socket
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

IP_MTU_BYTES = 1_500
TARGET_CONNECTIONS = 2
CONTROL_CONNECTIONS = 1
ACCEPT_TIMEOUT_SECONDS = 20.0
IO_POLL_SECONDS = 0.5


@dataclass
class ByteCounter:
    value: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add(self, amount: int) -> None:
        with self.lock:
            self.value += amount


def throughput_bounds(cap_kbps: int, duration_seconds: int) -> tuple[int, int]:
    expected = cap_kbps * 1_000 * duration_seconds // 8
    lower = expected * 90 // 100
    upper = expected * 105 // 100 + IP_MTU_BYTES
    return lower, upper


def validate_measurement(
    cap_kbps: int,
    duration_seconds: int,
    target_bytes: int,
    control_bytes: int,
) -> tuple[bool, int, int]:
    lower, upper = throughput_bounds(cap_kbps, duration_seconds)
    return lower <= target_bytes <= upper and control_bytes > upper, lower, upper


def accept_connections(listener: socket.socket, expected: int) -> list[socket.socket]:
    listener.settimeout(ACCEPT_TIMEOUT_SECONDS)
    connections: list[socket.socket] = []
    try:
        while len(connections) < expected:
            connection, _ = listener.accept()
            connection.settimeout(IO_POLL_SECONDS)
            connections.append(connection)
        return connections
    except BaseException:
        for connection in connections:
            connection.close()
        raise


def receive_until(connection: socket.socket, deadline: float, counter: ByteCounter) -> None:
    while time.monotonic() < deadline:
        try:
            chunk = connection.recv(64 * 1024)
        except TimeoutError:
            continue
        except OSError:
            return
        if not chunk:
            return
        counter.add(len(chunk))


def atomic_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with temporary.open("x", encoding="utf-8") as output:
        json.dump(value, output, sort_keys=True, separators=(",", ":"))
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def run(args: argparse.Namespace) -> bool:
    target_listener = socket.create_server(("0.0.0.0", args.target_port), reuse_port=False)
    control_listener = socket.create_server(("0.0.0.0", args.control_port), reuse_port=False)
    target_listener.listen(TARGET_CONNECTIONS)
    control_listener.listen(CONTROL_CONNECTIONS)
    Path(args.ready).touch(exist_ok=False)

    connections: list[socket.socket] = []
    try:
        target = accept_connections(target_listener, TARGET_CONNECTIONS)
        control = accept_connections(control_listener, CONTROL_CONNECTIONS)
        connections = target + control
        target_counter = ByteCounter()
        control_counter = ByteCounter()
        deadline = time.monotonic() + args.duration_seconds
        workers = [
            threading.Thread(target=receive_until, args=(connection, deadline, target_counter))
            for connection in target
        ] + [
            threading.Thread(target=receive_until, args=(connection, deadline, control_counter))
            for connection in control
        ]
        for worker in workers:
            worker.start()
        for connection in connections:
            connection.sendall(b"\x01")
        for worker in workers:
            worker.join(args.duration_seconds + IO_POLL_SECONDS * 4)
        passed, lower, upper = validate_measurement(
            args.cap_kbps,
            args.duration_seconds,
            target_counter.value,
            control_counter.value,
        )
        atomic_json(
            Path(args.output),
            {
                "cap_kbps": args.cap_kbps,
                "control_bytes": control_counter.value,
                "control_connections": len(control),
                "duration_seconds": args.duration_seconds,
                "lower_bound_bytes": lower,
                "passed": passed,
                "target_bytes": target_counter.value,
                "target_connections": len(target),
                "upper_bound_bytes": upper,
            },
        )
        return passed
    finally:
        for connection in connections:
            connection.close()
        target_listener.close()
        control_listener.close()


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--cap-kbps", type=int, required=True, choices=(64, 512, 4096))
    value.add_argument("--control-port", type=int, required=True)
    value.add_argument("--duration-seconds", type=int, default=60, choices=(60,))
    value.add_argument("--output", required=True)
    value.add_argument("--ready", required=True)
    value.add_argument("--target-port", type=int, required=True)
    return value


def main() -> int:
    args = parser().parse_args()
    if args.target_port == args.control_port:
        raise SystemExit("fixture listener ports must differ")
    return 0 if run(args) else 1


if __name__ == "__main__":
    raise SystemExit(main())
