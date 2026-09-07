using System.Data.Common;
using Microsoft.Data.SqlClient;
using Testcontainers.MsSql;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>A real SQL Server, in a container, with the QueueBox migrations applied.</summary>
public sealed class SqlServerHarness : IDatabaseHarness, IAsyncLifetime
{
    private readonly MsSqlContainer _container = new MsSqlBuilder().Build();

    private string? _connectionString;

    public SqlDialect Dialect => SqlDialect.SqlServer;

    public IInboxConnectionSource Connections => InboxConnections.From(SqlClientFactory.Instance, ConnectionString);

    public string InsertApplicationRowSql => "INSERT INTO app_orders ([key]) VALUES (@key)";

    private string ConnectionString => _connectionString ?? throw new InvalidOperationException("The harness did not start.");

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        _connectionString = _container.GetConnectionString();

        foreach (var script in MigrationScripts.For("sqlserver"))
        {
            await ExecuteAsync(script);
        }

        await ExecuteAsync("CREATE TABLE app_orders ([key] NVARCHAR(255) PRIMARY KEY)");
    }

    public async Task DisposeAsync() => await _container.DisposeAsync();

    public async Task<DbConnection> OpenAsync()
    {
        var connection = new SqlConnection(ConnectionString);
        await connection.OpenAsync();
        return connection;
    }

    public async Task<Guid> InsertPendingAsync(
        string source,
        string idempotencyKey,
        string payloadJson,
        string? aggregateId = null,
        string? eventType = null,
        string? correlationId = null,
        int attempt = 0)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = """
            INSERT INTO inbox (source, idempotency_key, aggregate_id, event_type, payload, state,
                               consumption, scheduled_at, attempt, correlation_id)
            OUTPUT INSERTED.id
            VALUES (@source, @key, @aggregate, @type, @payload, 'pending',
                    'pull', SYSUTCDATETIME(), @attempt, @correlation);
            """;

        command.Parameters.AddWithValue("@source", source);
        command.Parameters.AddWithValue("@key", idempotencyKey);
        command.Parameters.AddWithValue("@aggregate", (object?)aggregateId ?? DBNull.Value);
        command.Parameters.AddWithValue("@type", (object?)eventType ?? DBNull.Value);
        command.Parameters.AddWithValue("@payload", payloadJson);
        command.Parameters.AddWithValue("@attempt", attempt);
        command.Parameters.AddWithValue("@correlation", (object?)correlationId ?? DBNull.Value);

        return (Guid)(await command.ExecuteScalarAsync())!;
    }

    public async Task<(string State, int Attempt, string? LastError)> ReadRowAsync(Guid id)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = "SELECT state, attempt, last_error FROM inbox WHERE id = @id";
        command.Parameters.AddWithValue("@id", id);

        await using var reader = await command.ExecuteReaderAsync();
        Assert.True(await reader.ReadAsync());

        return (reader.GetString(0), reader.GetInt32(1), reader.IsDBNull(2) ? null : reader.GetString(2));
    }

    public async Task<DateTimeOffset> ReadScheduledAtAsync(Guid id)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = "SELECT scheduled_at FROM inbox WHERE id = @id";
        command.Parameters.AddWithValue("@id", id);

        // The column is DATETIME2 and it holds UTC, so the offset is zero.
        return new DateTimeOffset((DateTime)(await command.ExecuteScalarAsync())!, TimeSpan.Zero);
    }

    public Task StealClaimAsync(Guid id) => ExecuteAsync($"""
        UPDATE inbox SET state = 'processing', claim_token = NEWID(),
                         lease_expires_at = DATEADD(minute, 10, SYSUTCDATETIME())
        WHERE id = '{id}';
        """);

    public async Task<int> CountApplicationRowsAsync(string key)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = "SELECT COUNT(*) FROM app_orders WHERE [key] = @key";
        command.Parameters.AddWithValue("@key", key);

        return (int)(await command.ExecuteScalarAsync())!;
    }

    public async Task ResetAsync()
    {
        await ExecuteAsync("DELETE FROM inbox");
        await ExecuteAsync("DELETE FROM app_orders");
    }

    public async Task<InboxSchema> CreateMappedSchemaAsync()
    {
        var schema = new InboxSchema
        {
            Table = "qb_messages",
            Id = "message_id",
            State = "row_state",
            Source = "channel",
            Payload = "body",
            IdempotencyKey = "dedup_key",
            ClaimToken = "lease_token",
            Attempt = "tries",
        };

        await ExecuteAsync("IF OBJECT_ID('qb_messages', 'U') IS NOT NULL DROP TABLE qb_messages");
        await ExecuteAsync("""
            CREATE TABLE qb_messages (
                message_id UNIQUEIDENTIFIER PRIMARY KEY DEFAULT NEWID(),
                channel NVARCHAR(255) NOT NULL,
                dedup_key NVARCHAR(255) NOT NULL,
                aggregate_id NVARCHAR(255),
                event_type NVARCHAR(255),
                body NVARCHAR(MAX) NOT NULL,
                row_state NVARCHAR(50) NOT NULL DEFAULT 'pending',
                created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                processed_at DATETIME2,
                correlation_id NVARCHAR(128),
                lease_token UNIQUEIDENTIFIER,
                claimed_at DATETIME2,
                lease_expires_at DATETIME2,
                consumption NVARCHAR(4) NOT NULL DEFAULT 'push',
                scheduled_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                tries INT NOT NULL DEFAULT 0,
                last_error NVARCHAR(MAX)
            )
            """);

        return schema;
    }

    public async Task<Guid> InsertMappedPendingAsync(InboxSchema schema, string source, string idempotencyKey, string payloadJson)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = $"""
            INSERT INTO {schema.Table} ({schema.Source}, {schema.IdempotencyKey}, {schema.Payload}, {schema.State}, {schema.Consumption})
            OUTPUT INSERTED.{schema.Id}
            VALUES (@source, @key, @payload, 'pending', 'pull');
            """;

        command.Parameters.AddWithValue("@source", source);
        command.Parameters.AddWithValue("@key", idempotencyKey);
        command.Parameters.AddWithValue("@payload", payloadJson);

        return (Guid)(await command.ExecuteScalarAsync())!;
    }

    public async Task<string> ReadMappedStateAsync(InboxSchema schema, Guid id)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = $"SELECT {schema.State} FROM {schema.Table} WHERE {schema.Id} = @id";
        command.Parameters.AddWithValue("@id", id);

        return (string)(await command.ExecuteScalarAsync())!;
    }

    private async Task ExecuteAsync(string sql)
    {
        await using var connection = (SqlConnection)await OpenAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = sql;
        await command.ExecuteNonQueryAsync();
    }
}
