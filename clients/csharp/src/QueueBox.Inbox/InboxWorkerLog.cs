using Microsoft.Extensions.Logging;

namespace QueueBox.Inbox;

/// <summary>
/// The log lines of the worker.
/// The messages are source generated, so the library allocates nothing for a line that the
/// caller's level suppresses, and so it stays trimmable and Native AOT safe.
/// </summary>
internal static partial class InboxWorkerLog
{
    [LoggerMessage(EventId = 1, Level = LogLevel.Error, Message = "The claim failed: {Error}")]
    internal static partial void ClaimFailed(ILogger logger, string? error);

    [LoggerMessage(EventId = 2, Level = LogLevel.Error, Message = "The message {MessageId} was abandoned: {Error}")]
    internal static partial void MessageAbandoned(ILogger logger, Guid messageId, string? error);

    [LoggerMessage(EventId = 3, Level = LogLevel.Warning, Message = "The lease of {MessageId} was lost, so nothing was written.")]
    internal static partial void LeaseLostWithoutWrite(ILogger logger, Guid messageId);

    [LoggerMessage(EventId = 4, Level = LogLevel.Warning, Message = "The lease of {MessageId} was lost, so the work was rolled back.")]
    internal static partial void LeaseLostAfterHandler(ILogger logger, Guid messageId);

    [LoggerMessage(EventId = 5, Level = LogLevel.Warning, Message = "The completion of {MessageId} affected no row, so the work was rolled back.")]
    internal static partial void CompletionAffectedNoRow(ILogger logger, Guid messageId);

    [LoggerMessage(EventId = 6, Level = LogLevel.Warning, Message = "The failure of {MessageId} changed nothing, because the claim was lost.")]
    internal static partial void FailureOnLostClaim(ILogger logger, Guid messageId);

    [LoggerMessage(EventId = 7, Level = LogLevel.Warning, Message = "The message {MessageId} retries after {Delay}.")]
    internal static partial void Retrying(ILogger logger, Guid messageId, TimeSpan delay);

    [LoggerMessage(EventId = 8, Level = LogLevel.Error, Message = "The message {MessageId} reached the dead letter.")]
    internal static partial void DeadLettered(ILogger logger, Guid messageId);

    [LoggerMessage(EventId = 9, Level = LogLevel.Warning, Message = "The renewal of {MessageId} failed: {Error}")]
    internal static partial void RenewalFailed(ILogger logger, Guid messageId, string? error);

    [LoggerMessage(EventId = 10, Level = LogLevel.Warning, Message = "The lease of {MessageId} was lost to another worker.")]
    internal static partial void LeaseLostToAnotherWorker(ILogger logger, Guid messageId);

    [LoggerMessage(EventId = 11, Level = LogLevel.Information, Message = "The shutdown abandoned {MessageId}, so it spends no attempt.")]
    internal static partial void AbandonedOnShutdown(ILogger logger, Guid messageId);
}
