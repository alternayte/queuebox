# QueueBox.Inbox

A pull-inbox worker for [QueueBox](https://github.com/alternayte/queuebox).

QueueBox makes the push path need no code. The pull path needed five SQL statements, a renewal
timer and a lease discipline, written by hand in every application. This package holds all of it.

The library claims the messages of one source, hands each one to your handler inside a
transaction, and completes, retries or dead-letters it. It writes no schema, and it needs no ORM.

## What it guarantees

**Your writes and the completion commit together.** That is the whole point of the pull path.
The handler receives the transaction, and the library runs the completion inside it. If the
completion affects no row, the lease was lost, another worker owns the message, and the library
rolls your writes back and reports nothing as done.

## Requirements

| | |
|---|---|
| Framework | .NET 8.0 or later |
| Database | PostgreSQL, through `Npgsql`, or SQL Server, through `Microsoft.Data.SqlClient` |
| QueueBox | 0.1.0 or later, which is the V6 schema |

The package itself depends on no database driver. It talks to `System.Data.Common`, so one
package serves both dialects, and your application brings the driver it already has.

## Install

```
dotnet add package QueueBox.Inbox
```

## PostgreSQL

```csharp
using Npgsql;
using QueueBox.Inbox;

await using var dataSource = NpgsqlDataSource.Create("Host=localhost;Database=queuebox;Username=app;Password=secret");

var worker = new InboxWorker(
    InboxConnections.From(dataSource),
    new InboxOptions { Source = "orders", BatchSize = 10, LeaseMs = 30_000 });

await worker.RunAsync(async (message, transaction, cancellationToken) =>
{
    await using var command = transaction.CreateCommand();

    command.CommandText = "INSERT INTO orders (id, total) VALUES (@id, @total)";
    command
        .WithParameter("@id", message.Payload.GetProperty("id").GetString())
        .WithParameter("@total", message.Payload.GetProperty("total").GetInt32());

    await command.ExecuteNonQueryAsync(cancellationToken);
});
```

## SQL Server

`Microsoft.Data.SqlClient` ships no `DbDataSource`, so pass the factory and the connection
string instead. Everything after that line is the same.

```csharp
using Microsoft.Data.SqlClient;
using QueueBox.Inbox;

var worker = new InboxWorker(
    InboxConnections.From(SqlClientFactory.Instance, "Server=localhost;Database=queuebox;User ID=app;Password=secret;Encrypt=False"),
    new InboxOptions { Source = "orders", Dialect = SqlDialect.SqlServer });

await worker.RunAsync(async (message, transaction, cancellationToken) => { /* ... */ });
```

Set the `Microsoft.Data.SqlClient` command timeout to at least 30 seconds. Its default
is 30 seconds, equal to the old lock timeout, so a caller that leaves the default in
place must still raise it: a tie counts as a loss. The SQL Server claim runs
`sp_getapplock` with a 10 second lock timeout of its own, so the server always raises
Msg 51000 before a driver command timeout of 30 seconds or more can abort the call. A
client-side abort does not roll back the claim's transaction, because the lock is held
under `@LockOwner = 'Transaction'`. The symptom of a shorter command timeout is an
abandoned application lock: the per-source claim lock stays held until the connection
resets, and every later claim on that source stalls behind it.

## What the handler receives

| Field | Meaning |
|-------|---------|
| `message.Id` | The inbox row identifier |
| `message.Source` | The source name |
| `message.IdempotencyKey` | The deduplication key. The full identity is the source and this key together |
| `message.AggregateId` | Nullable |
| `message.EventType` | Nullable |
| `message.Payload` | The JSON body, as a `JsonElement` |
| `message.Attempt` | Zero on the first delivery |
| `message.CorrelationId` | Nullable, for logs |

The claim token is absent on purpose. The library owns the token, because a handler that could
reach it could complete a message out of band.

## Rules for a handler

1. Write every application change through the transaction the handler receives.
2. Do not commit the transaction and do not roll it back. The library owns both.
3. Throw to fail the message. The library rolls the transaction back, so no partial write stays.
4. Honour the cancellation token. It fires when the lease is lost and when a shutdown runs out
   of grace.
5. Do external work, such as an HTTP call, only where a repeat is safe. A transaction cannot
   roll back a call to another system. Deduplicate on the source and the idempotency key.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `Source` | none, it is mandatory | The source whose messages this worker takes |
| `BatchSize` | 10 | The largest number of messages one claim takes |
| `LeaseMs` | 30000 | The lease duration. The renewal runs every third of it |
| `MaxConcurrency` | 1 | The largest number of handlers that run at one time |
| `PollInterval` | 1 second | The wait after a claim that returned nothing |
| `ShutdownGrace` | 30 seconds | How long a stop waits for the handlers already running |
| `Dialect` | `PostgreSql` | `PostgreSql` or `SqlServer` |
| `Schema` | the QueueBox names | The table and column names, when an operator mapped them |
| `RetryPolicy` | `DefaultRetryPolicy` | What happens to a message whose handler threw |

The concurrency default is one. A handler meets no sibling message unless the caller raises it.
Raise it only when the handler is safe against a sibling message of another aggregate:

```csharp
var options = new InboxOptions { Source = "orders", MaxConcurrency = 10 };
```

## Failure, retry and the dead letter

The default policy retries while `Attempt < maxAttempts`, with an exponential backoff and
jitter, and dead-letters after that. Only your application knows that a validation error must
never be retried while a timeout must, so write your own policy:

```csharp
public sealed class OrdersRetryPolicy : IInboxRetryPolicy
{
    public InboxFailureAction Decide(InboxMessage message, Exception failure) => failure switch
    {
        // A bad payload never becomes good. Do not spend five attempts on it.
        JsonException => InboxFailureAction.DeadLetter(),
        _ when message.Attempt >= 5 => InboxFailureAction.DeadLetter(),
        _ => InboxFailureAction.Retry(TimeSpan.FromSeconds(Math.Pow(2, message.Attempt))),
    };
}
```

## Shutdown

Cancel the token you passed to `RunAsync`. The worker stops claiming at once. The handlers that
already run keep `ShutdownGrace`, and a handler that does not finish inside it is cancelled. Its
message is abandoned: the library completes nothing, spends no attempt on it, and lets the lease
expire so another worker takes it.

## Logging

Pass an `ILogger<InboxWorker>`. Without one the library prints nothing. Every message and every
error passes through the same redaction QueueBox itself uses, so a connection string password
reaches neither a log line nor the `last_error` column.

## Mapped table and column names

QueueBox lets an operator rename the inbox table and its columns. Name them here as well:

```csharp
var options = new InboxOptions
{
    Source = "orders",
    Schema = InboxSchema.Default with { Table = "qb_messages", State = "row_state" },
};
```

Every name is quoted and checked before it reaches the database, so a mapping cannot carry SQL.

## Releases

The library releases on its own tag, so a library fix never needs a QueueBox release, and a
QueueBox release never republishes the library.

```bash
git tag csharp-v0.1.1 && git push origin csharp-v0.1.1
```

The tag drives the package version. The `<Version>` in the project file is the development
default only. The README states the minimum QueueBox version, and the library checks nothing at
runtime.

## License

Apache-2.0.
