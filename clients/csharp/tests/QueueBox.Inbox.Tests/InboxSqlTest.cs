namespace QueueBox.Inbox.Tests;

public sealed class InboxSqlTest
{
    [Fact]
    public void PostgreSqlClaimMatchesTheContract()
    {
        var sql = InboxSql.For(SqlDialect.PostgreSql, InboxSchema.Default);

        Assert.Contains("FOR UPDATE SKIP LOCKED", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("FROM \"inbox\"", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("UNION ALL", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("@source", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("@cand_limit", sql.Claim, StringComparison.Ordinal);
        Assert.DoesNotContain(":source", sql.Claim, StringComparison.Ordinal);
        Assert.DoesNotContain(":cand_limit", sql.Claim, StringComparison.Ordinal);

        // The busy check, scoped to (source, aggregate_id), runs at every occurrence.
        Assert.Equal(3, CountOccurrences(sql.Claim, "\"aggregate_id\" = "));
    }

    [Fact]
    public void SqlServerClaimMatchesTheContract()
    {
        var sql = InboxSql.For(SqlDialect.SqlServer, InboxSchema.Default);

        Assert.Contains("UPDLOCK, READPAST, ROWLOCK", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("TOP (@qbCandLimit)", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("TOP (@qbBatch)", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("OUTPUT inserted.*", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("sp_getapplock", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("THROW 51000", sql.Claim, StringComparison.Ordinal);
        Assert.Contains("@cand_limit", sql.Claim, StringComparison.Ordinal);

        // The busy check, scoped to (source, aggregate_id), runs at every occurrence. SQL Server
        // has no separate locking CTE, so the check appears once fewer than PostgreSQL.
        Assert.Equal(2, CountOccurrences(sql.Claim, "[aggregate_id] = "));
    }

    private static int CountOccurrences(string haystack, string needle)
    {
        var count = 0;
        var index = 0;

        while ((index = haystack.IndexOf(needle, index, StringComparison.Ordinal)) >= 0)
        {
            count++;
            index += needle.Length;
        }

        return count;
    }

    [Theory]
    [InlineData(SqlDialect.PostgreSql)]
    [InlineData(SqlDialect.SqlServer)]
    public void EveryFencedStatementCarriesTheToken(SqlDialect dialect)
    {
        var sql = InboxSql.For(dialect, InboxSchema.Default);

        foreach (var statement in new[] { sql.Renew, sql.Complete, sql.Retry, sql.Dead })
        {
            Assert.Contains("@token", statement, StringComparison.Ordinal);
            Assert.Contains("'pull'", statement, StringComparison.Ordinal);
            Assert.Contains("'processing'", statement, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void AMappedSchemaRenamesTheTableAndTheColumn()
    {
        var schema = InboxSchema.Default with { Table = "queuebox_inbox", State = "row_state" };

        var sql = InboxSql.For(SqlDialect.PostgreSql, schema);

        Assert.Contains("\"queuebox_inbox\"", sql.Complete, StringComparison.Ordinal);
        Assert.Contains("\"row_state\" = 'processed'", sql.Complete, StringComparison.Ordinal);
        Assert.DoesNotContain("\"inbox\"", sql.Complete, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("inbox; DROP TABLE users")]
    [InlineData("in\"box")]
    [InlineData("in]box")]
    [InlineData("")]
    public void AnUnsafeNameIsRejected(string table)
    {
        var schema = InboxSchema.Default with { Table = table };

        Assert.Throws<ArgumentException>(() => InboxSql.For(SqlDialect.PostgreSql, schema));
    }
}
