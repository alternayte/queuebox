using System.Data.Common;

namespace QueueBox.Inbox;

/// <summary>
/// Handles one message.
/// <para>
/// The transaction is the point of the pull path. Write every application change through it. The
/// library runs the completion in the same transaction after the handler returns, so the writes
/// and the completion commit together or neither of them does.
/// </para>
/// <para>
/// Do not commit or roll back the transaction. The library owns both.
/// </para>
/// </summary>
/// <param name="message">The claimed message.</param>
/// <param name="transaction">The transaction that carries the completion.</param>
/// <param name="cancellationToken">
/// Cancels the handler. It fires when the lease is lost and when a shutdown runs out of grace.
/// </param>
/// <returns>A task that finishes when the application's work is written.</returns>
public delegate Task InboxHandler(InboxMessage message, DbTransaction transaction, CancellationToken cancellationToken);
