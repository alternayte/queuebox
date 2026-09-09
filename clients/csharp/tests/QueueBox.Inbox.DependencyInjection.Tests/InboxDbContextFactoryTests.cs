using Xunit;

namespace QueueBox.Inbox.DependencyInjection.Tests;

/// <summary>
/// Proves that <see cref="InboxDbContextFactory.CreateOn{TContext}"/> writes on the connection of
/// the message transaction, not on a connection of its own.
/// </summary>
public sealed class InboxDbContextFactoryTests
{
    // Edit that would make this fail: change CreateOn to build its own connection (for example
    // `new DbContextOptionsBuilder<TContext>().UseNpgsql(transaction.Connection!.ConnectionString)`
    // instead of `UseNpgsql(transaction.Connection)`), or drop the `UseTransaction` call. Either
    // change puts the application write on a second connection, so it survives the rollback and
    // the assertions below fail. This is the proof the finding exists for: a happy-path test alone
    // cannot tell a shared connection from two connections that both happen to succeed.
    [Fact]
    public async Task A_write_through_the_helper_rolls_back_when_the_handler_throws()
    {
        await using var harness = await PostgresHarness.CreateAsync();
        await harness.SeedPendingAsync(source: "orders", count: 1);

        var handler = new InboxHandler(async (message, transaction, token) =>
        {
            await using var context = InboxDbContextFactory.CreateOn<OrderContext>(
                transaction, options => new OrderContext(options));
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

    // Edit that would make this fail: make CreateOn skip `UseTransaction(transaction)` so Entity
    // Framework Core opens its write on a connection of its own. The completion still commits
    // through the message transaction, so the count below drops to zero while the state still
    // reads "processed", proving the two writes no longer share one outcome.
    [Fact]
    public async Task A_write_through_the_helper_commits_with_the_completion()
    {
        await using var harness = await PostgresHarness.CreateAsync();
        await harness.SeedPendingAsync(source: "orders", count: 1);

        var handler = new InboxHandler(async (message, transaction, token) =>
        {
            await using var context = InboxDbContextFactory.CreateOn<OrderContext>(
                transaction, options => new OrderContext(options));
            context.Orders.Add(new Order { Id = message.Id });
            await context.SaveChangesAsync(token);
        });

        await harness.RunWorkerOnceAsync(handler);

        Assert.Equal(1, await harness.CountOrdersAsync());
        Assert.Equal("processed", await harness.InboxStateAsync());
    }
}
