using Microsoft.Extensions.Logging;
using Npgsql;
using QueueBox.Inbox;

// The worker stops on Ctrl+C. It stops claiming at once, and the handlers already running keep
// their grace.
using var stopping = new CancellationTokenSource();
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    stopping.Cancel();
};

using var loggerFactory = LoggerFactory.Create(builder => builder.AddConsole());

var connectionString = Environment.GetEnvironmentVariable("QUEUEBOX_DB")
    ?? "Host=localhost;Database=queuebox;Username=queuebox;Password=queuebox";

await using var dataSource = NpgsqlDataSource.Create(connectionString);

var worker = new InboxWorker(
    InboxConnections.From(dataSource),
    new InboxOptions { Source = "orders", BatchSize = 10, LeaseMs = 30_000 },
    loggerFactory.CreateLogger<InboxWorker>());

Console.WriteLine("The worker waits for the source 'orders'. Press Ctrl+C to stop it.");

await worker.RunAsync(async (message, transaction, cancellationToken) =>
{
    // Write every application change through this transaction. The library runs the completion
    // in the same transaction, so the two commit together or neither of them does.
    await using var command = transaction.CreateCommand();

    command.CommandText = "INSERT INTO orders (id, total) VALUES (@id, @total)";
    command
        .WithParameter("@id", message.Payload.GetProperty("id").GetString())
        .WithParameter("@total", message.Payload.GetProperty("total").GetInt32());

    await command.ExecuteNonQueryAsync(cancellationToken);

    Console.WriteLine($"Stored the order {message.IdempotencyKey}.");
}, stopping.Token);
