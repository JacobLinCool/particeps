#!/usr/bin/env python3
"""Local-only TCP fixture for AllAppsTrafficShapingAndroidTest (no traffic recording)."""

import argparse
import socketserver


class PayloadHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(20)
        if self.request.recv(1) != b"\x01":
            return
        self.request.sendall(b"Z" * 262_144)


class PayloadServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    args = parser.parse_args()
    with PayloadServer(("127.0.0.1", args.port), PayloadHandler) as server:
        server.serve_forever()
