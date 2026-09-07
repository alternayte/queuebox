namespace QueueBox.Inbox;

/// <summary>What the library does with a message whose handler threw.</summary>
public readonly struct InboxFailureAction : IEquatable<InboxFailureAction>
{
    private InboxFailureAction(bool retry, TimeSpan delay)
    {
        ShouldRetry = retry;
        Delay = delay;
    }

    /// <summary>True to run the retry statement, false to run the dead-letter statement.</summary>
    public bool ShouldRetry { get; }

    /// <summary>How long the retry waits before the message can be claimed again.</summary>
    public TimeSpan Delay { get; }

    /// <summary>Return the message to pending after a delay.</summary>
    /// <param name="delay">The backoff. It must not be negative.</param>
    /// <returns>The action.</returns>
    public static InboxFailureAction Retry(TimeSpan delay)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(delay, TimeSpan.Zero, nameof(delay));
        return new InboxFailureAction(retry: true, delay);
    }

    /// <summary>Move the message to the dead letter and stop.</summary>
    /// <returns>The action.</returns>
    public static InboxFailureAction DeadLetter() => new(retry: false, TimeSpan.Zero);

    /// <inheritdoc />
    public bool Equals(InboxFailureAction other) => ShouldRetry == other.ShouldRetry && Delay == other.Delay;

    /// <inheritdoc />
    public override bool Equals(object? obj) => obj is InboxFailureAction other && Equals(other);

    /// <inheritdoc />
    public override int GetHashCode() => HashCode.Combine(ShouldRetry, Delay);

    /// <summary>Compare two actions.</summary>
    public static bool operator ==(InboxFailureAction left, InboxFailureAction right) => left.Equals(right);

    /// <summary>Compare two actions.</summary>
    public static bool operator !=(InboxFailureAction left, InboxFailureAction right) => !left.Equals(right);
}
