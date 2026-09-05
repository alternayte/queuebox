#!/usr/bin/env python3
"""The QueueBox delivery benchmark.

It measures three variants on the same machine and the same database image:

    baseline  the polling delivery before this change, built from a clean checkout
    polling   the polling delivery after this change
    cdc       the change data capture delivery after this change

Every variant uses the same receiver, the same producer, the same event size and the
same concurrency, so only the delivery path differs.

Usage:
    ./benchmarks/run.py --variant polling --events 100000 --runs 5
    ./benchmarks/run.py --variant cdc --backlog       # the slow receiver phase
"""
import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "benchmarks", "results")
BASELINE = "/tmp/queuebox-benchmark-baseline"
CONTAINER = "queuebox-benchmark-db"
PAYLOAD_PADDING = 980  # gives a payload of about one KiB


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, **kwargs)


def psql(sql, quiet=True):
    flags = ["-qtA"] if quiet else []
    return run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "queuebox", "-d", "queuebox"]
        + flags
        + ["-c", sql]
    ).stdout


def free_port():
    with socket.socket() as handle:
        handle.bind(("", 0))
        return handle.getsockname()[1]


def start_database():
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True, text=True)
    port = free_port()
    run(
        [
            "docker", "run", "-d", "--name", CONTAINER,
            "-e", "POSTGRES_DB=queuebox",
            "-e", "POSTGRES_USER=queuebox",
            "-e", "POSTGRES_PASSWORD=queuebox",
            "-p", "%d:5432" % port,
            "postgres:16",
            "postgres", "-c", "wal_level=logical",
        ]
    )
    for _ in range(120):
        probe = subprocess.run(
            ["docker", "exec", CONTAINER, "pg_isready", "-U", "queuebox"],
            capture_output=True, text=True,
        )
        if probe.returncode == 0:
            return port
        time.sleep(1)
    raise SystemExit("the database never became ready")


def build(variant):
    """Returns the directory of the installed distribution of this variant."""
    if variant == "baseline":
        if os.path.isdir(BASELINE):
            shutil.rmtree(BASELINE)
        os.makedirs(BASELINE)
        archive = subprocess.Popen(["git", "archive", "HEAD"], cwd=ROOT, stdout=subprocess.PIPE)
        subprocess.run(["tar", "-x", "-C", BASELINE], stdin=archive.stdout, check=True)
        archive.wait()
        source = BASELINE
    else:
        source = ROOT
    run(["./gradlew", "--quiet", ":app:installDist", "-x", "test"], cwd=source)
    return os.path.join(source, "app", "build", "install", "app")


def configuration(variant, database_port, receiver_port, state_directory):
    capture = ""
    if variant == "cdc":
        capture = """
  capture:
    mode: postgres-logical
    enabled: true
    identity: benchmark
    stateDirectory: %s
    publication: queuebox_outbox
    slot: queuebox_outbox
    reconciliationIntervalMs: 1000""" % state_directory
    return """
server:
  httpPort: %d

database:
  type: postgresql
  url: jdbc:postgresql://localhost:%d/queuebox
  username: queuebox
  password: queuebox
  poolSize: 16

outbox:
  pollIntervalMs: 100
  batchSize: 100
  concurrency: 8%s

destinations:
  receiver:
    type: http
    baseUrl: http://localhost:%d
    path: /events

routes:
  - topicPattern: "bench.*"
    destination: receiver
""" % (free_port(), database_port, capture, receiver_port)


class Sampler(threading.Thread):
    """Samples the resident memory and the CPU time of the QueueBox process."""

    def __init__(self, pid):
        super().__init__(daemon=True)
        self.pid = pid
        self.peak_rss_kib = 0
        self.cpu_seconds = 0.0
        self.running = True

    def run(self):
        while self.running:
            probe = subprocess.run(
                ["ps", "-o", "rss=,time=", "-p", str(self.pid)], capture_output=True, text=True
            )
            fields = probe.stdout.split()
            if len(fields) == 2:
                self.peak_rss_kib = max(self.peak_rss_kib, int(fields[0]))
                parts = [float(p) for p in fields[1].replace("-", ":").split(":")]
                seconds = 0.0
                for part in parts:
                    seconds = seconds * 60 + part
                self.cpu_seconds = seconds
            time.sleep(1)


def start_queuebox(install, config_path, log_path):
    environment = dict(os.environ, QUEUEBOX_CONFIG_FILE=config_path)
    handle = open(log_path, "w")
    process = subprocess.Popen(
        [os.path.join(install, "bin", "app")],
        env=environment, stdout=handle, stderr=subprocess.STDOUT, cwd=ROOT,
    )
    return process


def wait_for_health(port, process, seconds=180):
    for _ in range(seconds):
        if process.poll() is not None:
            raise SystemExit("QueueBox exited during startup")
        try:
            with urllib.request.urlopen("http://localhost:%d/health" % port, timeout=2) as answer:
                if answer.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1)
    raise SystemExit("QueueBox never became healthy")


def stop(process):
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.kill()


def idle_transactions_per_minute(seconds=60):
    """Counts the committed transactions of an idle instance, which is the query load."""
    before = int(psql("SELECT xact_commit FROM pg_stat_database WHERE datname = 'queuebox'").strip())
    time.sleep(seconds)
    after = int(psql("SELECT xact_commit FROM pg_stat_database WHERE datname = 'queuebox'").strip())
    return round((after - before) * 60.0 / seconds, 1)


def produce(events, batch, commits_path):
    """Inserts the events and records the moment that each batch commit acknowledged."""
    padding = "x" * PAYLOAD_PADDING
    with open(commits_path, "w") as handle:
        produced = 0
        while produced < events:
            size = min(batch, events - produced)
            sql = (
                "BEGIN; INSERT INTO outbox (topic, payload) "
                "SELECT 'bench.event', jsonb_build_object('n', g, 'pad', '%s') "
                "FROM generate_series(1, %d) AS g RETURNING id; COMMIT;" % (padding, size)
            )
            ids = [line.strip() for line in psql(sql).splitlines() if line.strip()]
            committed = int(time.time() * 1000)
            for identifier in ids:
                handle.write("%s %d\n" % (identifier, committed))
            produced += size
    return produced


def wait_for_receipts(receipts_path, expected, timeout):
    deadline = time.time() + timeout
    last = -1
    while time.time() < deadline:
        with open(receipts_path) as handle:
            count = sum(1 for _ in handle)
        if count >= expected:
            return count
        if count != last:
            last = count
            deadline = max(deadline, time.time() + 120)
        time.sleep(2)
    with open(receipts_path) as handle:
        return sum(1 for _ in handle)


def one_run(variant, install, events, batch, index, measure_idle, delay_ms):
    os.makedirs(RESULTS, exist_ok=True)
    tag = "%s-%d-%s" % (variant, index, uuid.uuid4().hex[:6])
    receipts_path = os.path.join(RESULTS, tag + ".receipts")
    commits_path = os.path.join(RESULTS, tag + ".commits")
    meta_path = os.path.join(RESULTS, tag + ".meta.json")
    open(receipts_path, "w").close()

    database_port = start_database()
    state_directory = os.path.join(RESULTS, tag + ".state")
    os.makedirs(state_directory, exist_ok=True)
    receiver_port = free_port()
    receiver = subprocess.Popen(
        [sys.executable, os.path.join(ROOT, "benchmarks", "receiver.py"), str(receiver_port)],
        env=dict(os.environ, RECEIPTS=receipts_path, DELAY_MS=str(delay_ms)),
    )

    config_path = os.path.join(RESULTS, tag + ".yml")
    text = configuration(variant, database_port, receiver_port, state_directory)
    with open(config_path, "w") as handle:
        handle.write(text)
    http_port = int(text.split("httpPort: ")[1].split("\n")[0])

    process = None
    try:
        if variant == "cdc":
            # The publication needs the outbox table, so the first start only migrates.
            plain = config_path.replace(".yml", ".plain.yml")
            with open(plain, "w") as handle:
                handle.write(configuration("polling", database_port, receiver_port, state_directory))
            plain_port = int(open(plain).read().split("httpPort: ")[1].split("\n")[0])
            first = start_queuebox(install, plain, os.path.join(RESULTS, tag + ".migrate.log"))
            wait_for_health(plain_port, first)
            stop(first)
            psql("CREATE PUBLICATION queuebox_outbox FOR TABLE outbox")

        process = start_queuebox(install, config_path, os.path.join(RESULTS, tag + ".log"))
        wait_for_health(http_port, process)
        sampler = Sampler(process.pid)
        sampler.start()
        if variant == "cdc":
            time.sleep(20)  # let the snapshot finish before the measured phase

        idle = idle_transactions_per_minute() if measure_idle else None

        started = time.time()
        produced = produce(events, batch, commits_path)
        received = wait_for_receipts(receipts_path, produced, timeout=1800)
        sampler.running = False
        sampler.join(timeout=5)

        json.dump(
            {
                "variant": variant,
                "run": index,
                "events": produced,
                "batch": batch,
                "receiver_delay_ms": delay_ms,
                "idle_transactions_per_minute": idle,
                "peak_rss_mib": round(sampler.peak_rss_kib / 1024.0, 1),
                "cpu_seconds": sampler.cpu_seconds,
                "produce_and_deliver_seconds": round(time.time() - started, 3),
                "received": received,
            },
            open(meta_path, "w"),
            indent=2,
            sort_keys=True,
        )
        run(
            [
                sys.executable, os.path.join(ROOT, "benchmarks", "report.py"),
                commits_path, receipts_path, meta_path,
                os.path.join(RESULTS, tag + ".result.json"),
            ]
        )
        print("wrote %s" % os.path.join(RESULTS, tag + ".result.json"))
    finally:
        if process is not None:
            stop(process)
        receiver.terminate()
        subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", required=True, choices=["baseline", "polling", "cdc"])
    parser.add_argument("--events", type=int, default=100000)
    parser.add_argument("--batch", type=int, default=1000)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--no-idle", action="store_true", help="skip the 60-second idle sample")
    parser.add_argument("--delay-ms", type=int, default=0, help="slow receiver, for the backlog phase")
    arguments = parser.parse_args()

    install = build(arguments.variant)
    for index in range(1, arguments.runs + 1):
        one_run(
            arguments.variant, install, arguments.events, arguments.batch, index,
            measure_idle=not arguments.no_idle, delay_ms=arguments.delay_ms,
        )


if __name__ == "__main__":
    main()
