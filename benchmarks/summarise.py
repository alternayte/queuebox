#!/usr/bin/env python3
"""Aggregates the per-run JSON results into the table of the verification report."""
import json
import statistics
import sys


def main(paths):
    runs = {}
    for path in paths:
        result = json.load(open(path))
        # A phase is one shape of load. Mixing a saturation run with a low-rate run in one
        # median would hide both, so the key carries the batch size and the receiver delay.
        phase = (result["events"], result["batch"], result.get("receiver_delay_ms", 0))
        runs.setdefault((phase, result["variant"]), []).append(result)

    print("| Events | Batch | Receiver delay ms | Variant | Runs | Throughput per second (median) | p50 ms | p95 ms | p99 ms | Idle transactions per minute | Peak RSS MiB | CPU seconds | Duplicates |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for (phase, variant), results in sorted(runs.items(), key=lambda item: (-item[0][0][0], item[0][0][1], item[0][1])):
        def median(select):
            values = [select(r) for r in results if select(r) is not None]
            return round(statistics.median(values), 1) if values else None

        print(
            "| %d | %d | %d | %s | %d | %s | %s | %s | %s | %s | %s | %s | %d |"
            % (
                phase[0],
                phase[1],
                phase[2],
                variant,
                len(results),
                median(lambda r: r["throughput_per_second"]),
                median(lambda r: r["latency_ms"]["p50"]),
                median(lambda r: r["latency_ms"]["p95"]),
                median(lambda r: r["latency_ms"]["p99"]),
                median(lambda r: r.get("idle_transactions_per_minute")),
                median(lambda r: r.get("peak_rss_mib")),
                median(lambda r: r.get("cpu_seconds")),
                sum(r.get("duplicates", 0) for r in results),
            )
        )


if __name__ == "__main__":
    main(sys.argv[1:])
