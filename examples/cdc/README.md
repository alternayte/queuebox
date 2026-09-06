# Change data capture

This stack runs QueueBox with the embedded PostgreSQL logical capture connector. Capture
only wakes delivery; SQL remains the truth. Read [docs/capture.md](../../docs/capture.md)
for the full contract.

Run it:

```bash
./smoke-test.sh
```

The stack:

- runs PostgreSQL with `wal_level=logical`,
- creates the publication `queuebox_outbox` before QueueBox starts, because QueueBox
  never creates a publication itself,
- keeps the capture state on the named volume `capture-state`, mounted at
  `/var/lib/queuebox/capture`,
- answers every delivery with 200 and prints the body.

`outbox.capture.reconciliationIntervalMs` is 30000 on purpose. The smoke test requires
the delivery within 15 seconds, so a pass proves that capture woke delivery rather than
the reconciliation timer. The test then restarts QueueBox and delivers a second row,
which proves that the recorded log position survived on the volume.

The smoke test then proves three more claims about the packaged image:

- the distribution ships the PostgreSQL and the SQL Server connector,
- a second process with the same capture identity is refused, by the session lock or by the
  state check depending on which one it reaches first, and
- that refused process still delivers through SQL.

Only one process may own a capture identity. The second instance in this stack exists to
prove the refusal, so it runs behind the Compose profile `second`. Keep `enabled: false`
on every replica of a real deployment that must not own capture.
