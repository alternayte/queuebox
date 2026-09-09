using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace QueueBox.Inbox.DependencyInjection;

/// <summary>
/// Runs one named worker for the whole life of the host. One instance per registration, so two
/// registrations give two workers rather than one.
/// </summary>
internal sealed class InboxWorkerHostedService : BackgroundService
{
    private readonly IInboxConnectionSource _connections;
    private readonly InboxHandler _handler;
    private readonly ILogger<InboxWorker>? _logger;
    private readonly TimeProvider? _timeProvider;

    /// <summary>The options of this registration only. No other worker shares this instance.</summary>
    internal InboxOptions Options { get; }

    /// <summary>The connection source of this registration only.</summary>
    internal IInboxConnectionSource Connections => _connections;

    internal InboxWorkerHostedService(
        IInboxConnectionSource connections,
        InboxOptions options,
        InboxHandler handler,
        ILogger<InboxWorker>? logger = null,
        TimeProvider? timeProvider = null)
    {
        _connections = connections;
        Options = options;
        _handler = handler;
        _logger = logger;
        _timeProvider = timeProvider;
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var worker = new InboxWorker(_connections, Options, _logger, _timeProvider);
        await worker.RunAsync(_handler, stoppingToken).ConfigureAwait(false);
    }
}
