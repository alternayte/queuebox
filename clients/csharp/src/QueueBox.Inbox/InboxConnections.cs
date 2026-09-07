using System.Data.Common;

namespace QueueBox.Inbox;

/// <summary>Builds a connection source from what a driver offers.</summary>
public static class InboxConnections
{
    /// <summary>
    /// Use a <see cref="DbDataSource"/>. This is the Npgsql path, and it is the one the README
    /// example uses.
    /// </summary>
    /// <param name="dataSource">The data source. The caller keeps ownership of it.</param>
    /// <returns>The connection source.</returns>
    public static IInboxConnectionSource From(DbDataSource dataSource)
    {
        ArgumentNullException.ThrowIfNull(dataSource);
        return new DataSourceConnections(dataSource);
    }

    /// <summary>
    /// Use a provider factory and a connection string. This is the Microsoft.Data.SqlClient path,
    /// because that driver ships no <see cref="DbDataSource"/>.
    /// </summary>
    /// <param name="factory">The provider factory, for example <c>SqlClientFactory.Instance</c>.</param>
    /// <param name="connectionString">The connection string.</param>
    /// <returns>The connection source.</returns>
    public static IInboxConnectionSource From(DbProviderFactory factory, string connectionString)
    {
        ArgumentNullException.ThrowIfNull(factory);
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionString);
        return new FactoryConnections(factory, connectionString);
    }

    /// <summary>Use a delegate of the application's own.</summary>
    /// <param name="open">Opens a connection.</param>
    /// <returns>The connection source.</returns>
    public static IInboxConnectionSource From(Func<CancellationToken, ValueTask<DbConnection>> open)
    {
        ArgumentNullException.ThrowIfNull(open);
        return new DelegateConnections(open);
    }

    private sealed class DataSourceConnections(DbDataSource dataSource) : IInboxConnectionSource
    {
        public async ValueTask<DbConnection> OpenAsync(CancellationToken cancellationToken) =>
            await dataSource.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
    }

    private sealed class FactoryConnections(DbProviderFactory factory, string connectionString) : IInboxConnectionSource
    {
        public async ValueTask<DbConnection> OpenAsync(CancellationToken cancellationToken)
        {
            var connection = factory.CreateConnection()
                ?? throw new InvalidOperationException("The provider factory returned no connection.");

            connection.ConnectionString = connectionString;

            try
            {
                await connection.OpenAsync(cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                await connection.DisposeAsync().ConfigureAwait(false);
                throw;
            }

            return connection;
        }
    }

    private sealed class DelegateConnections(Func<CancellationToken, ValueTask<DbConnection>> open) : IInboxConnectionSource
    {
        public ValueTask<DbConnection> OpenAsync(CancellationToken cancellationToken) => open(cancellationToken);
    }
}
