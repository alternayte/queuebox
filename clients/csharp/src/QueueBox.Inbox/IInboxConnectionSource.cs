using System.Data.Common;

namespace QueueBox.Inbox;

/// <summary>
/// Opens a connection for the worker.
/// <para>
/// The worker needs more than one connection at a time. The handler's transaction holds one, and
/// the lease renewal holds another, because a renewal that ran inside the handler's transaction
/// would commit with it and would therefore renew nothing.
/// </para>
/// <para>
/// The interface exists because the two reference drivers do not agree. Npgsql ships a
/// <see cref="DbDataSource"/>; Microsoft.Data.SqlClient does not. See
/// <see cref="InboxConnections"/> for the adapters.
/// </para>
/// </summary>
public interface IInboxConnectionSource
{
    /// <summary>Open a connection that the caller owns and disposes.</summary>
    /// <param name="cancellationToken">Cancels the open.</param>
    /// <returns>An open connection.</returns>
    ValueTask<DbConnection> OpenAsync(CancellationToken cancellationToken);
}
