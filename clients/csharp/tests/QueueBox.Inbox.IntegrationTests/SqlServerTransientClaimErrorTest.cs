using Microsoft.Data.SqlClient;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>
/// Item 19 of <c>clients/contract-tests.md</c>, from finding F-087. A claim lock failure
/// (<c>Msg 51000</c>, from <c>sp_getapplock</c> returning negative) is a transient failure of the
/// claim call, never a failure of a message.
/// </summary>
public sealed class SqlServerTransientClaimErrorTest(SqlServerHarness harness) : IClassFixture<SqlServerHarness>, IAsyncLifetime
{
    private const string Source = "locked-source";

    public Task InitializeAsync() => harness.ResetAsync();

    public Task DisposeAsync() => Task.CompletedTask;

    // Item 19.
    [Fact]
    public async Task AClaimLockFailureIsTreatedAsTransient()
    {
        var id = await harness.InsertPendingAsync(Source, "key-19", "{}");

        // Hold the per-source applock from outside the worker, for a session, with no timeout.
        // Every claim on this source must wait out the worker's own 30-second lock timeout and
        // then see sp_getapplock return negative.
        await using var holder = (SqlConnection)await harness.OpenAsync();
        await using (var acquire = holder.CreateCommand())
        {
            acquire.CommandText = "EXEC sp_getapplock @Resource = @src, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = -1;";
            acquire.Parameters.AddWithValue("@src", Source);
            await acquire.ExecuteNonQueryAsync();
        }

        var claimFailures = 0;

        var worker = new InboxWorker(harness.Connections, new InboxOptions
        {
            Source = Source,
            Dialect = harness.Dialect,
            PollInterval = TimeSpan.FromMilliseconds(200),
        }, logger: new CountingLogger(() => Interlocked.Increment(ref claimFailures)));

        using var stop = new CancellationTokenSource();
        var run = worker.RunAsync((_, _, _) => Task.CompletedTask, stop.Token);

        // Longer than the worker's 30-second sp_getapplock timeout: the message must still be
        // untouched, and the worker must still be running, having backed off rather than crashed.
        await Task.Delay(TimeSpan.FromSeconds(35), CancellationToken.None);

        var duringLock = await harness.ReadRowAsync(id);
        Assert.Equal("pending", duringLock.State);
        Assert.Equal(0, duringLock.Attempt);
        Assert.Null(duringLock.LastError);
        Assert.True(claimFailures > 0, "The claim must have reported at least one transient failure while the lock was held.");

        // Release the lock. The worker did not crash and it did not retry immediately into the
        // lock, so the very next poll must succeed.
        await using (var release = holder.CreateCommand())
        {
            release.CommandText = "EXEC sp_releaseapplock @Resource = @src, @LockOwner = 'Session';";
            release.Parameters.AddWithValue("@src", Source);
            await release.ExecuteNonQueryAsync();
        }

        var deadline = DateTimeOffset.UtcNow.AddSeconds(30);
        (string State, int Attempt, string? LastError) after;

        do
        {
            after = await harness.ReadRowAsync(id);

            if (after.State == "processed")
            {
                break;
            }

            await Task.Delay(TimeSpan.FromMilliseconds(200), CancellationToken.None);
        }
        while (DateTimeOffset.UtcNow < deadline);

        await stop.CancelAsync();
        await run;

        Assert.Equal("processed", after.State);
        Assert.Equal(0, after.Attempt);
        Assert.Null(after.LastError);
    }

    private sealed class CountingLogger(Action onClaimFailed) : Microsoft.Extensions.Logging.ILogger<InboxWorker>
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(Microsoft.Extensions.Logging.LogLevel logLevel) => true;

        public void Log<TState>(
            Microsoft.Extensions.Logging.LogLevel logLevel,
            Microsoft.Extensions.Logging.EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (eventId.Id == 1)
            {
                onClaimFailed();
            }
        }
    }
}
