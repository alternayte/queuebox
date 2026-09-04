# Building QueueBox

This document describes how to build QueueBox from the source. Most users do not need it. The
[README](../../README.md) Quick Start pulls the published image from GitHub Container Registry.

## Requirements

- A Java Development Kit, version 21. The build targets Java 21 bytecode and Gradle downloads the
  toolchain if the local JDK does not match. Java 22 and Java 23 also compile the code. Java 20 and
  earlier do not.
- Docker. The tests start real databases with Testcontainers.
- Git. The build reads the version from the Git tag, so a shallow clone without tags produces the
  fallback version.

The repository contains the Gradle wrapper, version 8.13. Do not install Gradle.

## Build the project

```bash
./gradlew build
```

## Run the tests

```bash
./gradlew check
```

The command starts PostgreSQL, SQL Server and RabbitMQ containers. Docker must run.

Two environment variables select the database image. The continuous integration matrix uses them to
run the same tests against more than one database release.

```bash
QUEUEBOX_TEST_POSTGRES_IMAGE=postgres:14 ./gradlew :postgres:test
QUEUEBOX_TEST_SQLSERVER_IMAGE=mcr.microsoft.com/mssql/server:2019-latest ./gradlew :sqlserver:test
```

The defaults are `postgres:16` and `mcr.microsoft.com/mssql/server:2022-latest`.

## Coverage report

```bash
./gradlew jacocoAggregatedReport
```

The report goes to `build/reports/jacoco/aggregated/html/index.html`.

## The version

The root build file derives the version from the Git tag. The tag `v1.2.3` gives the version
`1.2.3`. A commit after that tag gives `1.2.3-<count>-g<hash>`. A modified working tree adds
`-SNAPSHOT`. A clone with no matching tag gives `0.0.0-SNAPSHOT`.

To override the version, pass a Gradle property:

```bash
./gradlew build -PqueueboxVersion=1.2.3
```

QueueBox publishes a container image only. The build applies no `maven-publish` plugin and produces
no library artifact.

## Software bill of materials

```bash
./gradlew sbom --no-configuration-cache
```

The CycloneDX plugin does not support the Gradle configuration cache, so the flag is mandatory. The
file goes to `build/reports/queuebox-<version>-sbom.json`.

## Build the container image

A local single-architecture build:

```bash
docker build -t queuebox:local .
```

A multi-architecture build needs a buildx builder. Create the builder once:

```bash
docker buildx create --name queuebox-builder --use
```

Then build for both architectures:

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t queuebox:local .
```

A multi-architecture image cannot load into the local Docker daemon. To keep the result, push it to
a registry:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t ghcr.io/<your-account>/queuebox:1.2.3 \
  --push .
```

## Run the application without a container

The application reads the `QUEUEBOX_` variables. It reads no `DB_URL`, `DB_USER` or `DB_PASSWORD`
variable. [../configuration.md](../configuration.md) holds the naming rule.

```bash
export QUEUEBOX_DATABASE_URL=jdbc:postgresql://localhost:5432/queuebox
export QUEUEBOX_DATABASE_USERNAME=postgres
export QUEUEBOX_DATABASE_PASSWORD=secret
./gradlew run
```

`./gradlew run --continuous` rebuilds and restarts the application on every source change. Use it
while you write code.

## Release

A push of a `v*` tag starts `.github/workflows/release.yml`. The workflow builds the image for
`linux/amd64` and `linux/arm64`, pushes it to GHCR with the exact version, the minor version and
`latest`, and attaches the bill of materials and the provenance attestation.
