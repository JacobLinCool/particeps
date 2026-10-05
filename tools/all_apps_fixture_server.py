#!/usr/bin/env python3
"""Local-only TCP fixture with synthetic aggregate progress, never endpoint or payload logs."""

import argparse
import json
import socketserver
import time
from pathlib import Path


class PayloadHandler(socketserver.BaseRequestHandler):
    def handle(self):
        started = time.monotonic_ns()
        requested = None
        queued = None
        failure = None
        self.request.settimeout(20)
        try:
            if self.request.recv(1) != b"\x01":
                return
            requested = time.monotonic_ns()
            self.request.sendall(b"Z" * 262_144)
            queued = time.monotonic_ns()
        except OSError as error:
            failure = type(error).__name__
            raise
        finally:
            # Synthetic fixture progress only. sendall proves host socket admission, not delivery
            # to Android; no endpoint, payload or flow identifier is recorded.
            print(json.dumps({
                "event": "all_apps_fixture_handler_finished",
                "started_monotonic_ns": started,
                "request_monotonic_ns": requested,
                "sendall_completed_monotonic_ns": queued,
                "queued_to_host_socket_bytes": 262_144 if queued is not None else 0,
                "failure_type": failure,
            }), flush=True)


class PayloadServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def shutdown_request(self, request):
        # Keep socketserver's original shutdown/close behavior, then record that it returned.
        super().shutdown_request(request)
        print(json.dumps({
            "event": "all_apps_fixture_socket_closed",
            "closed_monotonic_ns": time.monotonic_ns(),
        }), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--ready", type=Path)
    args = parser.parse_args()
    with PayloadServer(("127.0.0.1", args.port), PayloadHandler) as server:
        if args.ready is not None:
            temporary = args.ready.with_name(f".{args.ready.name}.tmp")
            temporary.write_text(f"{server.server_address[1]}\n", encoding="ascii")
            temporary.replace(args.ready)
        server.serve_forever()
