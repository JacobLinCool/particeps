#!/usr/bin/env python3
"""Observe host upload and Android-delivered download under simultaneous demand."""

from __future__ import annotations

import argparse
import json
import socket
import threading
import time
import uuid
from pathlib import Path

from tools.android_fixture_server import (
    ByteCounter, ConnectionMetrics, IO_POLL_SECONDS, LIVENESS_WARMUP_SECONDS,
    MAX_CONSECUTIVE_ZERO_PAYLOAD_SECONDS, accept_connections, atomic_json,
    check_liveness, receive_until, throughput_bounds,
)

DURATION_SECONDS = 60
CAP_KBPS = 512
MAX_BARRIER_ACK_SECONDS = 1.0
SENDER_LIMIT_SECONDS = 61.5


def receive_exact(connection: socket.socket, size: int, deadline: float) -> bytes:
    data = bytearray()
    while len(data) < size:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Fixture protocol deadline")
        connection.settimeout(remaining)
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise EOFError("Incomplete fixture protocol frame")
        data.extend(chunk)
    return bytes(data)


def send_payload_until(connection: socket.socket, started: float, deadline: float, result: dict[str, object]) -> None:
    payload = b"Z" * (64 * 1024)
    accepted = 0
    reason = "deadline"
    error_errno = None
    while time.monotonic() < deadline:
        connection.settimeout(min(IO_POLL_SECONDS, max(0.001, deadline - time.monotonic())))
        try:
            amount = connection.send(payload)
        except TimeoutError:
            continue
        except OSError as error:
            reason, error_errno = "error", error.errno
            break
        if amount == 0:
            reason = "peer_closed"
            break
        accepted += amount
    result.update(socket_accepted_bytes=accepted, end_reason=reason, error_errno=error_errno,
        ended_host_seconds=time.monotonic() - started)


def run_server(args: argparse.Namespace) -> bool:
    identity = uuid.UUID(args.measurement_id)
    listeners = []
    connections = []
    workers = []
    host = {"schema_version": 1, "measurement_id": str(identity), "duration_seconds": DURATION_SECONDS}
    try:
        for port in (args.upload_port, args.download_port, args.control_port):
            listener = socket.create_server(("0.0.0.0", port), reuse_port=False)
            listener.listen(1)
            listeners.append(listener)
        Path(args.ready).touch(exist_ok=False)
        for listener in listeners:
            connections.extend(accept_connections(listener, 1))
        upload, download, control = connections
        if receive_exact(download, 16, time.monotonic() + 20) != identity.bytes:
            raise ValueError("Unexpected download measurement ID")
        upload_counter, control_counter = ByteCounter(), ByteCounter()
        upload_metrics, control_metrics = ConnectionMetrics("target", 0), ConnectionMetrics("control", 0)
        started = time.monotonic()
        host["started_host_monotonic_ns"] = int(started * 1_000_000_000)
        host["timing_basis"] = "host_monotonic_since_all_connections_ready"
        workers = [threading.Thread(target=receive_until, args=(
            connection, started + DURATION_SECONDS, counter, metrics, started,
        )) for connection, counter, metrics in (
            (upload, upload_counter, upload_metrics), (control, control_counter, control_metrics),
        )]
        for worker in workers:
            worker.start()
        for connection, metrics in ((upload, upload_metrics), (control, control_metrics)):
            connection.sendall(b"\x01")
            metrics.barrier_sent_seconds = time.monotonic() - started
        download.sendall(b"\x01" + identity.bytes)
        ack = receive_exact(download, 17, started + MAX_BARRIER_ACK_SECONDS)
        host["download_barrier_ack_seconds"] = time.monotonic() - started
        if ack != b"\x02" + identity.bytes:
            raise ValueError("Unexpected download barrier acknowledgement")
        sender_result: dict[str, object] = {}
        sender = threading.Thread(target=send_payload_until, args=(
            download, started, started + SENDER_LIMIT_SECONDS, sender_result,
        ))
        workers.append(sender)
        sender.start()
        for worker in workers:
            worker.join(max(0, started + SENDER_LIMIT_SECONDS + 1 - time.monotonic()))
        if any(worker.is_alive() for worker in workers):
            raise TimeoutError("Fixture worker exceeded measurement bound")
        host.update(
            completed=True, upload=upload_metrics.document(), control=control_metrics.document(),
            download_sender=sender_result,
        )
        return True
    except Exception as error:
        host.update(completed=False, failure_type=type(error).__name__)
        return False
    finally:
        for connection in connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        for worker in workers:
            if worker.ident is not None:
                worker.join(IO_POLL_SECONDS * 2)
        for listener in listeners:
            listener.close()
        atomic_json(Path(args.output), host)


def integer(value: object) -> bool:
    return type(value) is int and value >= 0


def connection_metrics(document: dict, role: str, *, complete_buckets: bool) -> ConnectionMetrics:
    if not integer(document.get("received_bytes")):
        raise ValueError("Invalid delivered-byte count")
    buckets = document.get("bytes_by_second")
    if not isinstance(buckets, list) or len(buckets) > DURATION_SECONDS:
        raise ValueError("Invalid bounded receive buckets")
    by_second = {}
    for bucket in buckets:
        second, amount = bucket["second"], bucket["bytes"]
        if not integer(second) or not integer(amount) or second >= DURATION_SECONDS or second in by_second:
            raise ValueError("Invalid or duplicated receive bucket")
        by_second[second] = amount
    if complete_buckets and set(by_second) != set(range(DURATION_SECONDS)):
        raise ValueError("Android receiver must report every complete bucket")
    if sum(by_second.values()) != document["received_bytes"]:
        raise ValueError("Delivered byte count does not equal bucket sum")
    return ConnectionMetrics(role, 0, received_bytes=document["received_bytes"], bytes_by_second=by_second)


def validate_duplex(host: dict, download: dict, measurement_id: str) -> dict[str, object]:
    for document in (host, download):
        if (
            type(document.get("schema_version")) is not int or document.get("schema_version") != 1
            or document.get("measurement_id") != measurement_id
            or document.get("duration_seconds") != DURATION_SECONDS
        ):
            raise ValueError("Measurement identity or window mismatch")
    ack = host.get("download_barrier_ack_seconds")
    if host.get("completed") is not True or type(ack) not in (float, int) or not 0 <= ack <= MAX_BARRIER_ACK_SECONDS:
        raise ValueError("Download barrier was not acknowledged within its fixed bound")
    sender = host["download_sender"]
    sender_ended = sender.get("ended_host_seconds")
    if (
        sender.get("end_reason") not in ("deadline", "error", "peer_closed")
        or type(sender_ended) not in (float, int) or not DURATION_SECONDS <= sender_ended
    ):
        raise ValueError("Download sender stopped before the receiver window could complete")
    if (
        download.get("direction") != "download" or download.get("fixture_role") != "target_b"
        or download.get("payload_valid") is not True
        or download.get("end_reason") != "deadline" or download.get("error_type") is not None
        or download.get("timing_basis") != "android_elapsed_realtime_since_validated_barrier"
    ):
        raise ValueError("Android download did not complete a valid payload observation window")
    started, ended = download.get("started_elapsed_realtime_nanos"), download.get("ended_elapsed_realtime_nanos")
    if not integer(started) or not integer(ended) or ended - started < DURATION_SECONDS * 1_000_000_000:
        raise ValueError("Android download window was truncated")
    for role, document in (("target", host["upload"]), ("control", host["control"])):
        if document.get("role") != role or document.get("index") != 0 or document.get("end_reason") != "deadline":
            raise ValueError("Host receiver did not complete its observation window")
    upload = connection_metrics(host["upload"], "target", complete_buckets=False)
    received_download = connection_metrics(download, "target", complete_buckets=True)
    control = connection_metrics(host["control"], "control", complete_buckets=False)
    lower, upper = throughput_bounds(CAP_KBPS, DURATION_SECONDS)
    directions = {}
    reasons = []
    for direction, metrics in (("upload", upload), ("download", received_download)):
        rate_passed = lower <= metrics.received_bytes <= upper
        liveness = check_liveness(metrics, DURATION_SECONDS)
        directions[direction] = {
            "received_bytes": metrics.received_bytes,
            "rate_passed": rate_passed,
            "liveness_passed": liveness.passed,
            "longest_zero_payload_seconds_after_warmup": liveness.longest_zero_payload_seconds_after_warmup,
        }
        if not rate_passed:
            reasons.append(f"{direction}_outside_payload_bounds")
        if not liveness.passed:
            reasons.append(f"{direction}_zero_payload_run_exceeded")
    control_passed = control.received_bytes > upper
    if not control_passed:
        reasons.append("control_did_not_bypass")
    return {
        "kind": "duplex", "measurement_id": measurement_id, "duration_seconds": DURATION_SECONDS,
        "cap_kbps": CAP_KBPS, "passed": not reasons, "directions": directions,
        "lower_bound_bytes": lower, "upper_bound_bytes": upper,
        "control_bytes": control.received_bytes, "control_passed": control_passed,
        "download_barrier_ack_seconds": ack, "maximum_barrier_ack_seconds": MAX_BARRIER_ACK_SECONDS,
        "liveness_window": {"warmup_seconds": LIVENESS_WARMUP_SECONDS,
            "start_second_inclusive": LIVENESS_WARMUP_SECONDS, "end_second_exclusive": DURATION_SECONDS,
            "max_consecutive_zero_payload_seconds": MAX_CONSECUTIVE_ZERO_PAYLOAD_SECONDS},
        "failure_reasons": reasons,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve")
    for name in ("upload", "download", "control"):
        serve.add_argument(f"--{name}-port", type=int, required=True)
    serve.add_argument("--ready", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--host", required=True)
    validate.add_argument("--download", required=True)
    for command in (serve, validate):
        command.add_argument("--measurement-id", required=True)
        command.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "serve":
        return 0 if run_server(args) else 1
    try:
        result = validate_duplex(json.loads(Path(args.host).read_text()), json.loads(Path(args.download).read_text()), args.measurement_id)
    except (ValueError, KeyError, TypeError) as error:
        result = {"kind": "duplex", "measurement_id": args.measurement_id, "passed": False,
            "failure_reasons": [str(error)]}
    atomic_json(Path(args.output), result)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
