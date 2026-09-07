namespace QueueBox.Inbox.IntegrationTests;

/// <summary>The contract, against a real PostgreSQL.</summary>
public sealed class PostgreSqlContractTest(PostgreSqlHarness harness)
    : InboxWorkerContractTest, IClassFixture<PostgreSqlHarness>
{
    protected override IDatabaseHarness Harness => harness;
}
