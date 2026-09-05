# The delivery benchmark

The benchmark compares three delivery paths on one machine and one database image:

| Variant | What it measures |
| --- | --- |
| `baseline` | The polling delivery before this change. Built from `git archive HEAD`. |
| `polling` | The polling delivery after this change. |
| `cdc` | The change data capture delivery after this change. |

Only the delivery path differs. The receiver, the producer, the event size, the batch
size, the pool size and the concurrency are identical for every variant.

## Run it

```bash
./benchmarks/run.py --variant baseline --events 100000 --runs 5
./benchmarks/run.py --variant polling  --events 100000 --runs 5
./benchmarks/run.py --variant cdc      --events 100000 --runs 5
python3 benchmarks/summarise.py benchmarks/results/*.result.json
```

Each run starts a fresh `postgres:16` container with `wal_level=logical`, builds and
starts the variant, waits for health, samples the idle load for sixty seconds, produces
the events, and waits for every receipt.

A saturation run measures how fast the delivery drains a backlog, because the producer
inserts faster than any variant delivers. It does not measure how quickly one committed
row reaches the receiver. Measure that with a low-rate phase, where each event is its own
commit and the delivery is idle between events:

```bash
./benchmarks/run.py --variant polling --events 200 --batch 1 --runs 3 --no-idle
./benchmarks/run.py --variant cdc     --events 200 --batch 1 --runs 3 --no-idle
```

The slow receiver and the backlog recovery use the same runner:

```bash
./benchmarks/run.py --variant polling --events 20000 --runs 1 --delay-ms 20 --no-idle
```

## What each number means

| Number | How it is measured |
| --- | --- |
| Throughput | Received deliveries divided by the span from the first commit to the last receipt. |
| Latency p50, p95, p99 | Receipt time minus the moment that the producer's batch commit acknowledged. |
| Idle transactions per minute | The change of `pg_stat_database.xact_commit` over sixty idle seconds. |
| Peak RSS, CPU seconds | One `ps` sample per second of the QueueBox process. |
| Duplicates | Receipts of a message identifier that already arrived. |

Latency uses the commit acknowledgement on the producer side and the receipt on the
receiver side. Both come from the same host clock, so no clock skew enters the number.
Every row of one batch shares the commit moment of that batch, which is what a producer
observes.

The event payload is a JSON object of about one KiB.

## Reading a result

Every run writes four files under `benchmarks/results`:

| File | Content |
| --- | --- |
| `*.commits` | One line per event: the identifier and the commit moment. |
| `*.receipts` | One line per delivery: the identifier and the receipt moment. |
| `*.meta.json` | The run settings and the resource samples. |
| `*.result.json` | The computed throughput, latency and duplicate counts. |

Keep the raw files. A throughput number without them cannot be checked.

## Honesty rules

State the machine, the container runtime and the number of runs beside every figure.
A polling regression above ten percent against `baseline` is a defect to investigate, not
a number to report and move past. Never quote a figure that this harness did not produce.
