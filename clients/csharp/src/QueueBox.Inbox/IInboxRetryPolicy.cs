namespace QueueBox.Inbox;

/// <summary>
/// Decides what happens to a message whose handler threw.
/// The library exposes the decision, because only the application knows that a validation error
/// must never be retried while a timeout must.
/// </summary>
public interface IInboxRetryPolicy
{
    /// <summary>Choose between a retry and the dead letter.</summary>
    /// <param name="message">The message that failed.</param>
    /// <param name="failure">The exception the handler threw.</param>
    /// <returns>The action the library applies.</returns>
    InboxFailureAction Decide(InboxMessage message, Exception failure);
}
