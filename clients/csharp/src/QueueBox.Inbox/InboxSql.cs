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

    /// <summary>
    /// Take up to <c>@batch</c> rows of <c>@source</c> for <c>@lease_ms</c>.
    /// </summary>
    /// <remarks>
    /// The SQL Server text carries its own transaction control (<c>BEGIN TRANSACTION</c> and
    /// <c>COMMIT TRANSACTION</c>). The claim must be alone in that transaction: put no other
    /// application work in it, and commit it before starting any handler. A wider transaction
    /// around the claim changes three things silently: the claimed rows stay uncommitted until
    /// the wider transaction commits, a lock failure rolls back that whole wider transaction
    /// instead of only the claim, and the per-source applock stays held for as long as the wider
    /// transaction stays open instead of releasing at the claim's own commit.
    /// </remarks>
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

        // The claim takes at most one message per aggregate. A row whose aggregate already holds
        // a message in state 'processing' under a live lease is not a candidate, and two rows of
        // one aggregate never leave one claim together. The full readiness predicate repeats in
        // the locking read and again in the final UPDATE, which restores the EvalPlanQual
        // re-check: without it, two workers can claim the same row.
        var claim = $"""
            WITH candidates AS (
                (
                    SELECT {Q(s.Id)}, {Q(s.AggregateId)}, {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}
                    FROM {Q(s.Table)}
                    WHERE {Q(s.Consumption)} = 'pull' AND {Q(s.Source)} = @source AND {Q(s.State)} = 'pending'
                      AND {Q(s.ScheduledAt)} <= clock_timestamp()
                    ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}, {Q(s.Id)}
                    LIMIT @cand_limit
                )
                UNION ALL
                (
                    SELECT {Q(s.Id)}, {Q(s.AggregateId)}, {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}
                    FROM {Q(s.Table)}
                    WHERE {Q(s.Consumption)} = 'pull' AND {Q(s.Source)} = @source AND {Q(s.State)} = 'processing'
                      AND {Q(s.LeaseExpiresAt)} <= clock_timestamp()
                    ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}, {Q(s.Id)}
                    LIMIT @cand_limit
                )
            ),
            ready AS (
                SELECT {Q(s.Id)}, {Q(s.AggregateId)}, {Q(s.ScheduledAt)}, {Q(s.CreatedAt)},
                       row_number() OVER (
                           PARTITION BY COALESCE({Q(s.AggregateId)}, '#' || {Q(s.Id)}::text)
                           ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}, {Q(s.Id)}
                       ) AS rn
                FROM candidates
            ),
            eligible AS (
                SELECT r.{Q(s.Id)}, r.{Q(s.AggregateId)}, r.{Q(s.ScheduledAt)}, r.{Q(s.CreatedAt)}
                FROM ready AS r
                WHERE r.rn = 1
                  AND (r.{Q(s.AggregateId)} IS NULL OR NOT EXISTS (
                        SELECT 1 FROM {Q(s.Table)} AS busy
                        WHERE busy.{Q(s.AggregateId)} = r.{Q(s.AggregateId)}
                          AND busy.{Q(s.Source)} = @source
                          AND busy.{Q(s.Consumption)} = 'pull'
                          AND busy.{Q(s.State)} = 'processing'
                          AND busy.{Q(s.LeaseExpiresAt)} > clock_timestamp()))
                ORDER BY r.{Q(s.ScheduledAt)}, r.{Q(s.CreatedAt)}, r.{Q(s.Id)}
                LIMIT @batch
            ),
            locked AS (
                SELECT i.{Q(s.Id)} FROM {Q(s.Table)} AS i
                JOIN eligible AS e ON i.{Q(s.Id)} = e.{Q(s.Id)}
                WHERE i.{Q(s.Consumption)} = 'pull' AND i.{Q(s.Source)} = @source
                  AND ((i.{Q(s.State)} = 'pending' AND i.{Q(s.ScheduledAt)} <= clock_timestamp())
                    OR (i.{Q(s.State)} = 'processing' AND i.{Q(s.LeaseExpiresAt)} <= clock_timestamp()))
                  AND (i.{Q(s.AggregateId)} IS NULL OR NOT EXISTS (
                        SELECT 1 FROM {Q(s.Table)} AS busy
                        WHERE busy.{Q(s.AggregateId)} = i.{Q(s.AggregateId)}
                          AND busy.{Q(s.Source)} = @source
                          AND busy.{Q(s.Consumption)} = 'pull'
                          AND busy.{Q(s.State)} = 'processing'
                          AND busy.{Q(s.LeaseExpiresAt)} > clock_timestamp()))
                FOR UPDATE SKIP LOCKED
            )
            UPDATE {Q(s.Table)} AS target
            SET {Q(s.State)} = 'processing', {Q(s.ClaimToken)} = gen_random_uuid(), {Q(s.ClaimedAt)} = clock_timestamp(),
                {Q(s.LeaseExpiresAt)} = clock_timestamp() + @lease_ms * INTERVAL '1 millisecond'
            FROM locked
            WHERE target.{Q(s.Id)} = locked.{Q(s.Id)}
              AND target.{Q(s.Consumption)} = 'pull' AND target.{Q(s.Source)} = @source
              AND ((target.{Q(s.State)} = 'pending' AND target.{Q(s.ScheduledAt)} <= clock_timestamp())
                OR (target.{Q(s.State)} = 'processing' AND target.{Q(s.LeaseExpiresAt)} <= clock_timestamp()))
              AND (target.{Q(s.AggregateId)} IS NULL OR NOT EXISTS (
                    SELECT 1 FROM {Q(s.Table)} AS busy
                    WHERE busy.{Q(s.AggregateId)} = target.{Q(s.AggregateId)}
                      AND busy.{Q(s.Source)} = @source
                      AND busy.{Q(s.Consumption)} = 'pull'
                      AND busy.{Q(s.State)} = 'processing'
                      AND busy.{Q(s.LeaseExpiresAt)} > clock_timestamp()))
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

        // The claim takes at most one message per aggregate. A row whose aggregate already holds
        // a message in state 'processing' under a live lease is not a candidate, and two rows of
        // one aggregate never leave one claim together.
        //
        // sp_getapplock serializes claims per source, scoped to @src so different sources do not
        // block each other. It returns a negative value on a lock timeout or on a deadlock, and
        // that return is checked and THROWn below rather than left to proceed unserialized. The
        // local variable names (@qbBatch, @qbLeaseMs, @qbCandLimit) are the canonical names of
        // examples/pull/sql/sqlserver/claim.sql; see that file for why they differ from the
        // bound parameter names.
        var claim = $"""
            BEGIN TRANSACTION;
            DECLARE @qbBatch INT = @batch;
            DECLARE @qbLeaseMs INT = @lease_ms;
            DECLARE @qbCandLimit INT = @cand_limit; -- LEAST(GREATEST(3 * @qbBatch, 50), 500)
            DECLARE @src VARCHAR(255) = @source;
            DECLARE @lockresult INT;
            EXEC @lockresult = sp_getapplock @Resource = @src, @LockMode = 'Exclusive',
                @LockOwner = 'Transaction', @LockTimeout = 10000;
            IF @lockresult < 0
            BEGIN
                ROLLBACK TRANSACTION;
                THROW 51000, 'inbox pull claim: sp_getapplock did not acquire the per-source claim lock', 1;
            END
            ;WITH candidates AS (
                SELECT TOP (@qbCandLimit) {Q(s.Id)}, {Q(s.AggregateId)}, {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}
                FROM {Q(s.Table)} WITH (UPDLOCK, READPAST, ROWLOCK)
                WHERE {Q(s.Consumption)} = 'pull' AND {Q(s.Source)} = @src AND {Q(s.State)} = 'pending'
                  AND {Q(s.ScheduledAt)} <= SYSUTCDATETIME()
                ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}, {Q(s.Id)}
                UNION ALL
                SELECT TOP (@qbCandLimit) {Q(s.Id)}, {Q(s.AggregateId)}, {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}
                FROM {Q(s.Table)} WITH (UPDLOCK, READPAST, ROWLOCK)
                WHERE {Q(s.Consumption)} = 'pull' AND {Q(s.Source)} = @src AND {Q(s.State)} = 'processing'
                  AND {Q(s.LeaseExpiresAt)} <= SYSUTCDATETIME()
                ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}, {Q(s.Id)}
            ),
            ready AS (
                SELECT {Q(s.Id)}, {Q(s.AggregateId)}, {Q(s.ScheduledAt)}, {Q(s.CreatedAt)},
                       ROW_NUMBER() OVER (
                           PARTITION BY COALESCE({Q(s.AggregateId)}, '#' + CAST({Q(s.Id)} AS VARCHAR(36)))
                           ORDER BY {Q(s.ScheduledAt)}, {Q(s.CreatedAt)}, {Q(s.Id)}
                       ) AS rn
                FROM candidates
            ),
            eligible AS (
                SELECT TOP (@qbBatch) r.{Q(s.Id)}, r.{Q(s.AggregateId)}, r.{Q(s.ScheduledAt)}, r.{Q(s.CreatedAt)}
                FROM ready AS r
                WHERE r.rn = 1
                  AND (r.{Q(s.AggregateId)} IS NULL OR NOT EXISTS (
                        SELECT 1 FROM {Q(s.Table)} AS busy
                        WHERE busy.{Q(s.AggregateId)} = r.{Q(s.AggregateId)}
                          AND busy.{Q(s.Source)} = @src
                          AND busy.{Q(s.Consumption)} = 'pull'
                          AND busy.{Q(s.State)} = 'processing'
                          AND busy.{Q(s.LeaseExpiresAt)} > SYSUTCDATETIME()))
                ORDER BY r.{Q(s.ScheduledAt)}, r.{Q(s.CreatedAt)}, r.{Q(s.Id)}
            )
            UPDATE target
            SET {Q(s.State)} = 'processing', {Q(s.ClaimToken)} = NEWID(), {Q(s.ClaimedAt)} = SYSUTCDATETIME(),
                {Q(s.LeaseExpiresAt)} = DATEADD(millisecond, @qbLeaseMs, SYSUTCDATETIME())
            OUTPUT inserted.*
            FROM {Q(s.Table)} AS target WITH (UPDLOCK, ROWLOCK)
            JOIN eligible AS e ON target.{Q(s.Id)} = e.{Q(s.Id)}
            WHERE target.{Q(s.Consumption)} = 'pull' AND target.{Q(s.Source)} = @src
              AND ((target.{Q(s.State)} = 'pending' AND target.{Q(s.ScheduledAt)} <= SYSUTCDATETIME())
                OR (target.{Q(s.State)} = 'processing' AND target.{Q(s.LeaseExpiresAt)} <= SYSUTCDATETIME()))
              AND (target.{Q(s.AggregateId)} IS NULL OR NOT EXISTS (
                    SELECT 1 FROM {Q(s.Table)} AS busy
                    WHERE busy.{Q(s.AggregateId)} = target.{Q(s.AggregateId)}
                      AND busy.{Q(s.Source)} = @src
                      AND busy.{Q(s.Consumption)} = 'pull'
                      AND busy.{Q(s.State)} = 'processing'
                      AND busy.{Q(s.LeaseExpiresAt)} > SYSUTCDATETIME()));
            COMMIT TRANSACTION;
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
