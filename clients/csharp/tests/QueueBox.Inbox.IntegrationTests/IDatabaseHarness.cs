using System.Data.Common;

namespace QueueBox.Inbox.IntegrationTests;

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
}
