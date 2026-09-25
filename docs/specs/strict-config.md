# Strict config

## What it does
QueueBox refuses to start when its configuration holds a key it does not know, a `QUEUEBOX_*` variable that binds no setting, or a `type` that does not fit the keys of its entry. One error lists every offending path, with a suggestion for a near miss. `type` decides the kind of each destination, source and auth block. Fixes #65.

## Decisions
- An unknown key fails the start. There is no warn-only release — a config that trips the check already runs with a silent default or the wrong kind.
- One error names every offending path, and a near miss gets "did you mean `<key>`?" — an operator fixes the whole file in one pass.
- `type` is required on every destination and every auth block — neither has an obvious default, and every shipped config already writes it.
- A source without `type` is `http` — the quick start and every example rely on it, and HTTP is the one kind with an obvious default.
- When `type` is present it selects the kind. A key that does not belong to that kind fails the start. QueueBox no longer infers a kind from the keys — the inference is the bug in #65.
- An unknown `QUEUEBOX_*` variable fails the start like an unknown key — a misspelled variable is the same defect as a misspelled key.
- A `QUEUEBOX_*` name that binds no setting and ends in a Kubernetes service-link form is ignored: `_SERVICE_HOST`, `_SERVICE_PORT…`, `_PORT` or `_PORT_<n>_<TCP|UDP|SCTP>…` — a Service whose name starts with `queuebox`, such as `queuebox-db`, injects these names.
- `QUEUEBOX_CONFIG_FILE` stays outside the check — it names the file and binds no setting.
- The check covers the YAML file, the packaged fallback and the environment, in the one place that builds the config — no source can bypass it.
- The changelog marks this as breaking in 0.5.0 and names the migration step: fix each path the error lists, and add `type` to any destination or auth block without one.
- The docs site states the rule on the configuration reference and the service-link exception on the deploy page.

## Out
- A config schema file for editors.
- A `validate` command that checks a config without a start.
- Deprecation warnings for old keys.
- Strictness for the pull client libraries' options.

## How I know it works
- `outbox: { batchSzie: 10 }` stops the start with `outbox.batchSzie: unknown key; did you mean batchSize?`.
- `destinations: { d: { type: rabbitmq, baseUrl: https://x } }` stops the start and names `destinations.d.baseUrl` as a key that `rabbitmq` does not take.
- A destination without `type` stops the start and names the path.
- `sources.stripe` with `path` and no `type` starts as an HTTP source.
- `QUEUEBOX_OUTBOX_BATCHSZIE=10` stops the start and names the variable.
- `QUEUEBOX_DB_SERVICE_HOST=10.0.0.1` and `QUEUEBOX_PORT=tcp://10.0.0.2:8080` do not stop the start.
- A config with three mistakes gets one error that lists all three.
- Every config sample on the docs site and in `examples/` still loads.
- `./gradlew check detekt` passes.
