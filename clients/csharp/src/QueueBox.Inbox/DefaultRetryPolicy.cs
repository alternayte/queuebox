namespace QueueBox.Inbox;

/// <summary>
/// Retries while <c>attempt &lt; maxAttempts</c>, with an exponential backoff and jitter, and
/// dead-letters after that. The row starts at attempt zero, so the ceiling allows
/// <c>maxAttempts + 1</c> deliveries in total.
/// </summary>
public sealed class DefaultRetryPolicy : IInboxRetryPolicy
{
    private readonly int _maxAttempts;
    private readonly TimeSpan _baseDelay;
    private readonly TimeSpan _maxDelay;
    private readonly double _jitter;

    /// <summary>Build the policy.</summary>
    /// <param name="maxAttempts">The attempt ceiling. It must be positive.</param>
    /// <param name="baseDelay">The delay after the first failure. It must not be negative.</param>
    /// <param name="maxDelay">The ceiling of the backoff. It must not be below the base delay.</param>
    /// <param name="jitter">The fraction of the delay that varies, from zero to one.</param>
    public DefaultRetryPolicy(int maxAttempts = 5, TimeSpan? baseDelay = null, TimeSpan? maxDelay = null, double jitter = 0.2)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maxAttempts);
        ArgumentOutOfRangeException.ThrowIfNegative(jitter);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(jitter, 1.0);

        _maxAttempts = maxAttempts;
        _baseDelay = baseDelay ?? TimeSpan.FromSeconds(1);
        _maxDelay = maxDelay ?? TimeSpan.FromMinutes(5);
        _jitter = jitter;

        ArgumentOutOfRangeException.ThrowIfNegative(_baseDelay.Ticks, nameof(baseDelay));
        ArgumentOutOfRangeException.ThrowIfLessThan(_maxDelay, _baseDelay, nameof(maxDelay));
    }

    /// <inheritdoc />
    public InboxFailureAction Decide(InboxMessage message, Exception failure)
    {
        ArgumentNullException.ThrowIfNull(message);

        if (message.Attempt >= _maxAttempts)
        {
            return InboxFailureAction.DeadLetter();
        }

        return InboxFailureAction.Retry(Backoff(message.Attempt));
    }

    private TimeSpan Backoff(int attempt)
    {
        // A large attempt must not overflow the shift, so the exponent stops where the ceiling
        // is certainly reached.
        var exponent = Math.Min(attempt, 30);
        var scaled = _baseDelay.Ticks * Math.Pow(2, exponent);
        var ticks = scaled >= _maxDelay.Ticks ? _maxDelay.Ticks : (long)scaled;

        if (_jitter <= 0)
        {
            return TimeSpan.FromTicks(ticks);
        }

        // A full band around the delay: one worker's backoff never lines up with another's.
        var band = ticks * _jitter;
        var offset = (Random.Shared.NextDouble() * 2 - 1) * band;

        return TimeSpan.FromTicks(Math.Max(0, ticks + (long)offset));
    }
}
