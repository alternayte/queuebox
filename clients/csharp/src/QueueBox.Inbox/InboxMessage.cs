using System.Text.Json;

namespace QueueBox.Inbox;

/// <summary>
/// One claimed inbox row, as the handler sees it.
/// The claim token is deliberately absent. The library owns the token, because a handler that
/// could reach it could complete a message out of band.
/// </summary>
public sealed class InboxMessage
{
    internal InboxMessage(
        Guid id,
        string source,
        string idempotencyKey,
        string? aggregateId,
        string? eventType,
        JsonElement payload,
        int attempt,
        string? correlationId)
    {
        Id = id;
        Source = source;
        IdempotencyKey = idempotencyKey;
        AggregateId = aggregateId;
        EventType = eventType;
        Payload = payload;
        Attempt = attempt;
        CorrelationId = correlationId;
    }

    /// <summary>The inbox row identifier.</summary>
    public Guid Id { get; }

    /// <summary>The source name.</summary>
    public string Source { get; }

    /// <summary>The deduplication key. The full identity is the source and this key together.</summary>
    public string IdempotencyKey { get; }

    /// <summary>The aggregate identifier, or null.</summary>
    public string? AggregateId { get; }

    /// <summary>The event type, or null.</summary>
    public string? EventType { get; }

    /// <summary>The JSON body, parsed.</summary>
    public JsonElement Payload { get; }

    /// <summary>The delivery counter. It is zero on the first delivery.</summary>
    public int Attempt { get; }

    /// <summary>The correlation identifier for logs, or null.</summary>
    public string? CorrelationId { get; }
}
