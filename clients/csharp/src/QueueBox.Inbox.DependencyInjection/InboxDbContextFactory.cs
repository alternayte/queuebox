using System.Data.Common;
using Microsoft.EntityFrameworkCore;

namespace QueueBox.Inbox.DependencyInjection;

/// <summary>Enlists an Entity Framework Core context on the transaction of the message.</summary>
/// <remarks>
/// The caller builds the context and picks the provider, for example
/// <c>c => new OrderContext(new DbContextOptionsBuilder&lt;OrderContext&gt;().UseNpgsql(c).Options)</c>
/// on PostgreSQL, or <c>.UseSqlServer(c)</c> on SQL Server. This method enlists whatever context
/// the caller builds on the transaction of the message, which is the one step a handler must not
/// skip and the one this package owns on the caller's behalf.
/// </remarks>
public static class InboxDbContextFactory
{
    /// <summary>
    /// Build a context that writes inside the transaction of the message.
    /// <para>
    /// Entity Framework Core opens its own connection by default, and a write on a second
    /// connection commits on its own. The inbox guarantee is that the application write and the
    /// completion commit together, so the context must take the connection AND the transaction of
    /// the message. A handler that opens its own transaction, or its own context with no call to
    /// this method, writes on a second connection. That write commits on its own, so a later
    /// failure leaves the application row written and the inbox row unprocessed, and QueueBox
    /// delivers the message again.
    /// </para>
    /// </summary>
    /// <typeparam name="TContext">The context type.</typeparam>
    /// <param name="transaction">The transaction of the message, from the <c>InboxHandler</c>.</param>
    /// <param name="build">Builds the context on the given connection. The caller picks the provider here.</param>
    /// <returns>A context that shares the connection and the transaction of the message.</returns>
    /// <exception cref="ArgumentException">The transaction carries no connection.</exception>
    public static TContext CreateOn<TContext>(
        DbTransaction transaction,
        Func<DbConnection, TContext> build)
        where TContext : DbContext
    {
        ArgumentNullException.ThrowIfNull(transaction);
        ArgumentNullException.ThrowIfNull(build);

        var connection = transaction.Connection
            ?? throw new ArgumentException(
                "The transaction carries no connection. It must come from the same connection " +
                "the message transaction opened, so it must still be open.",
                nameof(transaction));

        var context = build(connection);
        context.Database.UseTransaction(transaction);

        return context;
    }
}
