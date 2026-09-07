namespace QueueBox.Inbox.Tests;

public sealed class DefaultRetryPolicyTest
{
    private static readonly Exception Failure = new InvalidOperationException("no");

    [Fact]
    public void ItRetriesWhileTheAttemptIsBelowTheCeiling()
    {
        var policy = new DefaultRetryPolicy(maxAttempts: 3, baseDelay: TimeSpan.FromSeconds(1), maxDelay: TimeSpan.FromMinutes(1), jitter: 0);

        Assert.True(policy.Decide(Message(attempt: 0), Failure).ShouldRetry);
        Assert.True(policy.Decide(Message(attempt: 1), Failure).ShouldRetry);
        Assert.True(policy.Decide(Message(attempt: 2), Failure).ShouldRetry);
    }

    [Fact]
    public void ItDeadLettersAtTheCeiling()
    {
        var policy = new DefaultRetryPolicy(maxAttempts: 3, baseDelay: TimeSpan.FromSeconds(1), maxDelay: TimeSpan.FromMinutes(1), jitter: 0);

        // The rule is `attempt < maxAttempts`, so a row that already carries three deliveries dies.
        Assert.False(policy.Decide(Message(attempt: 3), Failure).ShouldRetry);
        Assert.Equal(InboxFailureAction.DeadLetter(), policy.Decide(Message(attempt: 9), Failure));
    }

    [Fact]
    public void TheBackoffDoublesAndThenStops()
    {
        var policy = new DefaultRetryPolicy(maxAttempts: 10, baseDelay: TimeSpan.FromSeconds(1), maxDelay: TimeSpan.FromSeconds(4), jitter: 0);

        Assert.Equal(TimeSpan.FromSeconds(1), policy.Decide(Message(attempt: 0), Failure).Delay);
        Assert.Equal(TimeSpan.FromSeconds(2), policy.Decide(Message(attempt: 1), Failure).Delay);
        Assert.Equal(TimeSpan.FromSeconds(4), policy.Decide(Message(attempt: 2), Failure).Delay);
        Assert.Equal(TimeSpan.FromSeconds(4), policy.Decide(Message(attempt: 3), Failure).Delay);
        Assert.Equal(TimeSpan.FromSeconds(4), policy.Decide(Message(attempt: 8), Failure).Delay);
    }

    [Fact]
    public void TheJitterStaysInsideItsBand()
    {
        var policy = new DefaultRetryPolicy(maxAttempts: 10, baseDelay: TimeSpan.FromSeconds(10), maxDelay: TimeSpan.FromMinutes(1), jitter: 0.2);

        for (var i = 0; i < 200; i++)
        {
            var delay = policy.Decide(Message(attempt: 0), Failure).Delay;

            Assert.InRange(delay, TimeSpan.FromSeconds(8), TimeSpan.FromSeconds(12));
        }
    }

    [Fact]
    public void ALargeAttemptDoesNotOverflowTheBackoff()
    {
        var policy = new DefaultRetryPolicy(maxAttempts: int.MaxValue, baseDelay: TimeSpan.FromSeconds(1), maxDelay: TimeSpan.FromMinutes(5), jitter: 0);

        Assert.Equal(TimeSpan.FromMinutes(5), policy.Decide(Message(attempt: 1_000_000), Failure).Delay);
    }

    private static InboxMessage Message(int attempt) => TestMessage.With(attempt);
}
