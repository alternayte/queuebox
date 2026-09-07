using System.Text.Json;

namespace QueueBox.Inbox.Tests;

/// <summary>Builds a message for a test that never touches a database.</summary>
internal static class TestMessage
{
    private static readonly JsonElement EmptyPayload = JsonDocument.Parse("{}").RootElement.Clone();

    internal static InboxMessage With(int attempt) => new(
        Guid.NewGuid(),
        "orders",
        "key-1",
        aggregateId: null,
        eventType: null,
        EmptyPayload,
        attempt,
        correlationId: null);
}
