using Microsoft.EntityFrameworkCore;
using Xunit;

namespace QueueBox.Inbox.DependencyInjection.Tests;

/// <summary>
/// End-to-end regression tests of the worker's rollback and completion path, run through
/// <see cref="InboxDbContextFactory.CreateOn{TContext}"/>. On PostgreSQL, Npgsql binds one
/// transaction per physical connection, so a context that never enlisted throws before it writes a
/// row rather than committing on its own. That means dropping the helper's
/// <c>UseTransaction</c> call fails these two tests closed, not open, and neither test tells that
/// failure apart from a genuine second-connection bug on its own. The real discriminator for the
/// helper's contract is <see cref="InboxDbContextFactoryUnitTests"/>, which asserts the enlistment
/// directly. See its remarks for what each test here actually defends.
/// </summary>
public sealed class InboxDbContextFactoryTests
{
    // The sabotage this test genuinely defends against: `build` receiving a second, independently
    // opened connection built from the same connection string, while the handler still throws
    // after it writes. That second connection commits its insert on its own, outside the message
    // transaction, so the row survives the rollback and `CountOrdersAsync` reads 1 instead of 0.
    // Dropping only `context.Database.UseTransaction(transaction)` does NOT fail this test on
    // PostgreSQL: Npgsql refuses a second command on a connection that already has another live
    // transaction, so the write throws before it reaches the database, and the assertions below
    // hold for that reason instead. This test still guards a real worker contract, that a throwing
    // handler leaves no application row and leaves the inbox row pending, which is worth a
    // regression test regardless.
    [Fact]
    public async Task The_worker_rolls_back_the_application_write_when_the_handler_throws()
    {
        await using var harness = await PostgresHarness.CreateAsync();
        await harness.SeedPendingAsync(source: "orders", count: 1);

        var handler = new InboxHandler(async (message, transaction, token) =>
        {
            await using var context = InboxDbContextFactory.CreateOn(
                transaction,
                connection => new OrderContext(new DbContextOptionsBuilder<OrderContext>().UseNpgsql(connection).Options));
            context.Orders.Add(new Order { Id = message.Id });
            await context.SaveChangesAsync(token);

            throw new InvalidOperationException("the handler failed after it wrote");
        });

        await harness.RunWorkerOnceAsync(handler);

        // The application write and the completion share one transaction. A handler that throws must
        // leave neither behind.
        Assert.Equal(0, await harness.CountOrdersAsync());
        Assert.Equal("pending", await harness.InboxStateAsync());
    }

    // This is the real discriminator on PostgreSQL. The sabotage that fails it: `build` receiving
    // a second, independently opened connection built from the same connection string instead of
    // the transaction's own connection. That connection commits the application row on its own,
    // separately from the inbox completion, so `CountOrdersAsync` reads 0 while the state still
    // reads "processed" — proof the two writes stopped sharing one outcome. Dropping only
    // `UseTransaction` produces the same visible failure here, because the second call the worker
    // makes on the shared connection (the completion) then runs with no transaction of its own
    // bound to the context, and the mismatch surfaces as the count and the state disagreeing.
    [Fact]
    public async Task A_write_through_the_helper_commits_with_the_completion()
    {
        await using var harness = await PostgresHarness.CreateAsync();
        await harness.SeedPendingAsync(source: "orders", count: 1);

        var handler = new InboxHandler(async (message, transaction, token) =>
        {
            await using var context = InboxDbContextFactory.CreateOn(
                transaction,
                connection => new OrderContext(new DbContextOptionsBuilder<OrderContext>().UseNpgsql(connection).Options));
            context.Orders.Add(new Order { Id = message.Id });
            await context.SaveChangesAsync(token);
        });

        await harness.RunWorkerOnceAsync(handler);

        Assert.Equal(1, await harness.CountOrdersAsync());
        Assert.Equal("processed", await harness.InboxStateAsync());
    }
}
