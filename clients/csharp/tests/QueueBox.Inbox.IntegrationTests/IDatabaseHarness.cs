using System.Data.Common;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>Handles one message inside a test.</summary>
public delegate Task TestHandler(InboxMessage message, DbTransaction transaction, CancellationToken cancellationToken);

/// <summary>
/// One database under test. The contract tests are written once and run against every harness,
/// because a difference between two dialects must never be a difference of guarantee.
/// </summary>
public interface IDatabaseHarness
{
    /// <summary>The dialect the worker must use.</summary>
    SqlDialect Dialect { get; }

    /// <summary>Opens connections for the worker and for the tests.</summary>
    IInboxConnectionSource Connections { get; }

    /// <summary>Open one connection for a test's own statement.</summary>
    Task<DbConnection> OpenAsync();

    /// <summary>Store one pending pull row and return its identifier.</summary>
    Task<Guid> InsertPendingAsync(
        string source,
        string idempotencyKey,
        string payloadJson,
        string? aggregateId = null,
        string? eventType = null,
        string? correlationId = null,
        int attempt = 0);

    /// <summary>Read the state, the attempt and the last error of one row.</summary>
    Task<(string State, int Attempt, string? LastError)> ReadRowAsync(Guid id);

    /// <summary>Read the moment at which the row can be claimed again.</summary>
    Task<DateTimeOffset> ReadScheduledAtAsync(Guid id);

    /// <summary>Take the claim away from the worker, as a second worker would.</summary>
    Task StealClaimAsync(Guid id);

    /// <summary>Count the rows the handler wrote into the application's own table.</summary>
    Task<int> CountApplicationRowsAsync(string key);

    /// <summary>The statement that a handler runs to write into the application's own table.</summary>
    string InsertApplicationRowSql { get; }

    /// <summary>Remove every row, so one test cannot see another test's work.</summary>
    Task ResetAsync();

    /// <summary>Create an inbox table under other names, and return the mapping that names it.</summary>
    Task<InboxSchema> CreateMappedSchemaAsync();

    /// <summary>Read the state of one row of the mapped table.</summary>
    Task<string> ReadMappedStateAsync(InboxSchema schema, Guid id);

    /// <summary>Store one pending pull row in the mapped table.</summary>
    Task<Guid> InsertMappedPendingAsync(InboxSchema schema, string source, string idempotencyKey, string payloadJson);

    /// <summary>Store several pending pull rows that share one aggregate.</summary>
    /// <param name="source">The source of every row.</param>
    /// <param name="aggregateId">The aggregate every row shares.</param>
    /// <param name="count">How many rows to store.</param>
    async Task SeedPendingAsync(string source, string aggregateId, int count)
    {
        for (var i = 0; i < count; i++)
        {
            await InsertPendingAsync(source, $"{aggregateId}-{i}-{Guid.NewGuid():N}", "{}", aggregateId: aggregateId);
        }
    }

    /// <summary>
    /// Run several workers against this database until they handled the expected number of
    /// messages between them, then stop every worker.
    /// </summary>
    /// <param name="count">How many workers to run at one time.</param>
    /// <param name="handler">The handler every worker calls.</param>
    /// <param name="untilProcessed">How many handler calls to wait for before the stop.</param>
    /// <param name="source">The source every worker takes from.</param>
    /// <param name="batchSize">The batch size of every worker.</param>
    /// <param name="leaseMs">The lease of every worker, in milliseconds.</param>
    async Task RunWorkersAsync(
        int count,
        TestHandler handler,
        int untilProcessed,
        string source = "s",
        int batchSize = 10,
        int leaseMs = 30_000)
    {
        using var done = new CountdownEvent(untilProcessed);

        async Task WrappedAsync(InboxMessage message, DbTransaction transaction, CancellationToken token)
        {
            try
            {
                await handler(message, transaction, token).ConfigureAwait(false);
            }
            finally
            {
                if (!done.IsSet)
                {
                    done.Signal();
                }
            }
        }

        using var stop = new CancellationTokenSource(TimeSpan.FromMinutes(2));

        var workers = Enumerable.Range(0, count)
            .Select(_ => new InboxWorker(Connections, new InboxOptions
            {
                Source = source,
                Dialect = Dialect,
                BatchSize = batchSize,
                LeaseMs = leaseMs,
                PollInterval = TimeSpan.FromMilliseconds(50),
                ShutdownGrace = TimeSpan.FromSeconds(10),
            }).RunAsync(new InboxHandler(WrappedAsync), stop.Token))
            .ToArray();

        done.Wait(stop.Token);

        // The failure or completion path runs after the handler returns, so give it room to finish.
        await Task.Delay(TimeSpan.FromMilliseconds(300), CancellationToken.None).ConfigureAwait(false);
        await stop.CancelAsync().ConfigureAwait(false);
        await Task.WhenAll(workers).ConfigureAwait(false);
    }
}
