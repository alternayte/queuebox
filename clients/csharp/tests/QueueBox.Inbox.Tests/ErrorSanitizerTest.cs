namespace QueueBox.Inbox.Tests;

public sealed class ErrorSanitizerTest
{
    [Theory]
    // The .NET connection string. The value ends at the semicolon, so the host survives.
    [InlineData("Host=db;Username=app;Password=hunter2;Database=queuebox", "hunter2")]
    [InlineData("Server=db;User ID=sa;Password=P@ssw0rd!;Encrypt=True", "P@ssw0rd!")]
    [InlineData("Server=db;pwd=hunter2;", "hunter2")]
    // The environment and the query notation.
    [InlineData("PGPASSWORD=hunter2 psql failed", "hunter2")]
    [InlineData("connect failed: password: my secret pass", "my secret pass")]
    // A URL that carries the credential in the user information.
    [InlineData("postgres://app:hunter2@db:5432/queuebox refused", "hunter2")]
    [InlineData("amqp://user:pass word@rabbit", "pass word")]
    // A bare authentication scheme.
    [InlineData("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9", "eyJhbGciOiJIUzI1NiJ9")]
    public void ASecretNeverSurvives(string text, string secret)
    {
        var sanitized = ErrorSanitizer.Sanitize(text);

        Assert.NotNull(sanitized);
        Assert.DoesNotContain(secret, sanitized, StringComparison.Ordinal);
    }

    [Fact]
    public void TheHostAndThePortSurvive()
    {
        var sanitized = ErrorSanitizer.Sanitize("postgres://app:hunter2@db:5432/queuebox refused");

        Assert.Contains("db:5432", sanitized, StringComparison.Ordinal);
    }

    [Fact]
    public void AnOrdinaryWordIsNotRedacted()
    {
        Assert.Equal("the token bucket is empty", ErrorSanitizer.Sanitize("the token bucket is empty"));
        Assert.Equal("Digest authentication failed", ErrorSanitizer.Sanitize("Digest authentication failed"));
    }

    [Fact]
    public void ALongTextIsTruncated()
    {
        var sanitized = ErrorSanitizer.Sanitize(new string('x', 5000));

        Assert.NotNull(sanitized);
        Assert.Equal(ErrorSanitizer.MaxLength, sanitized.Length);
        Assert.EndsWith("...[truncated]", sanitized, StringComparison.Ordinal);
    }

    [Fact]
    public void NullStaysNull() => Assert.Null(ErrorSanitizer.Sanitize((string?)null));

    [Fact]
    public void TheWholeCauseChainIsRedacted()
    {
        // A driver puts the connection string in the message of the cause, not of the wrapper.
        var cause = new InvalidOperationException("Host=db;Password=hunter2");
        var error = new InvalidOperationException("the claim failed", cause);

        var sanitized = ErrorSanitizer.Sanitize(error);

        Assert.NotNull(sanitized);
        Assert.DoesNotContain("hunter2", sanitized, StringComparison.Ordinal);
        Assert.Contains("the claim failed", sanitized, StringComparison.Ordinal);
        Assert.Contains("InvalidOperationException", sanitized, StringComparison.Ordinal);
    }

    [Fact]
    public void ACyclicCauseChainTerminates()
    {
        var error = new InvalidOperationException("outer", new InvalidOperationException("inner"));

        var sanitized = ErrorSanitizer.Sanitize(error);

        Assert.Contains("caused by", sanitized, StringComparison.Ordinal);
    }
}
