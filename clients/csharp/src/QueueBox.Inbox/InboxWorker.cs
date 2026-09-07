using System.Data.Common;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace QueueBox.Inbox;

/// <summary>
/// Claims QueueBox pull messages, hands each one to a handler inside a transaction, and completes,
/// retries or dead-letters it.
/// </summary>
public sealed class InboxWorker
{
    private readonly IInboxConnectionSource _connections;
    private readonly InboxOptions _options;
    private readonly InboxSql _sql;
    private readonly IInboxRetryPolicy _policy;
    private readonly ILogger _logger;
    private readonly TimeProvider _time;

    /// <summary>Build a worker.</summary>
    /// <param name="connections">Opens the connections the worker needs.</param>
    /// <param name="options">The settings.</param>
    /// <param name="logger">The caller's logger. The library prints nothing without one.</param>
    /// <param name="timeProvider">The clock, for tests.</param>
    public InboxWorker(
        IInboxConnectionSource connections,
        InboxOptions options,
        ILogger<InboxWorker>? logger = null,
        TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(connections);
        ArgumentNullException.ThrowIfNull(options);
        options.Validate();

        _connections = connections;
        _options = options;
        _sql = InboxSql.For(options.Dialect, options.Schema);
        _policy = options.RetryPolicy ?? new DefaultRetryPolicy();
        _logger = logger ?? NullLogger<InboxWorker>.Instance;
        _time = timeProvider ?? TimeProvider.System;
    }

    /// <summary>
    /// Run until the token is cancelled.
    /// <para>
    /// A cancellation stops the claiming at once. The handlers that already run keep their grace,
    /// and a handler that does not finish inside it is cancelled and its message is abandoned.
    /// An abandoned message is never completed; its lease expires and another worker takes it.
    /// </para>
    /// </summary>
    /// <param name="handler">Handles one message.</param>
    /// <param name="cancellationToken">Stops the worker.</param>
    /// <returns>A task that finishes when the worker stopped.</returns>
    public async Task RunAsync(InboxHandler handler, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(handler);

        // The handlers do not share the caller's token. A shutdown must let them finish, so the
        // cancellation reaches them only after the grace has run out.
        using var handlerStop = new CancellationTokenSource();
        using var grace = _time.CreateTimer(
            static state => ((CancellationTokenSource)state!).Cancel(),
            handlerStop,
            Timeout.InfiniteTimeSpan,
            Timeout.InfiniteTimeSpan);
        await using var registration = cancellationToken
            .Register(() => grace.Change(_options.ShutdownGrace, Timeout.InfiniteTimeSpan))
            .ConfigureAwait(false);

        using var slots = new SemaphoreSlim(_options.EffectiveConcurrency, _options.EffectiveConcurrency);

        while (!cancellationToken.IsCancellationRequested)
        {
            IReadOnlyList<ClaimedMessage> batch;

            try
            {
                batch = await ClaimAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (DbException error)
            {
                InboxWorkerLog.ClaimFailed(_logger, ErrorSanitizer.Sanitize(error));
                await DelayAsync(_options.PollInterval, cancellationToken).ConfigureAwait(false);
                continue;
            }

            if (batch.Count == 0)
            {
                // No polling storm. The caller owns the interval.
                if (!await DelayAsync(_options.PollInterval, cancellationToken).ConfigureAwait(false))
                {
                    break;
                }

                continue;
            }

            var running = new List<Task>(batch.Count);

            foreach (var claimed in batch)
            {
                await slots.WaitAsync(CancellationToken.None).ConfigureAwait(false);
                running.Add(RunOneAsync(handler, claimed, slots, handlerStop.Token));
            }

            await Task.WhenAll(running).ConfigureAwait(false);
        }
    }

    private async Task RunOneAsync(InboxHandler handler, ClaimedMessage claimed, SemaphoreSlim slots, CancellationToken stop)
    {
        try
        {
            await ProcessAsync(handler, claimed, stop).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            // A failure of the library itself, not of the handler. The message keeps its lease
            // and returns when the lease expires.
            InboxWorkerLog.MessageAbandoned(_logger, claimed.Message.Id, ErrorSanitizer.Sanitize(error));
        }
        finally
        {
            slots.Release();
        }
    }

    private async Task ProcessAsync(InboxHandler handler, ClaimedMessage claimed, CancellationToken stop)
    {
        var message = claimed.Message;

        await using var connection = await _connections.OpenAsync(CancellationToken.None).ConfigureAwait(false);
        await using var transaction = await connection.BeginTransactionAsync(CancellationToken.None).ConfigureAwait(false);

        await using var lease = new LeaseRenewal(this, claimed, stop);

        try
        {
            await handler(message, transaction, lease.HandlerToken).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            await lease.StopAsync().ConfigureAwait(false);

            if (lease.OwnershipLost)
            {
                InboxWorkerLog.LeaseLostWithoutWrite(_logger, message.Id);
                return;
            }

            if (error is OperationCanceledException && stop.IsCancellationRequested)
            {
                // The shutdown ran out of grace. The message is abandoned, not failed, so it
                // spends no attempt and reaches no dead letter. Its lease expires and another
                // worker takes it.
                InboxWorkerLog.AbandonedOnShutdown(_logger, message.Id);
                return;
            }

            await ApplyFailureAsync(claimed, error).ConfigureAwait(false);
            return;
        }

        await lease.StopAsync().ConfigureAwait(false);

        if (lease.OwnershipLost)
        {
            // The renewal already told us that another worker owns the message. Completing it
            // would affect zero rows anyway, but the application's writes must go first.
            await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
            InboxWorkerLog.LeaseLostAfterHandler(_logger, message.Id);
            return;
        }

        // Section 2. The completion runs inside the handler's transaction, so the application's
        // writes and the completion commit together or neither of them does.
        var affected = await CompleteAsync(connection, transaction, claimed).ConfigureAwait(false);

        if (affected == 1)
        {
            await transaction.CommitAsync(CancellationToken.None).ConfigureAwait(false);
            return;
        }

        await transaction.RollbackAsync(CancellationToken.None).ConfigureAwait(false);
        InboxWorkerLog.CompletionAffectedNoRow(_logger, message.Id);
    }

    private async Task ApplyFailureAsync(ClaimedMessage claimed, Exception failure)
    {
        var action = _policy.Decide(claimed.Message, failure);
        var error = ErrorSanitizer.Sanitize(failure);

        await using var connection = await _connections.OpenAsync(CancellationToken.None).ConfigureAwait(false);
        await using var transaction = await connection.BeginTransactionAsync(CancellationToken.None).ConfigureAwait(false);
        await using var command = connection.CreateCommand();

        command.Transaction = transaction;
        command.CommandText = action.ShouldRetry ? _sql.Retry : _sql.Dead;
        command.WithParameter("@id", claimed.Message.Id).WithParameter("@token", claimed.Token).WithParameter("@error", error);

        if (action.ShouldRetry)
        {
            command.WithParameter("@delay_ms", (int)Math.Min(action.Delay.TotalMilliseconds, int.MaxValue));
        }

        var affected = await command.ExecuteNonQueryAsync(CancellationToken.None).ConfigureAwait(false);
        await transaction.CommitAsync(CancellationToken.None).ConfigureAwait(false);

        if (affected == 0)
        {
            // The claim was already lost. Nothing changed, which is correct.
            InboxWorkerLog.FailureOnLostClaim(_logger, claimed.Message.Id);
            return;
        }

        if (action.ShouldRetry)
        {
            InboxWorkerLog.Retrying(_logger, claimed.Message.Id, action.Delay);
        }
        else
        {
            InboxWorkerLog.DeadLettered(_logger, claimed.Message.Id);
        }
    }

    private async Task<int> CompleteAsync(DbConnection connection, DbTransaction transaction, ClaimedMessage claimed)
    {
        await using var command = connection.CreateCommand();

        command.Transaction = transaction;
        command.CommandText = _sql.Complete;
        command.WithParameter("@id", claimed.Message.Id).WithParameter("@token", claimed.Token);

        return await command.ExecuteNonQueryAsync(CancellationToken.None).ConfigureAwait(false);
    }

    private async Task<IReadOnlyList<ClaimedMessage>> ClaimAsync(CancellationToken cancellationToken)
    {
        // The claim runs in a short transaction of its own and commits before any handler starts.
        await using var connection = await _connections.OpenAsync(cancellationToken).ConfigureAwait(false);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();

        command.Transaction = transaction;
        command.CommandText = _sql.Claim;
        command
            .WithParameter("@source", _options.Source)
            .WithParameter("@batch", _options.BatchSize)
            .WithParameter("@lease_ms", _options.LeaseMs);

        var claimed = new List<ClaimedMessage>(_options.BatchSize);

        await using (var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false))
        {
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                claimed.Add(Read(reader, _options.Schema));
            }
        }

        await transaction.CommitAsync(cancellationToken).ConfigureAwait(false);
        return claimed;
    }

    private static ClaimedMessage Read(DbDataReader reader, InboxSchema schema)
    {
        var payloadText = reader.GetNullableString(schema.Payload) ?? "null";

        using var document = JsonDocument.Parse(payloadText);

        var message = new InboxMessage(
            reader.GetFieldValue<Guid>(reader.GetOrdinal(schema.Id)),
            reader.GetFieldValue<string>(reader.GetOrdinal(schema.Source)),
            reader.GetFieldValue<string>(reader.GetOrdinal(schema.IdempotencyKey)),
            reader.GetNullableString(schema.AggregateId),
            reader.GetNullableString(schema.EventType),
            document.RootElement.Clone(),
            reader.GetFieldValue<int>(reader.GetOrdinal(schema.Attempt)),
            reader.GetNullableString(schema.CorrelationId));

        return new ClaimedMessage(message, reader.GetFieldValue<Guid>(reader.GetOrdinal(schema.ClaimToken)));
    }

    /// <summary>Waits, and reports whether the wait finished rather than the worker stopping.</summary>
    private async Task<bool> DelayAsync(TimeSpan delay, CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(delay, _time, cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    /// <summary>A claimed message and the token that fences every statement about it.</summary>
    private sealed record ClaimedMessage(InboxMessage Message, Guid Token);

    /// <summary>
    /// Renews the lease while the handler runs. A renewal that affects zero rows means the
    /// ownership is lost, so the renewal cancels the handler and reports the loss.
    /// </summary>
    private sealed class LeaseRenewal : IAsyncDisposable
    {
        private readonly InboxWorker _worker;
        private readonly ClaimedMessage _claimed;
        private readonly CancellationTokenSource _renewalStop = new();
        private readonly CancellationTokenSource _handlerStop;
        private readonly Task _loop;
        private int _ownershipLost;

        internal LeaseRenewal(InboxWorker worker, ClaimedMessage claimed, CancellationToken stop)
        {
            _worker = worker;
            _claimed = claimed;
            _handlerStop = CancellationTokenSource.CreateLinkedTokenSource(stop);
            _loop = Task.Run(() => RenewAsync(_renewalStop.Token), CancellationToken.None);
        }

        internal CancellationToken HandlerToken => _handlerStop.Token;

        internal bool OwnershipLost => Volatile.Read(ref _ownershipLost) == 1;

        internal async Task StopAsync()
        {
            // The renewal timer stops when the handler returns, however it returns.
            await _renewalStop.CancelAsync().ConfigureAwait(false);
            await _loop.ConfigureAwait(false);
        }

        public async ValueTask DisposeAsync()
        {
            await StopAsync().ConfigureAwait(false);
            _renewalStop.Dispose();
            _handlerStop.Dispose();
        }

        private async Task RenewAsync(CancellationToken stop)
        {
            while (!stop.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(_worker._options.RenewalInterval, _worker._time, stop).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }

                int affected;

                try
                {
                    affected = await RenewOnceAsync(stop).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (DbException error)
                {
                    // A renewal that could not run is not a lost lease. The next one tries again,
                    // and the lease expires on its own if none of them succeeds.
                    InboxWorkerLog.RenewalFailed(_worker._logger, _claimed.Message.Id, ErrorSanitizer.Sanitize(error));
                    continue;
                }

                if (affected == 0)
                {
                    Volatile.Write(ref _ownershipLost, 1);
                    InboxWorkerLog.LeaseLostToAnotherWorker(_worker._logger, _claimed.Message.Id);
                    await _handlerStop.CancelAsync().ConfigureAwait(false);
                    return;
                }
            }
        }

        private async Task<int> RenewOnceAsync(CancellationToken stop)
        {
            // The renewal needs its own connection. The handler's connection is inside the
            // handler's transaction, and a renewal there would commit with it.
            await using var connection = await _worker._connections.OpenAsync(stop).ConfigureAwait(false);
            await using var command = connection.CreateCommand();

            command.CommandText = _worker._sql.Renew;
            command
                .WithParameter("@lease_ms", _worker._options.LeaseMs)
                .WithParameter("@id", _claimed.Message.Id)
                .WithParameter("@token", _claimed.Token);

            return await command.ExecuteNonQueryAsync(stop).ConfigureAwait(false);
        }
    }
}
