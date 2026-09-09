namespace QueueBox.Inbox;

/// <summary>The settings of one worker.</summary>
public sealed class InboxOptions
{
    /// <summary>The source whose messages this worker takes. It is mandatory.</summary>
    public required string Source { get; init; }

    /// <summary>The largest number of messages one claim takes.</summary>
    public int BatchSize { get; init; } = 10;

    /// <summary>The lease duration in milliseconds. The renewal runs every third of it.</summary>
    public int LeaseMs { get; init; } = 30_000;

    /// <summary>
    /// The largest number of handlers that run at one time. It never passes the batch size.
    /// A null value means one, so a handler meets no sibling message unless the caller asks for
    /// parallelism. Set it above one only when the handler is safe against a concurrent sibling.
    /// </summary>
    public int? MaxConcurrency { get; init; }

    /// <summary>How long the worker waits after a claim that returned nothing.</summary>
    public TimeSpan PollInterval { get; init; } = TimeSpan.FromSeconds(1);

    /// <summary>
    /// How long a shutdown waits for the handlers that are already running. A handler that does
    /// not finish inside this wait is cancelled and its message is abandoned.
    /// </summary>
    public TimeSpan ShutdownGrace { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>The database dialect.</summary>
    public SqlDialect Dialect { get; init; } = SqlDialect.PostgreSql;

    /// <summary>The table and column names.</summary>
    public InboxSchema Schema { get; init; } = InboxSchema.Default;

    /// <summary>The failure policy. A null value means <see cref="DefaultRetryPolicy"/>.</summary>
    public IInboxRetryPolicy? RetryPolicy { get; init; }

    /// <summary>The concurrency the worker actually applies. The default is one.</summary>
    public int EffectiveConcurrency => Math.Min(MaxConcurrency ?? 1, BatchSize);

    /// <summary>The renewal interval, which is a third of the lease.</summary>
    public TimeSpan RenewalInterval => TimeSpan.FromMilliseconds(LeaseMs / 3.0);

    /// <summary>Reject a setting that cannot work.</summary>
    /// <exception cref="ArgumentException">The source or a schema name is not usable.</exception>
    /// <exception cref="ArgumentOutOfRangeException">A number is out of its range.</exception>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Source))
        {
            throw new ArgumentException("The source is mandatory.", nameof(Source));
        }

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(BatchSize, nameof(BatchSize));

        // The renewal runs every third of the lease, so a shorter lease has no interval at all.
        ArgumentOutOfRangeException.ThrowIfLessThan(LeaseMs, 3, nameof(LeaseMs));

        if (MaxConcurrency is { } concurrency)
        {
            ArgumentOutOfRangeException.ThrowIfNegativeOrZero(concurrency, nameof(MaxConcurrency));
        }

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(PollInterval.Ticks, nameof(PollInterval));
        ArgumentOutOfRangeException.ThrowIfNegative(ShutdownGrace.Ticks, nameof(ShutdownGrace));

        Schema.Validate();
    }
}
