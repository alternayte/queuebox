using System.Data.Common;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>Counts the connections a worker opens, so a test can count its claims.</summary>
internal sealed class CountingConnections(IInboxConnectionSource inner) : IInboxConnectionSource
{
    private int _opened;

    internal int Opened => Volatile.Read(ref _opened);

    public ValueTask<DbConnection> OpenAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref _opened);
        return inner.OpenAsync(cancellationToken);
    }
}
