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
LIVENESS_WARMUP_SECONDS = 10
MAX_CONSECUTIVE_ZERO_PAYLOAD_SECONDS = 5


@dataclass
class ByteCounter:
    value: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add(self, amount: int) -> None:
        with self.lock:
            self.value += amount


@dataclass
class ConnectionMetrics:
    """Per-role connection index and host-relative timing, without network identifiers.

    Barrier time is recorded after sendall returns, which does not assert that the
    Android client has received the byte. Each receive bucket uses its completion time.
    """

    role: str
    index: int
    received_bytes: int = 0
    barrier_sent_seconds: float | None = None
    first_byte_seconds: float | None = None
    last_byte_seconds: float | None = None
    ended_seconds: float | None = None
    end_reason: str | None = None
    error_errno: int | None = None
    bytes_by_second: dict[int, int] = field(default_factory=dict)

    def received(self, amount: int, elapsed: float) -> None:
        self.received_bytes += amount
        if self.first_byte_seconds is None:
            self.first_byte_seconds = elapsed
        self.last_byte_seconds = elapsed
        second = int(elapsed)
        self.bytes_by_second[second] = self.bytes_by_second.get(second, 0) + amount

    def document(self) -> dict[str, object]:
        return {
            "role": self.role,
            "index": self.index,
            "received_bytes": self.received_bytes,
            "barrier_sent_seconds": self.barrier_sent_seconds,
            "first_byte_seconds": self.first_byte_seconds,
            "last_byte_seconds": self.last_byte_seconds,
            "ended_seconds": self.ended_seconds,
            "end_reason": self.end_reason,
            "error_errno": self.error_errno,
            "bytes_by_second": [
                {"second": second, "bytes": amount}
                for second, amount in sorted(self.bytes_by_second.items())
            ],
        }


def throughput_bounds(cap_kbps: int, duration_seconds: int) -> tuple[int, int]:
    expected = cap_kbps * 1_000 * duration_seconds // 8
    # The cap applies to aggregate Layer-3 bytes while this external observer
    # can count only TCP payload. Keep the upper bound strict and allow the
    # payload floor to absorb headers plus virtual-device scheduling jitter.
    lower = expected * 85 // 100
    upper = expected * 105 // 100 + IP_MTU_BYTES
    return lower, upper


@dataclass(frozen=True)
class TargetLiveness:
    index: int
    longest_zero_payload_seconds_after_warmup: int

    @property
    def passed(self) -> bool:
        return self.longest_zero_payload_seconds_after_warmup <= MAX_CONSECUTIVE_ZERO_PAYLOAD_SECONDS

    def document(self) -> dict[str, object]:
        return {
            "index": self.index,
            "longest_zero_payload_seconds_after_warmup": self.longest_zero_payload_seconds_after_warmup,
            "passed": self.passed,
        }


@dataclass(frozen=True)
class MeasurementValidation:
    rate_passed: bool
    target_liveness: tuple[TargetLiveness, ...]
    lower_bound_bytes: int
    upper_bound_bytes: int
    duration_seconds: int
    failure_reasons: tuple[str, ...]

    @property
    def liveness_passed(self) -> bool:
        return all(target.passed for target in self.target_liveness)

    @property
    def passed(self) -> bool:
        return self.rate_passed and self.liveness_passed

    def document(self) -> dict[str, object]:
        return {
            "passed": self.passed,
            "rate_passed": self.rate_passed,
            "liveness_passed": self.liveness_passed,
            "lower_bound_bytes": self.lower_bound_bytes,
            "upper_bound_bytes": self.upper_bound_bytes,
            "failure_reasons": list(self.failure_reasons),
            "target_liveness": [target.document() for target in self.target_liveness],
            "liveness_window": {
                "warmup_seconds": LIVENESS_WARMUP_SECONDS,
                "start_second_inclusive": LIVENESS_WARMUP_SECONDS,
                "end_second_exclusive": self.duration_seconds,
                "max_consecutive_zero_payload_seconds": MAX_CONSECUTIVE_ZERO_PAYLOAD_SECONDS,
            },
        }


def validate_measurement(
    cap_kbps: int,
    duration_seconds: int,
    target_bytes: int,
    control_bytes: int,
    targets: list[ConnectionMetrics],
) -> MeasurementValidation:
    if duration_seconds <= LIVENESS_WARMUP_SECONDS:
        raise ValueError("measurement must include complete buckets after liveness warmup")
    if (
        len(targets) != TARGET_CONNECTIONS
        or any(target.role != "target" for target in targets)
        or {target.index for target in targets} != set(range(TARGET_CONNECTIONS))
    ):
        raise ValueError("liveness requires every distinct target connection")
    lower, upper = throughput_bounds(cap_kbps, duration_seconds)
    reasons = []
    if target_bytes < lower:
        reasons.append("target_below_payload_floor")
    if target_bytes > upper:
        reasons.append("target_above_payload_ceiling")
    if control_bytes <= upper:
        reasons.append("control_did_not_bypass")
    rate_passed = not reasons
    liveness = []
    for target in sorted(targets, key=lambda value: value.index):
        longest = consecutive = 0
        # Only complete host recv-completion buckets count. Sparse missing buckets
        # are zero; startup and any recv tail at/after the deadline are excluded.
        for second in range(LIVENESS_WARMUP_SECONDS, duration_seconds):
            consecutive = consecutive + 1 if target.bytes_by_second.get(second, 0) == 0 else 0
            longest = max(longest, consecutive)
        result = TargetLiveness(target.index, longest)
        liveness.append(result)
        if not result.passed:
            reasons.append(f"target_{target.index}_zero_payload_run_exceeded")
    return MeasurementValidation(
        rate_passed, tuple(liveness), lower, upper, duration_seconds, tuple(reasons),
    )


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


def receive_until(
    connection: socket.socket,
    deadline: float,
    counter: ByteCounter,
    metrics: ConnectionMetrics,
    started_at: float,
) -> None:
    reason = "deadline"
    while time.monotonic() < deadline:
        try:
            chunk = connection.recv(64 * 1024)
        except TimeoutError:
            continue
        except OSError as error:
            reason = "error"
            metrics.error_errno = error.errno
            break
        if not chunk:
            reason = "eof"
            break
        elapsed = time.monotonic() - started_at
        counter.add(len(chunk))
        metrics.received(len(chunk), elapsed)
    metrics.end_reason = reason
    metrics.ended_seconds = time.monotonic() - started_at


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
        target_metrics = [ConnectionMetrics("target", index) for index in range(len(target))]
        control_metrics = [ConnectionMetrics("control", index) for index in range(len(control))]
        connection_metrics = target_metrics + control_metrics
        started_at = time.monotonic()
        deadline = started_at + args.duration_seconds
        workers = [
            threading.Thread(
                target=receive_until,
                args=(connection, deadline, target_counter, metrics, started_at),
            )
            for connection, metrics in zip(target, target_metrics, strict=True)
        ] + [
            threading.Thread(
                target=receive_until,
                args=(connection, deadline, control_counter, metrics, started_at),
            )
            for connection, metrics in zip(control, control_metrics, strict=True)
        ]
        for worker in workers:
            worker.start()
        for connection, metrics in zip(connections, connection_metrics, strict=True):
            connection.sendall(b"\x01")
            metrics.barrier_sent_seconds = time.monotonic() - started_at
        for worker in workers:
            worker.join(args.duration_seconds + IO_POLL_SECONDS * 4)
        validation = validate_measurement(
            args.cap_kbps,
            args.duration_seconds,
            target_counter.value,
            control_counter.value,
            target_metrics,
        )
        atomic_json(
            Path(args.output),
            {
                "cap_kbps": args.cap_kbps,
                "connections": [metrics.document() for metrics in connection_metrics],
                "control_bytes": control_counter.value,
                "control_connections": len(control),
                "duration_seconds": args.duration_seconds,
                # Sparse buckets use host recv-completion time relative to the existing
                # deadline origin. Missing buckets contain zero bytes. A blocking recv
                # may finish after the deadline; preserve that tail in its actual bucket.
                "timing_basis": "host_monotonic_since_all_connections_accepted",
                "started_host_monotonic_ns": int(started_at * 1_000_000_000),
                "bytes_bucket_width_seconds": 1,
                "target_bytes": target_counter.value,
                "target_connections": len(target),
                **validation.document(),
            },
        )
        return validation.passed
    finally:
        for connection in connections:
            connection.close()
        target_listener.close()
        control_listener.close()


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--cap-kbps", type=int, required=True, choices=(64, 512, 4096))
    value.add_argument("--control-port", type=int, required=True)
    value.add_argument("--duration-seconds", type=int, default=60, choices=(60, 300))
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
