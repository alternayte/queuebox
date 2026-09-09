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
        Assert.Equal(new[] { "orders", "payments" }, hosted.Select(h => h.Options.Source).OrderBy(s => s));
        Assert.Equal(new[] { 3, 7 }, hosted.Select(h => h.Options.BatchSize).OrderBy(n => n));
    }
}
