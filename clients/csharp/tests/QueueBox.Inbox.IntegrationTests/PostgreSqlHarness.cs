using System.Data.Common;
using Npgsql;
using Testcontainers.PostgreSql;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>A real PostgreSQL, in a container, with the QueueBox migrations applied.</summary>
public sealed class PostgreSqlHarness : IDatabaseHarness, IAsyncLifetime
{
    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder()
        .WithImage("postgres:16-alpine")
        .Build();

    private NpgsqlDataSource? _dataSource;

    public SqlDialect Dialect => SqlDialect.PostgreSql;

    public IInboxConnectionSource Connections => InboxConnections.From(DataSource);

    public string InsertApplicationRowSql => "INSERT INTO app_orders (key) VALUES (@key)";

    private NpgsqlDataSource DataSource => _dataSource ?? throw new InvalidOperationException("The harness did not start.");

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        _dataSource = NpgsqlDataSource.Create(_container.GetConnectionString());

        foreach (var script in MigrationScripts.For("postgresql"))
        {
            await ExecuteAsync(script);
        }

        await ExecuteAsync("CREATE TABLE app_orders (key TEXT PRIMARY KEY)");
    }

    public async Task DisposeAsync()
    {
        if (_dataSource is not null)
        {
            await _dataSource.DisposeAsync();
        }

        await _container.DisposeAsync();
    }

    public async Task<DbConnection> OpenAsync() => await DataSource.OpenConnectionAsync();

    public async Task<Guid> InsertPendingAsync(
        string source,
        string idempotencyKey,
        string payloadJson,
        string? aggregateId = null,
        string? eventType = null,
        string? correlationId = null,
        int attempt = 0)
    {
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = """
            INSERT INTO inbox (source, idempotency_key, aggregate_id, event_type, payload, state,
                               consumption, scheduled_at, attempt, correlation_id)
            VALUES (@source, @key, @aggregate, @type, @payload::jsonb, 'pending',
                    'pull', CURRENT_TIMESTAMP, @attempt, @correlation)
            RETURNING id;
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
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = "SELECT state, attempt, last_error FROM inbox WHERE id = @id";
        command.Parameters.AddWithValue("@id", id);

        await using var reader = await command.ExecuteReaderAsync();
        Assert.True(await reader.ReadAsync());

        return (reader.GetString(0), reader.GetInt32(1), reader.IsDBNull(2) ? null : reader.GetString(2));
    }

    public async Task<DateTimeOffset> ReadScheduledAtAsync(Guid id)
    {
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = "SELECT scheduled_at FROM inbox WHERE id = @id";
        command.Parameters.AddWithValue("@id", id);

        // Npgsql reads a timestamptz as a UTC DateTime, not as a DateTimeOffset.
        return new DateTimeOffset(DateTime.SpecifyKind((DateTime)(await command.ExecuteScalarAsync())!, DateTimeKind.Utc));
    }

    public Task StealClaimAsync(Guid id) => ExecuteAsync($"""
        UPDATE inbox SET state = 'processing', claim_token = gen_random_uuid(),
                         lease_expires_at = clock_timestamp() + INTERVAL '10 minutes'
        WHERE id = '{id}';
        """);

    public async Task<int> CountApplicationRowsAsync(string key)
    {
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = "SELECT COUNT(*) FROM app_orders WHERE key = @key";
        command.Parameters.AddWithValue("@key", key);

        return (int)(long)(await command.ExecuteScalarAsync())!;
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

        await ExecuteAsync("DROP TABLE IF EXISTS qb_messages");
        await ExecuteAsync("""
            CREATE TABLE qb_messages (
                message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                channel VARCHAR(255) NOT NULL,
                dedup_key VARCHAR(255) NOT NULL,
                aggregate_id VARCHAR(255),
                event_type VARCHAR(255),
                body JSONB NOT NULL,
                row_state VARCHAR(50) NOT NULL DEFAULT 'pending',
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_at TIMESTAMPTZ,
                correlation_id VARCHAR(128),
                lease_token UUID,
                claimed_at TIMESTAMPTZ,
                lease_expires_at TIMESTAMPTZ,
                consumption VARCHAR(4) NOT NULL DEFAULT 'push',
                scheduled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                tries INT NOT NULL DEFAULT 0,
                last_error TEXT
            )
            """);

        return schema;
    }

    public async Task<Guid> InsertMappedPendingAsync(InboxSchema schema, string source, string idempotencyKey, string payloadJson)
    {
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = $"""
            INSERT INTO {schema.Table} ({schema.Source}, {schema.IdempotencyKey}, {schema.Payload}, {schema.State}, {schema.Consumption})
            VALUES (@source, @key, @payload::jsonb, 'pending', 'pull')
            RETURNING {schema.Id};
            """;

        command.Parameters.AddWithValue("@source", source);
        command.Parameters.AddWithValue("@key", idempotencyKey);
        command.Parameters.AddWithValue("@payload", payloadJson);

        return (Guid)(await command.ExecuteScalarAsync())!;
    }

    public async Task<string> ReadMappedStateAsync(InboxSchema schema, Guid id)
    {
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = $"SELECT {schema.State} FROM {schema.Table} WHERE {schema.Id} = @id";
        command.Parameters.AddWithValue("@id", id);

        return (string)(await command.ExecuteScalarAsync())!;
    }

    private async Task ExecuteAsync(string sql)
    {
        await using var connection = await DataSource.OpenConnectionAsync();
        await using var command = connection.CreateCommand();

        command.CommandText = sql;
        await command.ExecuteNonQueryAsync();
    }
}
