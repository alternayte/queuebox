# Releasing QueueBox

This document describes how a maintainer publishes a QueueBox release.

QueueBox ships as a container image only. It publishes no Maven artifact, so there is no artifact
repository step.

## Versioning

QueueBox follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html). A version has
the form `MAJOR.MINOR.PATCH`.

| Part | Increment when |
|------|----------------|
| MAJOR | A change breaks an existing deployment. See the compatibility policy below. |
| MINOR | A release adds a capability and every existing deployment still works. |
| PATCH | A release only corrects a defect. |

The root build file derives the version from the Git tag. An untagged build carries
`0.0.0-SNAPSHOT`. The tag `v0.1.0` therefore produces the version `0.1.0`.

## Compatibility policy

### The configuration schema

1. A MINOR release can add an optional field. The default value must keep the previous behaviour.
2. A MINOR release can add a new value to an enumerated field.
3. A MINOR release must not remove a field, rename a field, or change the meaning of a field.
4. A field that is due for removal is first marked deprecated in a MINOR release. QueueBox logs a
   warning at startup when a deprecated field is set. The removal then happens in the next MAJOR
   release.
5. A MAJOR release can remove a deprecated field or change a default value. The changelog must
   name every such change and state the migration step.

### The database schema

1. A MINOR release can add a migration that adds a table, a column, or an index.
2. A MINOR release must not drop a column or change the type of a column.
3. Every new column is nullable, or carries a default value, so an older instance keeps running
   during a rolling upgrade.
4. QueueBox supports one version skew. An instance of the previous MINOR release must run against
   the schema of the current MINOR release.
5. A destructive change belongs to a MAJOR release. The changelog must name the change and state
   the downtime that it needs.
6. `docs/development/migrations.md` holds the rules for the migration files themselves.

## Release steps

1. Confirm that `main` is green. Run `./gradlew check` with a running Docker daemon.
2. Decide the version from the entries under `Unreleased` in `CHANGELOG.md`, using the rules above.
3. Move the `Unreleased` entries into a new version section in `CHANGELOG.md`. Add the release
   date. Add the two link definitions at the end of the file, and update the `Unreleased` link to
   compare against the new tag.
4. Commit the changelog with the message `docs: prepare the <version> release`.
5. Open a pull request, get it reviewed, and merge it into `main`.
6. Tag the merge commit on `main` and push the tag.

   ```bash
   git checkout main
   git pull
   git tag -a v0.1.0 -m "QueueBox 0.1.0"
   git push origin v0.1.0
   ```

7. The push of a `v*` tag starts `.github/workflows/release.yml`.
8. Watch the workflow. It must finish green.
9. Check the published image, then edit the GitHub release notes if they need a summary.

## What the release workflow produces

The workflow builds the image with Docker buildx for `linux/amd64` and `linux/arm64`, and pushes it
to the GitHub container registry with three tags.

| Tag | Example for the version 0.1.0 |
|-----|-------------------------------|
| The exact version | `ghcr.io/alternayte/queuebox:0.1.0` |
| The minor version | `ghcr.io/alternayte/queuebox:0.1` |
| The latest release | `ghcr.io/alternayte/queuebox:latest` |

The workflow attaches the software bill of materials and the provenance attestation to the GitHub
release.

## Verify the release

Start the published image against a test configuration, then read the information metric.

The image needs a database, so the check runs the released image against the Compose stack rather
than alone. QueueBox exits when no database answers within `database.startupTimeoutMs`.

```bash
RELEASE_IMAGE=ghcr.io/alternayte/queuebox:0.1.0 \
  docker compose -f docker-compose.yml -f docker-compose.release.yml up -d
curl -s http://localhost:8080/health/ready
# The Prometheus exporter strips the `_info` suffix, so the scrape carries `queuebox`.
curl -s http://localhost:8080/metrics | grep '^queuebox{'
```

Confirm three points.

1. The image starts and `/health/ready` answers 200 with every dependency up.
2. The `queuebox` metric, which `queuebox_info` becomes in a scrape, carries the version of the
   tag. See [../operations/metrics.md](../operations/metrics.md).
3. Both architectures exist. `docker manifest inspect ghcr.io/alternayte/queuebox:0.1.0` lists
   `linux/amd64` and `linux/arm64`.

## A failed release

Never move a tag that is already published, because an existing deployment can already pull the
image behind it. Correct the defect on `main` and publish the next patch version instead.
