using System.Data.Common;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using QueueBox.Inbox;
using Xunit;

namespace QueueBox.Inbox.DependencyInjection.Tests;

/// A fake connection source. The tests assert the registration only, so a worker never runs and
/// this source never opens a connection.
public sealed class FakeConnectionSource : IInboxConnectionSource
{
    public ValueTask<DbConnection> OpenAsync(CancellationToken cancellationToken)
    {
        throw new NotSupportedException("The tests assert the registration only, and never open a connection.");
    }
}

public class ServiceCollectionExtensionsTests
{
    [Fact]
    public void Two_named_workers_both_run()
    {
        var services = new ServiceCollection();
        services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());

        services.AddQueueBoxInbox("orders", new InboxOptions { Source = "orders" }, (m, t, c) => Task.CompletedTask);
        services.AddQueueBoxInbox("payments", new InboxOptions { Source = "payments" }, (m, t, c) => Task.CompletedTask);

        var hosted = services.BuildServiceProvider().GetServices<IHostedService>().ToList();

        // Two registrations must give two hosted services. The AddHostedService trap is that a second
        // registration of the same implementation type is dropped, and one worker disappears in
        // silence.
        Assert.Equal(2, hosted.Count);
    }

    [Fact]
    public void A_duplicate_name_throws()
    {
        var services = new ServiceCollection();
        services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());
        services.AddQueueBoxInbox("orders", new InboxOptions { Source = "orders" }, (m, t, c) => Task.CompletedTask);

        var error = Assert.Throws<InvalidOperationException>(
            () => services.AddQueueBoxInbox("orders", new InboxOptions { Source = "other" }, (m, t, c) => Task.CompletedTask));

        // The message must name the duplicate, because an operator with twenty workers needs to know
        // which one.
        Assert.Contains("orders", error.Message);
    }

    [Fact]
    public void Each_worker_keeps_its_own_options()
    {
        var services = new ServiceCollection();
        services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());
        services.AddQueueBoxInbox("orders", new InboxOptions { Source = "orders", BatchSize = 3 }, (m, t, c) => Task.CompletedTask);
        services.AddQueueBoxInbox("payments", new InboxOptions { Source = "payments", BatchSize = 7 }, (m, t, c) => Task.CompletedTask);

        var hosted = services.BuildServiceProvider().GetServices<IHostedService>()
            .Cast<InboxWorkerHostedService>().ToList();

        // A shared options instance is the other silent failure: both workers would poll one source.
        // Assert the source and the batch size as one tuple per worker, so a bug that pairs the
        // wrong source with the wrong batch size fails this test.
        var actual = hosted.Select(h => (h.Options.Source, h.Options.BatchSize)).OrderBy(pair => pair.Source);
        Assert.Equal(new[] { ("orders", 3), ("payments", 7) }, actual);
    }

    [Fact]
    public void Each_worker_keeps_its_own_connection_source()
    {
        var services = new ServiceCollection();
        services.AddSingleton<IInboxConnectionSource>(new FakeConnectionSource());

        var ordersConnections = new FakeConnectionSource();
        var paymentsConnections = new FakeConnectionSource();

        services.AddQueueBoxInbox(
            "orders",
            new InboxOptions { Source = "orders" },
            (m, t, c) => Task.CompletedTask,
            connections: ordersConnections);
        services.AddQueueBoxInbox(
            "payments",
            new InboxOptions { Source = "payments" },
            (m, t, c) => Task.CompletedTask,
            connections: paymentsConnections);

        var hosted = services.BuildServiceProvider().GetServices<IHostedService>()
            .Cast<InboxWorkerHostedService>().ToList();

        // Each worker must keep the connection source passed to its own call, not the container's,
        // and not the other worker's, so a host with two inboxes in two databases works.
        var bySource = hosted.ToDictionary(h => h.Options.Source);
        Assert.Same(ordersConnections, bySource["orders"].Connections);
        Assert.Same(paymentsConnections, bySource["payments"].Connections);
    }
}
