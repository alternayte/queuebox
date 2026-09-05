# Change data capture

Capture shortens the delay between a committed outbox row and its delivery. It does not
deliver anything. The capture connector reads the database log, sees an insert into the
outbox table, and wakes the delivery loop. The delivery loop then claims and publishes
through SQL, exactly as it does without capture.

SQL is the truth. A capture event that QueueBox never receives costs latency, not a
message. The reconciliation timer still runs, so delivery continues while capture is
down, misconfigured, or disabled.

## Modes

| `outbox.capture.mode` | Database | Behaviour |
| --- | --- | --- |
| `polling` | any | The default. Delivery polls at `outbox.pollIntervalMs`. |
| `postgres-logical` | PostgreSQL | Delivery reacts to the logical replication stream. |
| `sqlserver-cdc` | SQL Server | Delivery reacts to the change data capture tables. |

The connector runs inside the QueueBox process. There is no Kafka, no Kafka Connect and
no Debezium Server to operate.

Capture ignores updates, deletes and tombstones. Only an insert or a snapshot record
wakes delivery, so a state change that QueueBox itself writes creates no new work.

## Settings

```yaml
outbox:
  capture:
    mode: postgres-logical
    enabled: true                 # false on every replica that must not own capture
    identity: queuebox            # the name of this capture owner
    stateDirectory: /var/lib/queuebox/capture
    schema: public                # default: public on PostgreSQL, dbo on SQL Server
    publication: queuebox_outbox  # PostgreSQL only
    slot: queuebox_outbox         # PostgreSQL only
    reconciliationIntervalMs: 1000
    connection:
      hostname: replica.example.com
      port: 5433
      database: queuebox
      username: capture_user
      password: capture_secret
      encrypt: true                 # SQL Server only
      trustServerCertificate: false # SQL Server only
```

`enabled` is `false` by default and `mode` is `polling` by default, so an upgrade changes
no behaviour until you ask for capture.

### Connection overrides

Capture reads the host, the port and the database name from `database.url`. Every field
under `capture.connection` replaces the parsed value for the capture connection only.
Delivery keeps using `database.url`. Use the overrides to give capture a different
account, a read replica, or a single host when the URL lists several. A URL with more
than one host is rejected unless `capture.connection.hostname` names one host.

Secrets stay out of the logs and out of the recorded capture state.

## PostgreSQL preparation

1. Start the server with `wal_level = logical`.
2. Grant the capture account the `REPLICATION` attribute, plus `SELECT` on the outbox
   table.
3. Create the publication for the outbox table:

   ```sql
   CREATE PUBLICATION queuebox_outbox FOR TABLE outbox;
   ```

QueueBox never creates or drops the publication, and it never drops the replication
slot. It refuses to start capture when the publication is absent, because an automatic
publication would silently capture the wrong tables.

The connector creates the replication slot on the first start. A slot holds write-ahead
log until capture consumes it. Monitor `pg_replication_slots`, and remove the slot by
hand after you retire a capture identity for good.

## SQL Server preparation

1. Run the SQL Server Agent. Change data capture needs it.
2. Enable capture on the database and on the outbox table:

   ```sql
   EXEC sys.sp_cdc_enable_db;
   EXEC sys.sp_cdc_enable_table
       @source_schema = N'dbo',
       @source_name   = N'outbox',
       @role_name     = NULL,
       @supports_net_changes = 0;
   ```

QueueBox checks both before it starts the connector and reports a clear error instead of
a connector failure loop.

## The state directory

`stateDirectory` must be a durable volume that survives a restart. QueueBox writes:

| File | Content |
| --- | --- |
| `offsets.dat` | The log position that capture already delivered. |
| `history.dat` | The schema history. SQL Server only. |
| `identity` | The identifier that ties these files to the database registry row. |

Give the directory to the user that runs QueueBox, with read and write permission, and
mount it read-write. A container without a mounted volume loses the files on every
restart.

The image runs as the non-root user `queuebox` and ships `/var/lib/queuebox/capture`
owned by that user. An empty Docker named volume mounted there inherits that ownership
and needs no further work. A bind mount keeps the ownership of the host directory, so
chown it to the user that runs the container before you start QueueBox.

The database table `queuebox_capture_state` records the identity and a fingerprint of the
capture settings. Together they detect three faults that would otherwise pass unnoticed:

- The volume is missing or empty while the database says capture ran before.
- The state files belong to a different instance.
- The settings changed. A new slot, publication, schema, table, host or database makes
  the recorded offsets meaningless.

QueueBox also refuses to continue when the PostgreSQL replication slot disappeared while
the state remains, because the connector would create a fresh slot and quietly restart
from the present.

## One owner

Exactly one process may own a capture identity. The owner holds a database session lock
for the whole run. A second process that starts with the same identity fails the lock,
reports that the identity already has an owner, and keeps delivering through SQL. Set
`enabled: false` on every replica that must not own capture.

## Health and failure

Capture health is separate from delivery health. `/health/ready` reports the component
`outbox-capture`, but that component is advisory: a capture fault never makes the
readiness answer unhealthy, because the instance still delivers. Watch the component to
see the fault; watch delivery to see whether messages move.

When the connector fails, QueueBox:

- marks capture unhealthy and keeps SQL delivery running,
- retries with a bounded backoff from one second up to thirty seconds,
- wakes delivery on every attempt, so nothing waits for capture to recover.

A fault that needs a decision does not retry. QueueBox stops capture, reports the reason
and keeps delivering. Delivery falls back to the reconciliation interval, so messages
arrive later but they do arrive.

## Recovery

Recovery is an operator decision, because every automatic answer either replays or drops
messages. Use this procedure after QueueBox reports that capture requires recovery.

1. Read the reported reason. It names the fault.
2. Stop the QueueBox process that owns the capture identity.
3. Restore the volume, if the state files are only unavailable. Capture continues from
   the recorded position.
4. If the state is truly lost, or you changed the capture settings on purpose, reset the
   identity:

   ```sql
   DELETE FROM queuebox_capture_state WHERE identity_name = 'queuebox';
   ```

   For PostgreSQL, drop the slot as well, so the next start creates a clean one:

   ```sql
   SELECT pg_drop_replication_slot('queuebox_outbox');
   ```

5. Delete the files in `stateDirectory`.
6. Start QueueBox. Capture takes a fresh snapshot of the outbox table.

A fresh snapshot only wakes delivery for rows that SQL still holds. It delivers no
message twice, because delivery claims each row through SQL.

## Failover and reconfiguration

Failover is manual. QueueBox does not elect a capture owner. To move capture to another
host, stop the current owner, move or recreate the state directory, and start the new
owner with the same identity.

Reconfiguration is manual for the same reason. Change the slot, the publication, the
schema or the table only together with the recovery procedure above.
