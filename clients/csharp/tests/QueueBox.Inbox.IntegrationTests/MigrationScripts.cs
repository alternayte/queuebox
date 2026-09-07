using System.Text.RegularExpressions;

namespace QueueBox.Inbox.IntegrationTests;

/// <summary>
/// Reads the QueueBox migrations from the repository.
/// The tests apply the real schema, so the library cannot drift away from it.
/// </summary>
internal static partial class MigrationScripts
{
    internal static IReadOnlyList<string> For(string dialectDirectory)
    {
        var root = RepositoryRoot();
        var module = dialectDirectory == "postgresql" ? "postgres" : "sqlserver";
        var directory = Path.Combine(root, module, "src", "main", "resources", "db", dialectDirectory);

        return Directory.GetFiles(directory, "V*.sql")
            .OrderBy(Version)
            .Select(File.ReadAllText)
            .ToList();
    }

    private static int Version(string path)
    {
        var match = VersionPattern().Match(Path.GetFileName(path));
        return match.Success ? int.Parse(match.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) : 0;
    }

    private static string RepositoryRoot()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);

        while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, ".git")))
        {
            directory = directory.Parent;
        }

        return directory?.FullName ?? throw new InvalidOperationException("The repository root was not found.");
    }

    [GeneratedRegex(@"^V(\d+)__")]
    private static partial Regex VersionPattern();
}
