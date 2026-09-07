namespace QueueBox.Inbox.Tests;

public sealed class InboxOptionsTest
{
    [Fact]
    public void TheDefaultsAreUsable()
    {
        var options = new InboxOptions { Source = "orders" };
        options.Validate();

        Assert.Equal(10, options.BatchSize);
        Assert.Equal(30_000, options.LeaseMs);
        Assert.Equal(SqlDialect.PostgreSql, options.Dialect);
        Assert.Same(InboxSchema.Default, options.Schema);
    }

    [Fact]
    public void TheConcurrencyNeverPassesTheBatch()
    {
        var options = new InboxOptions { Source = "orders", BatchSize = 4, MaxConcurrency = 16 };

        // Bounded memory: never claim or hold more than the configured batch.
        Assert.Equal(4, options.EffectiveConcurrency);
    }

    [Fact]
    public void TheConcurrencyDefaultsToTheBatch()
    {
        var options = new InboxOptions { Source = "orders", BatchSize = 4 };

        Assert.Equal(4, options.EffectiveConcurrency);
    }

    [Theory]
    [InlineData("")]
    [InlineData("  ")]
    [InlineData(null)]
    public void ASourceIsMandatory(string? source)
    {
        var options = new InboxOptions { Source = source! };

        Assert.Throws<ArgumentException>(options.Validate);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void TheBatchMustBePositive(int batch)
    {
        var options = new InboxOptions { Source = "orders", BatchSize = batch };

        Assert.Throws<ArgumentOutOfRangeException>(options.Validate);
    }

    [Fact]
    public void TheLeaseMustLeaveRoomForARenewal()
    {
        // The renewal runs every third of the lease, so a lease under three milliseconds has no
        // renewal interval at all.
        var options = new InboxOptions { Source = "orders", LeaseMs = 2 };

        Assert.Throws<ArgumentOutOfRangeException>(options.Validate);
    }

    [Fact]
    public void AnUnsafeSchemaNameIsRejected()
    {
        var options = new InboxOptions { Source = "orders", Schema = InboxSchema.Default with { Table = "a b" } };

        Assert.Throws<ArgumentException>(options.Validate);
    }
}
