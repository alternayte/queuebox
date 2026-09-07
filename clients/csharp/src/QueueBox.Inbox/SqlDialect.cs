namespace QueueBox.Inbox;

/// <summary>The database dialect that the inbox table lives in.</summary>
public enum SqlDialect
{
    /// <summary>PostgreSQL, through Npgsql.</summary>
    PostgreSql,

    /// <summary>Microsoft SQL Server, through Microsoft.Data.SqlClient.</summary>
    SqlServer,
}
