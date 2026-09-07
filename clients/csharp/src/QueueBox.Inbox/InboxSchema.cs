using System.Text.RegularExpressions;

namespace QueueBox.Inbox;

/// <summary>
/// The name of the inbox table and of every column the library reads or writes.
/// QueueBox lets an operator map these names, so the library must not assume the defaults.
/// </summary>
public sealed record InboxSchema
{
    // An identifier is quoted before it reaches the database, and it is also checked here.
    // A mapping comes from configuration, and configuration is not a trusted SQL fragment.
    private static readonly Regex SafeIdentifier = new(@"^[A-Za-z_][A-Za-z0-9_$]{0,62}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    /// <summary>The default QueueBox V6 names.</summary>
    public static InboxSchema Default { get; } = new();

    /// <summary>The table that holds the inbox rows.</summary>
    public string Table { get; init; } = "inbox";

    /// <summary>The row identifier.</summary>
    public string Id { get; init; } = "id";

    /// <summary>The push or pull marker.</summary>
    public string Consumption { get; init; } = "consumption";

    /// <summary>The source name.</summary>
    public string Source { get; init; } = "source";

    /// <summary>The row state.</summary>
    public string State { get; init; } = "state";

    /// <summary>The earliest time at which the row can be claimed.</summary>
    public string ScheduledAt { get; init; } = "scheduled_at";

    /// <summary>The time at which the row was stored.</summary>
    public string CreatedAt { get; init; } = "created_at";

    /// <summary>The claim token that fences every later statement.</summary>
    public string ClaimToken { get; init; } = "claim_token";

    /// <summary>The time of the claim.</summary>
    public string ClaimedAt { get; init; } = "claimed_at";

    /// <summary>The time at which the lease ends.</summary>
    public string LeaseExpiresAt { get; init; } = "lease_expires_at";

    /// <summary>The time at which the application finished the work.</summary>
    public string ProcessedAt { get; init; } = "processed_at";

    /// <summary>The delivery counter.</summary>
    public string Attempt { get; init; } = "attempt";

    /// <summary>The last failure text.</summary>
    public string LastError { get; init; } = "last_error";

    /// <summary>The deduplication key.</summary>
    public string IdempotencyKey { get; init; } = "idempotency_key";

    /// <summary>The aggregate identifier, which can be null.</summary>
    public string AggregateId { get; init; } = "aggregate_id";

    /// <summary>The event type, which can be null.</summary>
    public string EventType { get; init; } = "event_type";

    /// <summary>The JSON body.</summary>
    public string Payload { get; init; } = "payload";

    /// <summary>The correlation identifier, which can be null.</summary>
    public string CorrelationId { get; init; } = "correlation_id";

    /// <summary>Reject a name that is not a plain SQL identifier.</summary>
    /// <exception cref="ArgumentException">A name contains a character that is not allowed.</exception>
    public void Validate()
    {
        foreach (var (property, value) in All())
        {
            if (!SafeIdentifier.IsMatch(value))
            {
                throw new ArgumentException($"The schema name '{property}' is not a plain SQL identifier.", nameof(InboxSchema));
            }
        }
    }

    internal IEnumerable<(string Property, string Value)> All()
    {
        yield return (nameof(Table), Table);
        yield return (nameof(Id), Id);
        yield return (nameof(Consumption), Consumption);
        yield return (nameof(Source), Source);
        yield return (nameof(State), State);
        yield return (nameof(ScheduledAt), ScheduledAt);
        yield return (nameof(CreatedAt), CreatedAt);
        yield return (nameof(ClaimToken), ClaimToken);
        yield return (nameof(ClaimedAt), ClaimedAt);
        yield return (nameof(LeaseExpiresAt), LeaseExpiresAt);
        yield return (nameof(ProcessedAt), ProcessedAt);
        yield return (nameof(Attempt), Attempt);
        yield return (nameof(LastError), LastError);
        yield return (nameof(IdempotencyKey), IdempotencyKey);
        yield return (nameof(AggregateId), AggregateId);
        yield return (nameof(EventType), EventType);
        yield return (nameof(Payload), Payload);
        yield return (nameof(CorrelationId), CorrelationId);
    }
}
