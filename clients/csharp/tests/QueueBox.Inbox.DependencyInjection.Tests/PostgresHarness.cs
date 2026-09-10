using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using QueueBox.Inbox.IntegrationTests;

namespace QueueBox.Inbox.DependencyInjection.Tests;

/// <summary>
/// A real PostgreSQL, in a container, wired through <see cref="AddQueueBoxInbox"/>.
/// <para>
/// This wraps <see cref="PostgreSqlHarness"/> from the integration test project rather than
/// starting a second container, and it adds one table of its own for the rollback proof.
/// </para>
/// </summary>
public sealed class PostgresHarness : IAsyncDisposable
{
    private const string Source = "orders";

    private readonly PostgreSqlHarness _harness = new();
    private readonly List<Guid> _seededIds = [];

    private PostgresHarness()
    {
    }

    /// <summary>Start the container, apply the QueueBox migrations, and create the application table.</summary>
    public static async Task<PostgresHarness> CreateAsync()
    {
        var harness = new PostgresHarness();
        await harness._harness.InitializeAsync();

        await using var connection = await harness._harness.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = """CREATE TABLE ef_orders ("Id" uuid PRIMARY KEY)""";
        await command.ExecuteNonQueryAsync();

        return harness;
    }

    /// <summary>Insert pending inbox rows for <paramref name="source"/>. The worker picks the newest one.</summary>
    public async Task SeedPendingAsync(string source, int count)
    {
        for (var i = 0; i < count; i++)
        {
            var id = await _harness.InsertPendingAsync(source, $"key-{Guid.NewGuid()}", "{}");
            _seededIds.Add(id);
        }
    }

    /// <summary>
    /// Run one worker registration long enough to claim and settle the seeded message, then stop it.
    /// </summary>
    public async Task RunWorkerOnceAsync(InboxHandler handler)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IInboxConnectionSource>(_harness.Connections);
        services.AddQueueBoxInbox(
            "test",
            new InboxOptions
            {
                Source = Source,
                PollInterval = TimeSpan.FromMilliseconds(50),
            },
            handler);

        await using var provider = services.BuildServiceProvider();
        var hosted = provider.GetServices<IHostedService>().Single();

        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        await hosted.StartAsync(stop.Token);

        var id = _seededIds[^1];
        await WaitUntilAsync(id, state => state != "pending", stop.Token);
        await WaitUntilAsync(id, state => state != "processing", stop.Token);

        await hosted.StopAsync(CancellationToken.None);
    }

    /// <summary>The number of rows the test handler wrote through the helper.</summary>
    public async Task<int> CountOrdersAsync()
    {
        await using var connection = await _harness.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM ef_orders";
        return (int)(long)(await command.ExecuteScalarAsync())!;
    }

    /// <summary>The inbox state of the last seeded message.</summary>
    public async Task<string> InboxStateAsync()
    {
        var (state, _, _) = await _harness.ReadRowAsync(_seededIds[^1]);
        return state;
    }

    /// <inheritdoc />
    public async ValueTask DisposeAsync() => await _harness.DisposeAsync();

    private async Task WaitUntilAsync(Guid id, Func<string, bool> condition, CancellationToken cancellationToken)
    {
        while (!condition((await _harness.ReadRowAsync(id)).State))
        {
            cancellationToken.ThrowIfCancellationRequested();
            await Task.Delay(25, cancellationToken);
        }
    }
}
