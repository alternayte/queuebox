#!/usr/bin/env python3
"""The fixed HTTP receiver of the benchmark.

It answers 200 to every POST and appends one line per delivery:
    <X-Message-Id> <receipt time in milliseconds since the epoch>

The receiver is identical for every variant, so it never favours one of them. An
optional delay simulates a slow receiver for the backlog phase.
"""
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OUT = open(os.environ.get("RECEIPTS", "receipts.txt"), "a", buffering=1)
DELAY_MS = int(os.environ.get("DELAY_MS", "0"))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        if DELAY_MS:
            time.sleep(DELAY_MS / 1000.0)
        OUT.write("%s %d\n" % (self.headers.get("X-Message-Id", "?"), int(time.time() * 1000)))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
