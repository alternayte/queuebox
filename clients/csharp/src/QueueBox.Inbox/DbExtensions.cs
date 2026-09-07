using System.Data.Common;

namespace QueueBox.Inbox;

internal static class DbExtensions
{
    internal static string? GetNullableString(this DbDataReader reader, string column)
    {
        var ordinal = reader.GetOrdinal(column);
        return reader.IsDBNull(ordinal) ? null : reader.GetValue(ordinal).ToString();
    }
}
