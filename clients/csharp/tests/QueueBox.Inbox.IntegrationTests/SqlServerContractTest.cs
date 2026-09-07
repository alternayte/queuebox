namespace QueueBox.Inbox.IntegrationTests;

/// <summary>The contract, against a real SQL Server.</summary>
public sealed class SqlServerContractTest(SqlServerHarness harness)
    : InboxWorkerContractTest, IClassFixture<SqlServerHarness>
{
    protected override IDatabaseHarness Harness => harness;
}
