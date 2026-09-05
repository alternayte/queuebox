# Architecture

This document describes the modules, the message lifecycle and the message states. Every fact here
comes from the shipped code. A test asserts the state set and the column width against the source.

## Modules

The graph shows the Gradle project dependencies. It comes from the `build.gradle.kts` file of each
module.

```mermaid
graph TD
    app --> core
    app --> config
    app --> postgres
    app --> rabbitmq
    app --> outbox-service
    app --> inbox-service
    config --> core
    postgres --> core
    postgres --> config
    sqlserver --> core
    sqlserver --> config
    rabbitmq --> core
    rabbitmq --> config
    rabbitmq --> inbox-service
    outbox-service --> core
    outbox-service --> config
    outbox-service --> postgres
    inbox-service --> core
    inbox-service --> config
    inbox-service --> postgres
    inbox-service --> outbox-service
```

The `core` module has no dependency on another module. The `sqlserver` module has no dependent
module. The `app` module does not depend on it. The repository layer loads a provider by
reflection, so a provider module can be absent at compile time. See
[ADR 0001](adr/0001-reflection-for-database-providers.md).

## Message lifecycle

```mermaid
sequenceDiagram
    participant Application
    participant Database
    participant Relay as Outbox relay
    participant Destination
    Application->>Database: INSERT INTO outbox (state 'pending')
    Note over Application,Database: The insert shares the transaction of the business write.
    Relay->>Database: claim a batch (state 'pending' to 'processing')
    Database-->>Relay: the claimed messages
    Relay->>Destination: deliver the payload
    alt the destination accepts
        Relay->>Database: mark sent (state 'sent')
    else the attempt fails and an attempt remains
        Relay->>Database: schedule a retry (state 'pending', attempt + 1)
    else no attempt remains
        Relay->>Database: mark dead (state 'dead')
    end
```

The inbox follows the same shape, with one difference that matters. An HTTP client posts a message
to the inbox route. The route applies the source transform and writes the row in state `pending`.
The inbox relay claims the row, writes an OUTBOX row from it, and then writes state `processed` or
state `dead`. The relay delivers nothing itself, and it runs no transform: the transform ran at
ingestion, and the outbox machinery routes and delivers the message. Decision 1 of section 2A of
`hardening-doc.md` settles this. [message-flow.md](message-flow.md) holds the step list.

A claim that a crash leaves behind returns to state `pending`. The reclaim step finds a row that
stays in state `processing` longer than the visibility timeout.

## Outbox states

```mermaid
stateDiagram-v2
    [*] --> pending: insert
    pending --> processing: claim
    processing --> sent: the destination accepts
    processing --> pending: retry, or reclaim after the claim timeout
    processing --> dead: no attempt remains
    sent --> [*]
    dead --> [*]
```

The outbox state set:

<!-- states:outbox -->
```text
pending
processing
sent
dead
```

## Inbox states

```mermaid
stateDiagram-v2
    [*] --> pending: the inbox route accepts the message
    pending --> processing: claim
    processing --> processed: the destination accepts
    processing --> pending: release, or reclaim after the claim timeout
    processing --> dead: the message cannot be delivered
    processed --> [*]
    dead --> [*]
```

The inbox state set:

<!-- states:inbox -->
```text
pending
processing
processed
dead
```

`dead` reaches the inbox by two routes. `storeDead` writes it in one transaction when a source
transform rejects an AMQP message. The relay writes it when the topic template of the source
renders empty, because such a message can reach no destination. `claimPending` selects `pending`
rows, so no read path in the shipped code returns a dead inbox row. The row exists for an operator
to read with SQL. See [operations/dead-letter.md](operations/dead-letter.md).

## The state column

Both schemas declare the state column with a width of 50 characters. The PostgreSQL migration uses
`VARCHAR(50)`. The SQL Server migration uses `NVARCHAR(50)`. An earlier document stated
`VARCHAR(20)`. That statement was wrong.

## The state literals and MessageState

The repositories write the literals above. `MessageState` in `core` is the in-memory form.
`MessageState.Sent` carries both the outbox literal `sent` and the inbox literal `processed`.
`MessageState.Failed` is never written to the database. A repository returns it when it reads a
literal that it does not know.

## Decision: `canTransitionTo` is deleted

`MessageState.canTransitionTo` declared a transition table. No production code called it. A
repository-wide search found calls only in its own unit test. The table also contradicted the
repositories. It permitted `processing` to `failed`, and it refused `processing` to `pending`, but
the repositories write `pending` on a retry and never write `failed`.

The decision is to delete the function, and to delete `MessageStateTest.kt` with it. The reason is
that a rule which nothing enforces is a false claim about the product. The repositories hold the
state machine, and the diagrams above describe it. `DocumentedStateSetTest` now compares this
document against the repository sources, so the document and the code cannot drift apart.

## The module tree

Each module has one responsibility. The graph above shows how they depend on each other.

```
queuebox/
├── app/                    # Main application, HTTP server
├── config/                 # Configuration loading and validation
├── core/                   # Domain models and interfaces
├── inbox-service/          # Inbox handling logic
├── outbox-service/         # Outbox polling and delivery
├── postgres/               # PostgreSQL repository implementation
├── sqlserver/              # SQL Server repository implementation
└── rabbitmq/               # RabbitMQ consumer and publisher
```
