using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Xunit;

namespace QueueBox.Inbox.DependencyInjection.Tests;

/// <summary>
/// Tests <see cref="InboxDbContextFactory.CreateOn{TContext}"/> against its own contract, the
/// enlistment, rather than against an end-to-end consequence of it. No container and no dialect
/// dependency: a Sqlite in-memory connection proves the same enlistment a PostgreSQL or SQL Server
/// connection would, because <c>UseTransaction</c> is provider neutral.
/// </summary>
public sealed class InboxDbContextFactoryUnitTests
{
    // Edit that would make this fail: remove `context.Database.UseTransaction(transaction)` from
    // CreateOn. The context then enlists no transaction at all, so `CurrentTransaction` is null and
    // the `!` below throws, failing the assertion. This is the direct proof the two end-to-end
    // tests in InboxDbContextFactoryTests cannot give on PostgreSQL: it catches a dropped
    // enlistment in milliseconds, with no dependence on how a particular ADO.NET driver reacts to
    // an un-enlisted write on an already-transacted connection.
    [Fact]
    public void CreateOn_enlists_the_context_on_the_given_transaction()
    {
        using var connection = new SqliteConnection("DataSource=:memory:");
        connection.Open();
        using var transaction = connection.BeginTransaction();

        using var context = InboxDbContextFactory.CreateOn(
            transaction,
            c => new OrderContext(new DbContextOptionsBuilder<OrderContext>().UseSqlite(c).Options));

        Assert.Same(transaction, context.Database.CurrentTransaction!.GetDbTransaction());
    }
}
