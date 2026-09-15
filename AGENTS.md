# queuebox

## What this is
QueueBox is a Kotlin service that runs the transactional outbox and the idempotent inbox on Postgres or SQL Server.
Brokers (RabbitMQ, Kafka, NATS) and webhooks feed the inbox, and the outbox delivers rows to destinations; `clients/` holds pull-inbox libraries in Go, TypeScript and C#.

## Run
- `docker compose -f docker-compose.yml --env-file .env.example up -d --build`
- The compose stack mounts `examples/queuebox.yml` as the config.

## Test
- Gate: `./gradlew check detekt`
- `./gradlew check` alone does not run detekt.
- Integration tests use Testcontainers and need Docker.
- `clients/go`: `go vet ./...` and `go test ./...`
- `clients/typescript`: `npm run typecheck` and `npm test`
- `clients/csharp`: `dotnet test clients/csharp/tests/QueueBox.Inbox.Tests`

## Stack rules
- The service is Kotlin on the JVM (Java 21), built with Gradle, not Go.
- Every dependency version lives in `gradle/libs.versions.toml`.
- A dependency change needs regenerated checksums in `gradle/verification-metadata.xml`; `CONTRIBUTING.md` holds the two commands.
- Schema changes are Flyway files with matching version numbers in `postgres/src/main/resources/db/postgresql` and `sqlserver/src/main/resources/db/sqlserver`.
- Config loads from YAML and from QUEUEBOX_ env vars through `config/src/main/kotlin/EnvConfigLoader.kt`.

## Domain words
- inbox source: one configured ingress (a broker consumer or a webhook path) that stores rows in the inbox.
- destination: one configured egress that the outbox delivers to.
- relay: the push path that copies processed inbox rows into the outbox.
