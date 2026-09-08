using System.Collections.Concurrent;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>
/// Items 18 and 19 of <c>clients/contract-tests.md</c>, from finding F-087. The claim must hold
/// at most one message per aggregate in flight, and it must not serialize a whole source.
/// </summary>
public abstract class AggregateReservationTests : IAsyncLifetime
{
    /// <summary>The database under test.</summary>
    protected abstract IDatabaseHarness Harness { get; }

    /// <inheritdoc />
    public Task InitializeAsync() => Harness.ResetAsync();

    /// <inheritdoc />
    public Task DisposeAsync() => Task.CompletedTask;

    // Item 18.
    [Fact]
    public async Task OneAggregateNeverRunsTwoHandlersAtOneTime()
    {
        await Harness.SeedPendingAsync(source: "s", aggregateId: "agg-1", count: 4);

        var windows = new ConcurrentBag<(DateTimeOffset Start, DateTimeOffset End)>();

        Task Handler(InboxMessage m, System.Data.Common.DbTransaction tx, CancellationToken ct) =>
            RecordWindowAsync(windows, ct);

        await Harness.RunWorkersAsync(count: 2, handler: Handler, untilProcessed: 4);

        var ordered = windows.OrderBy(w => w.Start).ToList();

        Assert.Equal(4, ordered.Count);

        for (var i = 1; i < ordered.Count; i++)
        {
            Assert.True(
                ordered[i].Start >= ordered[i - 1].End,
                $"Handler {i} started at {ordered[i].Start:o}, before handler {i - 1} ended at {ordered[i - 1].End:o}.");
        }
    }

    // Item 18, the guard against over-serialization.
    [Fact]
    public async Task TwoAggregatesDoRunAtOneTime()
    {
        await Harness.SeedPendingAsync(source: "s", aggregateId: "agg-1", count: 2);
        await Harness.SeedPendingAsync(source: "s", aggregateId: "agg-2", count: 2);

        var inFlight = 0;
        var peak = 0;

        async Task Handler(InboxMessage m, System.Data.Common.DbTransaction tx, CancellationToken ct)
        {
            InterlockedMax(ref peak, Interlocked.Increment(ref inFlight));

            // SQL Server serializes the claim itself (sp_getapplock, per source), so the second
            // worker's claim can lag behind the first worker's handler by however long that lock
            // wait and round trip take. The handler stays open well past that lag, so the two
            // windows overlap regardless of dialect, and the assertion below is about overlap,
            // not about timing.
            await Task.Delay(TimeSpan.FromSeconds(2), ct);
            Interlocked.Decrement(ref inFlight);
        }

        await Harness.RunWorkersAsync(count: 2, handler: Handler, untilProcessed: 4);

        // The fix must not serialize the whole source.
        Assert.Equal(2, peak);
    }

    private static async Task RecordWindowAsync(ConcurrentBag<(DateTimeOffset Start, DateTimeOffset End)> windows, CancellationToken ct)
    {
        var start = DateTimeOffset.UtcNow;
        await Task.Delay(200, ct);
        windows.Add((start, DateTimeOffset.UtcNow));
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
}

/// <summary>The reservation contract, against a real PostgreSQL.</summary>
public sealed class PostgreSqlAggregateReservationTest(PostgreSqlHarness harness)
    : AggregateReservationTests, IClassFixture<PostgreSqlHarness>
{
    protected override IDatabaseHarness Harness => harness;
}

/// <summary>The reservation contract, against a real SQL Server.</summary>
public sealed class SqlServerAggregateReservationTest(SqlServerHarness harness)
    : AggregateReservationTests, IClassFixture<SqlServerHarness>
{
    protected override IDatabaseHarness Harness => harness;
}
