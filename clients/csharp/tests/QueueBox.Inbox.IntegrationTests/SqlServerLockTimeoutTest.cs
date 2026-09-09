using Microsoft.Data.SqlClient;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>
/// Regression test for finding F-087. The canonical claim (examples/pull/sql/sqlserver/claim.sql)
/// sets <c>sp_getapplock</c>'s own <c>@LockTimeout</c>. That value must sit below every driver
/// default, so the server always raises <c>Msg 51000</c> before a driver aborts the call on its
/// own. A client-side abort does NOT roll back the server-side transaction: the lock uses
/// <c>@LockOwner = 'Transaction'</c>, so an abandoned transaction keeps the per-source claim lock
/// until the connection resets, and every later claim on that source then waits its own full
/// timeout and aborts the same way.
///
/// <c>Microsoft.Data.SqlClient</c> defaults its command timeout to 30 seconds, the same value the
/// pre-fix lock timeout used: a tie must be treated as a loss for the lock. This test uses that
/// same 30 second default deliberately, with no explicit <c>CommandTimeout</c> set, to prove the
/// tie is now won: post-fix the lock timeout (10 seconds) is comfortably below the 30 second
/// command timeout, so <c>Msg 51000</c> must arrive well before the command times out.
/// </summary>
public sealed class SqlServerLockTimeoutTest(SqlServerHarness harness) : IClassFixture<SqlServerHarness>, IAsyncLifetime
{
    private const string Source = "lock-timeout-source";

    public Task InitializeAsync() => harness.ResetAsync();

    public Task DisposeAsync() => Task.CompletedTask;

    [Fact]
    public async Task TheServerLockTimeoutFiresBeforeTheDriverCommandTimeout()
    {
        // Hold the per-source applock from outside the worker, for a session, with no timeout.
        await using var holder = (SqlConnection)await harness.OpenAsync();
        await using (var acquire = holder.CreateCommand())
        {
            acquire.CommandText = "EXEC sp_getapplock @Resource = @src, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = -1;";
            acquire.Parameters.AddWithValue("@src", Source);
            await acquire.ExecuteNonQueryAsync();
        }

        var sql = InboxSql.For(SqlDialect.SqlServer, InboxSchema.Default);

        await using var connection = (SqlConnection)await harness.OpenAsync();
        await using var claim = connection.CreateCommand();
        claim.CommandText = sql.Claim;
        // Deliberately left at the Microsoft.Data.SqlClient default of 30 seconds: the value the
        // defect made a losing tie against the old 30 second lock timeout.
        claim.Parameters.AddWithValue("@source", Source);
        claim.Parameters.AddWithValue("@batch", 10);
        claim.Parameters.AddWithValue("@lease_ms", 30000);
        claim.Parameters.AddWithValue("@cand_limit", 100);

        var started = DateTimeOffset.UtcNow;
        SqlException? failure = null;

        try
        {
            await claim.ExecuteNonQueryAsync();
        }
        catch (SqlException ex)
        {
            failure = ex;
        }

        var elapsed = DateTimeOffset.UtcNow - started;

        await using (var release = holder.CreateCommand())
        {
            release.CommandText = "EXEC sp_releaseapplock @Resource = @src, @LockOwner = 'Session';";
            release.Parameters.AddWithValue("@src", Source);
            await release.ExecuteNonQueryAsync();
        }

        Assert.NotNull(failure);
        Assert.False(
            failure!.Message.Contains("Timeout", StringComparison.OrdinalIgnoreCase) && failure.Number != 51000,
            $"the claim failed with a driver timeout instead of Msg 51000, after {elapsed}: {failure.Message}");
        Assert.Equal(51000, failure.Number);
        Assert.True(elapsed < TimeSpan.FromSeconds(20),
            $"the claim took {elapsed} to fail, too close to the 30 second command timeout for the 10 second server-side lock timeout to have fired first");
    }
}
