using System.Data.Common;

namespace QueueBox.Inbox;

/// <summary>
/// Small helpers for a handler.
/// A handler receives a <see cref="DbTransaction"/>, so its command is a <see cref="DbCommand"/>
/// and the convenience of one driver, such as <c>AddWithValue</c>, is not available. These
/// helpers work on both drivers, so a handler reads the same whatever the dialect.
/// </summary>
public static class InboxCommandExtensions
{
    /// <summary>Create a command that already belongs to the transaction.</summary>
    /// <param name="transaction">The handler's transaction.</param>
    /// <returns>The command. The caller disposes it.</returns>
    public static DbCommand CreateCommand(this DbTransaction transaction)
    {
        ArgumentNullException.ThrowIfNull(transaction);

        var connection = transaction.Connection
            ?? throw new InvalidOperationException("The transaction has no connection.");

        var command = connection.CreateCommand();
        command.Transaction = transaction;
        return command;
    }

    /// <summary>Add one named parameter. A null value becomes <see cref="DBNull"/>.</summary>
    /// <param name="command">The command.</param>
    /// <param name="name">The parameter name, for example <c>@id</c>.</param>
    /// <param name="value">The value, which can be null.</param>
    /// <returns>The same command, so the calls chain.</returns>
    public static DbCommand WithParameter(this DbCommand command, string name, object? value)
    {
        ArgumentNullException.ThrowIfNull(command);

        var parameter = command.CreateParameter();
        parameter.ParameterName = name;
        parameter.Value = value ?? DBNull.Value;
        command.Parameters.Add(parameter);

        return command;
    }
}
