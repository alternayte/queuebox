namespace QueueBox.Inbox;

/// <summary>
/// The five statements of the pull contract, rendered for one dialect and one schema.
/// The text follows <c>examples/pull/sql</c> exactly. Only the identifiers move.
/// </summary>
public sealed class InboxSql
{
    private InboxSql(string claim, string renew, string complete, string retry, string dead)
    {
        Claim = claim;
        Renew = renew;
        Complete = complete;
        Retry = retry;
        Dead = dead;
    }

    /// <summary>Take up to <c>@batch</c> rows of <c>@source</c> for <c>@lease_ms</c>.</summary>
    public string Claim { get; }

    /// <summary>Extend the lease of <c>@id</c> under <c>@token</c>.</summary>
    public string Renew { get; }

    /// <summary>Mark <c>@id</c> processed under <c>@token</c>.</summary>
    public string Complete { get; }

    /// <summary>Return <c>@id</c> to pending after <c>@delay_ms</c>, under <c>@token</c>.</summary>
    public string Retry { get; }

    /// <summary>Mark <c>@id</c> dead under <c>@token</c>.</summary>
    public string Dead { get; }

    /// <summary>Render the statements for one dialect and one schema.</summary>
    /// <param name="dialect">The database dialect.</param>
    /// <param name="schema">The table and column names.</param>
    /// <returns>The five statements.</returns>
    public static InboxSql For(SqlDialect dialect, InboxSchema schema)
    {
        ArgumentNullException.ThrowIfNull(schema);
        schema.Validate();

        return dialect switch
        {
            SqlDialect.PostgreSql => PostgreSql(schema),
            SqlDialect.SqlServer => SqlServer(schema),
            _ => throw new ArgumentOutOfRangeException(nameof(dialect), dialect, "The dialect is not supported."),
        };
    }

    private static InboxSql PostgreSql(InboxSchema s)
    {
        static string Q(string name) => "\"" + name + "\"";

        // The fence that every statement after the claim shares.
        var fence = $"""
            WHERE {Q(s.Id)} = @id AND {Q(s.Consumption)} = 'pull' AND {Q(s.State)} = 'processing'
              AND {Q(s.ClaimToken)} = @token AND {Q(s.LeaseExpiresAt)} > clock_timestamp()
            """;

        var claim = $"""
            WITH candidates AS (
                SELECT {Q(s.Id)} FROM {Q(s.Table)}
                WHERE {Q(s.Consumption)} = 'pull' AND {Q(s.Source)} = @source
                  AND (({Q(s.State)} = 'pending' AND {Q(s.ScheduledAt)} <= clock_timestamp())
                    OR ({Q(s.State)} = 'processing' AND {Q(s.LeaseExpiresAt)} <= clock_timestamp()))
                ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}
                LIMIT @batch
                FOR UPDATE SKIP LOCKED
            )
            UPDATE {Q(s.Table)} AS target
            SET {Q(s.State)} = 'processing', {Q(s.ClaimToken)} = gen_random_uuid(),
                {Q(s.ClaimedAt)} = clock_timestamp(),
                {Q(s.LeaseExpiresAt)} = clock_timestamp() + @lease_ms * INTERVAL '1 millisecond'
            FROM candidates WHERE target.{Q(s.Id)} = candidates.{Q(s.Id)}
            RETURNING target.*;
            """;

        var renew = $"""
            UPDATE {Q(s.Table)} SET {Q(s.LeaseExpiresAt)} = clock_timestamp() + @lease_ms * INTERVAL '1 millisecond'
            {fence};
            """;

        var complete = $"""
            UPDATE {Q(s.Table)} SET {Q(s.State)} = 'processed', {Q(s.ProcessedAt)} = clock_timestamp(), {Q(s.ClaimToken)} = NULL, {Q(s.LeaseExpiresAt)} = NULL
            {fence};
            """;

        var retry = $"""
            UPDATE {Q(s.Table)} SET {Q(s.State)} = 'pending', {Q(s.ScheduledAt)} = clock_timestamp() + @delay_ms * INTERVAL '1 millisecond', {Q(s.Attempt)} = {Q(s.Attempt)} + 1, {Q(s.LastError)} = @error, {Q(s.ClaimToken)} = NULL, {Q(s.LeaseExpiresAt)} = NULL
            {fence};
            """;

        var dead = $"""
            UPDATE {Q(s.Table)} SET {Q(s.State)} = 'dead', {Q(s.LastError)} = @error, {Q(s.ClaimToken)} = NULL, {Q(s.LeaseExpiresAt)} = NULL
            {fence};
            """;

        return new InboxSql(claim, renew, complete, retry, dead);
    }

    private static InboxSql SqlServer(InboxSchema s)
    {
        static string Q(string name) => "[" + name + "]";

        var fence = $"""
            WHERE {Q(s.Id)} = @id AND {Q(s.Consumption)} = 'pull' AND {Q(s.State)} = 'processing'
              AND {Q(s.ClaimToken)} = @token AND {Q(s.LeaseExpiresAt)} > SYSUTCDATETIME()
            """;

        var claim = $"""
            WITH candidates AS (
                SELECT TOP (@batch) * FROM {Q(s.Table)} WITH (UPDLOCK, READPAST, ROWLOCK)
                WHERE {Q(s.Consumption)} = 'pull' AND {Q(s.Source)} = @source
                  AND (({Q(s.State)} = 'pending' AND {Q(s.ScheduledAt)} <= SYSUTCDATETIME())
                    OR ({Q(s.State)} = 'processing' AND {Q(s.LeaseExpiresAt)} <= SYSUTCDATETIME()))
                ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}
            )
            UPDATE candidates
            SET {Q(s.State)} = 'processing', {Q(s.ClaimToken)} = NEWID(), {Q(s.ClaimedAt)} = SYSUTCDATETIME(),
                {Q(s.LeaseExpiresAt)} = DATEADD(millisecond, @lease_ms, SYSUTCDATETIME())
            OUTPUT INSERTED.*;
            """;

        var renew = $"""
            UPDATE {Q(s.Table)} SET {Q(s.LeaseExpiresAt)} = DATEADD(millisecond, @lease_ms, SYSUTCDATETIME())
            {fence};
            """;

        var complete = $"""
            UPDATE {Q(s.Table)} SET {Q(s.State)} = 'processed', {Q(s.ProcessedAt)} = SYSUTCDATETIME(), {Q(s.ClaimToken)} = NULL, {Q(s.LeaseExpiresAt)} = NULL
            {fence};
            """;

        var retry = $"""
            UPDATE {Q(s.Table)} SET {Q(s.State)} = 'pending', {Q(s.ScheduledAt)} = DATEADD(millisecond, @delay_ms, SYSUTCDATETIME()), {Q(s.Attempt)} = {Q(s.Attempt)} + 1, {Q(s.LastError)} = @error, {Q(s.ClaimToken)} = NULL, {Q(s.LeaseExpiresAt)} = NULL
            {fence};
            """;

        var dead = $"""
            UPDATE {Q(s.Table)} SET {Q(s.State)} = 'dead', {Q(s.LastError)} = @error, {Q(s.ClaimToken)} = NULL, {Q(s.LeaseExpiresAt)} = NULL
            {fence};
            """;

        return new InboxSql(claim, renew, complete, retry, dead);
    }
}
