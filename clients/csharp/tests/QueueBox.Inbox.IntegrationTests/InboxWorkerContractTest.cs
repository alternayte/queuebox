using System.Data.Common;
using System.Text.Json;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>
/// The contract of <c>clients/contract-tests.md</c>. Every harness runs the whole list, because
/// a difference between two dialects must be a difference of idiom, never of guarantee.
/// </summary>
public abstract class InboxWorkerContractTest : IAsyncLifetime
{
    private const string Source = "orders";

    /// <summary>The database under test.</summary>
    protected abstract IDatabaseHarness Harness { get; }

    /// <inheritdoc />
    public Task InitializeAsync() => Harness.ResetAsync();

    /// <inheritdoc />
    public Task DisposeAsync() => Task.CompletedTask;

    // Item 1.
    [Fact]
    public async Task TheHandlerReceivesEveryField()
    {
        var id = await Harness.InsertPendingAsync(
            Source, "key-1", """{"id":"order-1","total":42}""",
            aggregateId: "agg-1", eventType: "OrderPlaced", correlationId: "corr-1", attempt: 2);

        InboxMessage? seen = null;

        await RunUntilAsync(1, (message, _, _) =>
        {
            seen = message;
            return Task.CompletedTask;
        });

        Assert.NotNull(seen);
        Assert.Equal(id, seen.Id);
        Assert.Equal(Source, seen.Source);
        Assert.Equal("key-1", seen.IdempotencyKey);
        Assert.Equal("agg-1", seen.AggregateId);
        Assert.Equal("OrderPlaced", seen.EventType);
        Assert.Equal("corr-1", seen.CorrelationId);
        Assert.Equal(2, seen.Attempt);
        Assert.Equal("order-1", seen.Payload.GetProperty("id").GetString());
        Assert.Equal(42, seen.Payload.GetProperty("total").GetInt32());
    }

    // Item 1, the nullable fields.
    [Fact]
    public async Task TheNullableFieldsArriveAsNull()
    {
        await Harness.InsertPendingAsync(Source, "key-null", """{"a":1}""");

        InboxMessage? seen = null;

        await RunUntilAsync(1, (message, _, _) =>
        {
            seen = message;
            return Task.CompletedTask;
        });

        Assert.NotNull(seen);
        Assert.Null(seen.AggregateId);
        Assert.Null(seen.EventType);
        Assert.Null(seen.CorrelationId);
        Assert.Equal(0, seen.Attempt);
    }

    // Item 2. This is the whole point of the pull path.
    [Fact]
    public async Task TheWritesAndTheCompletionCommitTogether()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-2", "{}");

        await RunUntilAsync(1, (_, transaction, token) => WriteApplicationRowAsync(transaction, "row-2", token));

        Assert.Equal("processed", (await Harness.ReadRowAsync(id)).State);
        Assert.Equal(1, await Harness.CountApplicationRowsAsync("row-2"));
    }

    // Item 3.
    [Fact]
    public async Task AHandlerThatThrowsLeavesNoWriteBehind()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-3", "{}");

        await RunUntilAsync(1, async (_, transaction, token) =>
        {
            await WriteApplicationRowAsync(transaction, "row-3", token);
            throw new InvalidOperationException("the handler failed");
        });

        Assert.Equal(0, await Harness.CountApplicationRowsAsync("row-3"));
        Assert.NotEqual("processed", (await Harness.ReadRowAsync(id)).State);
    }

    // Item 4. The completion affects zero rows, so the application's writes go with it.
    [Fact]
    public async Task ACompletionThatAffectsNoRowRollsTheWritesBack()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-4", "{}");

        // The lease is long, so no renewal notices the theft. The completion must catch it.
        await RunUntilAsync(1, async (message, transaction, token) =>
        {
            await WriteApplicationRowAsync(transaction, "row-4", token);
            await Harness.StealClaimAsync(message.Id);
        }, leaseMs: 600_000);

        Assert.Equal(0, await Harness.CountApplicationRowsAsync("row-4"));
        Assert.Equal("processing", (await Harness.ReadRowAsync(id)).State);
    }

    // Item 5.
    [Fact]
    public async Task TwoWorkersNeverHoldTheSameMessage()
    {
        const int total = 20;

        for (var i = 0; i < total; i++)
        {
            await Harness.InsertPendingAsync(Source, $"key-5-{i}", "{}");
        }

        var seen = new System.Collections.Concurrent.ConcurrentBag<Guid>();
        using var done = new CountdownEvent(total);

        Task Handle(InboxMessage message, DbTransaction transaction, CancellationToken token)
        {
            seen.Add(message.Id);
            done.Signal();
            return Task.CompletedTask;
        }

        using var stop = new CancellationTokenSource(TimeSpan.FromMinutes(2));

        var first = NewWorker(batchSize: 3).RunAsync(Handle, stop.Token);
        var second = NewWorker(batchSize: 3).RunAsync(Handle, stop.Token);

        done.Wait(stop.Token);
        await stop.CancelAsync();
        await Task.WhenAll(first, second);

        Assert.Equal(total, seen.Count);
        Assert.Equal(total, seen.Distinct().Count());
    }

    // Item 6.
    [Fact]
    public async Task ASlowHandlerRenewsAndKeepsOwnership()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-6", "{}");

        // The lease is one second and the renewal runs every third of it, so a handler of three
        // seconds needs several renewals to survive.
        await RunUntilAsync(1, async (_, transaction, token) =>
        {
            await Task.Delay(TimeSpan.FromSeconds(3), token);
            await WriteApplicationRowAsync(transaction, "row-6", token);
        }, leaseMs: 1_000);

        Assert.Equal("processed", (await Harness.ReadRowAsync(id)).State);
        Assert.Equal(1, await Harness.CountApplicationRowsAsync("row-6"));
    }

    // Item 7.
    [Fact]
    public async Task ALostRenewalCancelsTheHandlerAndCompletesNothing()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-7", "{}");

        var cancelled = false;

        await RunUntilAsync(1, async (message, transaction, token) =>
        {
            await Harness.StealClaimAsync(message.Id);

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(30), token);
            }
            catch (OperationCanceledException)
            {
                cancelled = true;
                throw;
            }
        }, leaseMs: 1_000);

        Assert.True(cancelled, "The renewal must cancel the handler when the ownership is lost.");
        Assert.Equal("processing", (await Harness.ReadRowAsync(id)).State);
    }

    // Item 9.
    [Fact]
    public async Task ARetryIncrementsTheAttemptAndAppliesTheBackoff()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-9", "{}");
        var before = await Harness.ReadScheduledAtAsync(id);

        await RunUntilAsync(1, (_, _, _) => throw new InvalidOperationException("no"),
            policy: new DefaultRetryPolicy(maxAttempts: 5, baseDelay: TimeSpan.FromMinutes(1), maxDelay: TimeSpan.FromMinutes(1), jitter: 0));

        var row = await Harness.ReadRowAsync(id);

        Assert.Equal("pending", row.State);
        Assert.Equal(1, row.Attempt);
        Assert.NotNull(row.LastError);
        Assert.True(await Harness.ReadScheduledAtAsync(id) > before.AddSeconds(30), "The backoff must move the schedule forward.");
    }

    // Item 10.
    [Fact]
    public async Task TheDeadLetterIsReachedAtTheCeiling()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-10", "{}", attempt: 3);

        await RunUntilAsync(1, (_, _, _) => throw new InvalidOperationException("no"),
            policy: new DefaultRetryPolicy(maxAttempts: 3, baseDelay: TimeSpan.FromSeconds(1), maxDelay: TimeSpan.FromSeconds(1), jitter: 0));

        var row = await Harness.ReadRowAsync(id);

        Assert.Equal("dead", row.State);
        Assert.NotNull(row.LastError);
    }

    // Item 15. The failure text reaches the database, so a secret must not travel with it.
    [Fact]
    public async Task ASecretNeverReachesTheLastErrorColumn()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-15", "{}");

        await RunUntilAsync(1, (_, _, _) =>
            throw new InvalidOperationException("connect failed: Host=db;Password=hunter2;Database=queuebox"));

        var row = await Harness.ReadRowAsync(id);

        Assert.NotNull(row.LastError);
        Assert.DoesNotContain("hunter2", row.LastError, StringComparison.Ordinal);
    }

    // Item 17.
    [Fact]
    public async Task TheWorkerNeverHoldsMoreThanTheBatch()
    {
        const int total = 12;

        for (var i = 0; i < total; i++)
        {
            await Harness.InsertPendingAsync(Source, $"key-17-{i}", "{}");
        }

        var inFlight = 0;
        var highWater = 0;
        using var done = new CountdownEvent(total);

        await RunUntilAsync(done, async (_, _, token) =>
        {
            var now = Interlocked.Increment(ref inFlight);
            InterlockedMax(ref highWater, now);
            await Task.Delay(TimeSpan.FromMilliseconds(50), token);
            Interlocked.Decrement(ref inFlight);
            done.Signal();
        }, batchSize: 3);

        Assert.True(highWater <= 3, $"The worker held {highWater} messages, and the batch is 3.");
    }

    // Item 8.
    [Fact]
    public async Task AnAbandonedMessageReturnsAfterTheLeaseExpires()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-8", "{}");

        using var reached = new CountdownEvent(1);
        using var stop = new CancellationTokenSource();

        var worker = new InboxWorker(Harness.Connections, new InboxOptions
        {
            Source = Source,
            Dialect = Harness.Dialect,
            LeaseMs = 1_000,
            PollInterval = TimeSpan.FromMilliseconds(50),
            // The shutdown gives no grace at all, which is how a killed worker behaves.
            ShutdownGrace = TimeSpan.Zero,
        });

        var run = worker.RunAsync(async (_, _, token) =>
        {
            reached.Signal();
            await Task.Delay(TimeSpan.FromMinutes(5), token);
        }, stop.Token);

        reached.Wait(TimeSpan.FromSeconds(30));
        await stop.CancelAsync();
        await run;

        // The message was abandoned, not completed, and the shutdown spent no attempt on it.
        var abandoned = await Harness.ReadRowAsync(id);
        Assert.Equal("processing", abandoned.State);
        Assert.Equal(0, abandoned.Attempt);

        // The lease expires, so a second worker takes the message back with no operator action.
        var second = false;
        await RunUntilAsync(1, (message, _, _) =>
        {
            second = message.Id == id;
            return Task.CompletedTask;
        });

        Assert.True(second, "The abandoned message must return after the lease expires.");
    }

    // Item 11.
    [Fact]
    public async Task AStaleTokenChangesNothing()
    {
        var id = await Harness.InsertPendingAsync(Source, "key-11", "{}");

        var sql = InboxSql.For(Harness.Dialect, InboxSchema.Default);
        var stale = Guid.NewGuid();

        // Another worker owns the message under a token of its own.
        await Harness.StealClaimAsync(id);

        foreach (var statement in new[] { sql.Complete, sql.Retry, sql.Dead })
        {
            await using var connection = await Harness.OpenAsync();
            await using var command = connection.CreateCommand();

            command.CommandText = statement;
            AddParameter(command, "@id", id);
            AddParameter(command, "@token", stale);
            AddParameter(command, "@error", "no");
            AddParameter(command, "@delay_ms", 1000);

            Assert.Equal(0, await command.ExecuteNonQueryAsync());
        }

        var row = await Harness.ReadRowAsync(id);

        Assert.Equal("processing", row.State);
        Assert.Equal(0, row.Attempt);
        Assert.Null(row.LastError);
    }

    // Item 12.
    [Fact]
    public async Task AShutdownClaimsNothingNewAndAbandonsRatherThanCompletes()
    {
        var first = await Harness.InsertPendingAsync(Source, "key-12-a", "{}");
        var second = await Harness.InsertPendingAsync(Source, "key-12-b", "{}");

        using var reached = new CountdownEvent(1);
        using var stop = new CancellationTokenSource();

        // A batch of one, so the second message is still pending when the stop arrives.
        var worker = new InboxWorker(Harness.Connections, new InboxOptions
        {
            Source = Source,
            Dialect = Harness.Dialect,
            BatchSize = 1,
            LeaseMs = 60_000,
            PollInterval = TimeSpan.FromMilliseconds(50),
            ShutdownGrace = TimeSpan.Zero,
        });

        var run = worker.RunAsync(async (_, _, token) =>
        {
            reached.Signal();
            await Task.Delay(TimeSpan.FromMinutes(5), token);
        }, stop.Token);

        reached.Wait(TimeSpan.FromSeconds(30));
        await stop.CancelAsync();
        await run;

        Assert.NotEqual("processed", (await Harness.ReadRowAsync(first)).State);
        Assert.Equal("pending", (await Harness.ReadRowAsync(second)).State);
    }

    // Item 13.
    [Fact]
    public async Task AMappedTableAndColumnNamesWork()
    {
        var schema = await Harness.CreateMappedSchemaAsync();
        var id = await Harness.InsertMappedPendingAsync(schema, Source, "key-13", """{"a":1}""");

        using var done = new CountdownEvent(1);
        using var stop = new CancellationTokenSource(TimeSpan.FromMinutes(1));

        var worker = new InboxWorker(Harness.Connections, new InboxOptions
        {
            Source = Source,
            Dialect = Harness.Dialect,
            Schema = schema,
            PollInterval = TimeSpan.FromMilliseconds(50),
        });

        var run = worker.RunAsync(async (message, transaction, token) =>
        {
            Assert.Equal(id, message.Id);
            await WriteApplicationRowAsync(transaction, "row-13", token);
            done.Signal();
        }, stop.Token);

        done.Wait(stop.Token);
        await Task.Delay(TimeSpan.FromMilliseconds(500), CancellationToken.None);
        await stop.CancelAsync();
        await run;

        Assert.Equal("processed", await Harness.ReadMappedStateAsync(schema, id));
        Assert.Equal(1, await Harness.CountApplicationRowsAsync("row-13"));
    }

    // Item 16.
    [Fact]
    public async Task AnEmptyClaimDoesNotStartAPollingStorm()
    {
        var counting = new CountingConnections(Harness.Connections);

        var worker = new InboxWorker(counting, new InboxOptions
        {
            Source = Source,
            Dialect = Harness.Dialect,
            PollInterval = TimeSpan.FromMilliseconds(200),
        });

        using var stop = new CancellationTokenSource();
        var run = worker.RunAsync((_, _, _) => Task.CompletedTask, stop.Token);

        await Task.Delay(TimeSpan.FromSeconds(2), CancellationToken.None);
        await stop.CancelAsync();
        await run;

        // The table is empty, so every connection belongs to a claim. Two seconds at two hundred
        // milliseconds is about ten claims. The band is wide, because a container is not a clock.
        Assert.InRange(counting.Opened, 4, 20);
    }

    private static void AddParameter(DbCommand command, string name, object value)
    {
        var parameter = command.CreateParameter();
        parameter.ParameterName = name;
        parameter.Value = value;
        command.Parameters.Add(parameter);
    }

    private static void InterlockedMax(ref int target, int value)
    {
        int seen;

        do
        {
            seen = Volatile.Read(ref target);

            if (value <= seen)
            {
                return;
            }
        }
        while (Interlocked.CompareExchange(ref target, value, seen) != seen);
    }

    private async Task WriteApplicationRowAsync(DbTransaction transaction, string key, CancellationToken token)
    {
        await using var command = transaction.Connection!.CreateCommand();

        command.Transaction = transaction;
        command.CommandText = Harness.InsertApplicationRowSql;

        var parameter = command.CreateParameter();
        parameter.ParameterName = "@key";
        parameter.Value = key;
        command.Parameters.Add(parameter);

        await command.ExecuteNonQueryAsync(token);
    }

    private InboxWorker NewWorker(int batchSize = 10, int leaseMs = 30_000, IInboxRetryPolicy? policy = null) =>
        new(Harness.Connections, new InboxOptions
        {
            Source = Source,
            BatchSize = batchSize,
            LeaseMs = leaseMs,
            Dialect = Harness.Dialect,
            PollInterval = TimeSpan.FromMilliseconds(50),
            ShutdownGrace = TimeSpan.FromSeconds(10),
            RetryPolicy = policy,
        });

    /// <summary>Run the worker until it handled the expected number of messages, then stop it.</summary>
    private async Task RunUntilAsync(
        int expected,
        Func<InboxMessage, DbTransaction, CancellationToken, Task> handler,
        int batchSize = 10,
        int leaseMs = 30_000,
        IInboxRetryPolicy? policy = null)
    {
        using var done = new CountdownEvent(expected);

        await RunUntilAsync(done, async (message, transaction, token) =>
        {
            try
            {
                await handler(message, transaction, token);
            }
            finally
            {
                done.Signal();
            }
        }, batchSize, leaseMs, policy);
    }

    private async Task RunUntilAsync(
        CountdownEvent done,
        Func<InboxMessage, DbTransaction, CancellationToken, Task> handler,
        int batchSize = 10,
        int leaseMs = 30_000,
        IInboxRetryPolicy? policy = null)
    {
        using var stop = new CancellationTokenSource(TimeSpan.FromMinutes(2));

        var run = NewWorker(batchSize, leaseMs, policy).RunAsync(new InboxHandler(handler), stop.Token);

        done.Wait(stop.Token);

        // The failure path runs after the handler returned, so give it room to finish.
        await Task.Delay(TimeSpan.FromMilliseconds(500), CancellationToken.None);
        await stop.CancelAsync();
        await run;
    }
}
