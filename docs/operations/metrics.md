# Metrics

QueueBox exposes a Prometheus scrape at `GET /metrics`. The endpoint listens on the management
port when the configuration sets one. The body uses the Prometheus text format.

`MetricsDocTest` compares this document against a live scrape. Every name in the tables below must
appear in the scrape, and every name in the scrape must appear below or match a prefix of the
allowlist.

## How the exporter changes a name

The Prometheus exporter renames two kinds of metric.

- It removes the `_info` suffix. The registered gauge `queuebox_info` appears as `queuebox`.
- It adds a second family for a timer. A timer named `x_seconds` also exposes the gauge
  `x_seconds_max`, which holds the largest value of the current decay window.

A counter keeps its `_total` suffix in the `# TYPE` line and in the sample line.

## The QueueBox metrics

A tag value comes from the configuration or from a fixed enumeration, so the label set stays
bounded.

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `queuebox` | gauge | `version` | The build information. The value is always 1. The registered name is `queuebox_info`. |
| `queuebox_uptime_seconds` | gauge | none | The seconds since the process started. |
| `queuebox_outbox_messages_total` | counter | `status` = `sent`, `failed` or `dead` | The outbox messages that reached each terminal status. |
| `queuebox_outbox_messages_pending` | gauge | none | The outbox messages that wait for a publish. |
| `queuebox_outbox_messages_reclaimed_total` | counter | none | The outbox messages that returned to pending after a stale claim. |
| `queuebox_claims_lost_total` | counter | `component` | The terminal writes that lost the claim. Another replica owned the message. |
| `queuebox_outbox_process_errors_total` | counter | none | The errors that stopped the processing of one outbox message. |
| `queuebox_outbox_processing_duration_seconds` | summary | none | The time to process one outbox message. The summary carries the 50th, 95th and 99th percentile. |
| `queuebox_outbox_processing_duration_seconds_max` | gauge | none | The largest processing time of the current window. |
| `queuebox_outbox_publish_duration_seconds` | summary | `destination_type` | The time to publish one message to a destination type, for example `http`. |
| `queuebox_outbox_publish_duration_seconds_max` | gauge | `destination_type` | The largest publish time of the current window. |
| `queuebox_outbox_destination_messages_total` | counter | `destination`, `outcome` = `success` or `failure` | The outbox messages per destination and outcome. |
| `queuebox_outbox_queue_depth` | gauge | `destination` | The messages that wait for a publish to one destination. |
| `queuebox_http_publish_responses_total` | counter | `status_class` = `1xx`, `2xx`, `3xx`, `4xx`, `5xx` or `other` | The HTTP publish responses per status class. A raw status code is never a label. |
| `queuebox_transform_failures_total` | counter | `strategy` | The transform failures per error strategy. |
| `queuebox_inbox_messages_total` | counter | `status` = `new`, `forwarded` or `duplicate` | The inbox messages per status. |
| `queuebox_inbox_relay_errors_total` | counter | none | The errors of the inbox relay. |
| `queuebox_inbox_rejections_total` | counter | `reason` = `extraction_failed`, `transform_failed` or `storage_failed` | The inbox messages that QueueBox rejected, per reason. |
| `queuebox_cleanup_messages_deleted_total` | counter | `table` | The rows that the retention cleanup deleted, per table. |
| `queuebox_cleanup_duration_seconds` | summary | `table` | The time of one cleanup run, per table. |
| `queuebox_cleanup_duration_seconds_max` | gauge | `table` | The longest cleanup run of the current window. |
| `queuebox_cleanup_last_run_timestamp` | gauge | `table` | The Unix time in seconds of the last cleanup run. |

A metric with a tag registers on its first use. A destination that never received a message has no
sample until the first publish.

## The HikariCP pool metrics

`DatabaseFactory` gives the meter registry to HikariCP through `MicrometerMetricsTrackerFactory`.
Every metric below carries the tag `pool`, which holds the pool name.

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `hikaricp_connections` | gauge | `pool` | The connections in the pool, both idle and active. |
| `hikaricp_connections_active` | gauge | `pool` | The connections that a caller holds. |
| `hikaricp_connections_idle` | gauge | `pool` | The connections that are free. |
| `hikaricp_connections_pending` | gauge | `pool` | The threads that wait for a connection. |
| `hikaricp_connections_min` | gauge | `pool` | The minimum idle connection count of the pool configuration. |
| `hikaricp_connections_max` | gauge | `pool` | The maximum pool size of the pool configuration. |
| `hikaricp_connections_timeout_total` | counter | `pool` | The connection requests that timed out. |
| `hikaricp_connections_acquire_seconds` | summary | `pool` | The time to acquire a connection from the pool. |
| `hikaricp_connections_acquire_seconds_max` | gauge | `pool` | The longest acquire time of the current window. |
| `hikaricp_connections_creation_seconds` | summary | `pool` | The time to create a physical connection. |
| `hikaricp_connections_creation_seconds_max` | gauge | `pool` | The longest creation time of the current window. |
| `hikaricp_connections_usage_seconds` | summary | `pool` | The time a caller held a connection. |
| `hikaricp_connections_usage_seconds_max` | gauge | `pool` | The longest usage time of the current window. |

## The JVM metrics

QueueBox binds no JVM metrics. No `jvm_`, `process_` or `system_` family appears in the scrape,
because the application registers no Micrometer JVM binder. An operator who needs the heap, the
thread count or the garbage collection must read them from another exporter.

Do not document a JVM family here before a binder exists. `MetricsDocTest` fails on a documented
metric that the scrape does not carry.

## The allowlist

The allowlist names the prefixes that this document does not enumerate one by one. It is empty.
Every metric of the scrape has its own row above.

<!-- metrics:allowlist -->
<!-- /metrics:allowlist -->
