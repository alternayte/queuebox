#!/usr/bin/env python3
"""Turns the raw files of one benchmark run into one JSON result.

Latency is commit to receipt. The commit time is the database clock value of
`created_at`, and the receipt time is the host clock. Both clocks are the same machine,
so the difference carries no network skew.
"""
import json
import sys


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def main(commits_path, receipts_path, meta_path, out_path):
    commits = {}
    with open(commits_path) as handle:
        for line in handle:
            message_id, committed = line.split()
            commits[message_id.lower()] = float(committed)

    receipts = {}
    with open(receipts_path) as handle:
        for line in handle:
            parts = line.split()
            if len(parts) != 2:
                continue
            message_id, received = parts[0].lower(), float(parts[1])
            # A duplicate delivery keeps the first receipt.
            receipts.setdefault(message_id, received)

    latencies = [receipts[k] - commits[k] for k in commits if k in receipts]
    meta = json.load(open(meta_path))
    span = (max(receipts.values()) - min(commits.values())) / 1000.0 if receipts else 0.0

    meta.update(
        {
            "events_committed": len(commits),
            "events_received": len(receipts),
            "duplicates": sum(1 for _ in open(receipts_path)) - len(receipts),
            "wall_seconds": round(span, 3),
            "throughput_per_second": round(len(receipts) / span, 1) if span > 0 else None,
            "latency_ms": {
                "p50": percentile(latencies, 0.50),
                "p95": percentile(latencies, 0.95),
                "p99": percentile(latencies, 0.99),
                "max": max(latencies) if latencies else None,
            },
        }
    )
    json.dump(meta, open(out_path, "w"), indent=2, sort_keys=True)
    print(json.dumps(meta, sort_keys=True))


if __name__ == "__main__":
    main(*sys.argv[1:5])
