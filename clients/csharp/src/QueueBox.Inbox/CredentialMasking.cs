using System.Text.RegularExpressions;

namespace QueueBox.Inbox;

/// <summary>
/// Hides the credential that a URL still carries.
/// This is a port of <c>CredentialMasking</c> in QueueBox. The two must not drift apart.
/// </summary>
internal static partial class CredentialMasking
{
    // The scheme name is BOUNDED, for the reason ErrorSanitizer states about its key prefix: an
    // unbounded run in front of a required literal makes the pattern quadratic in the length of
    // the text. The longest registered URI scheme is far below thirty two characters.
    private const string Scheme = "([a-zA-Z][a-zA-Z0-9+.-]{0,31}:/{1,2})";
    private const string User = "[^\\s/?#@]*:";
    private const string HostAfter = "(?=[^/?#\\s@]*(?:[/?#\\s]|$))";
    private const string PlausibleHostAfter = "(?=[^/?#\\s@]*:[0-9]+(?:[/?#\\s]|$)|[^/?#\\s@]*[/?#])";

    // The runs are bounded. An unbounded run swallowed the host of an ordinary sentence that
    // named a URL and an address. A comma followed by whitespace is prose, never a user
    // information, so it stops the run as well.
    private const string SlashRun = "(?:(?!://)(?!,\\s)[^?#]){0,200}";
    private const string NoSlashRun = "(?:(?!,\\s)[^/?#]){0,200}";

    private const string Mask = "***";

    /// <summary>Replace the user information and the password parameter of every URL.</summary>
    internal static string MaskUrl(string url)
    {
        var masked = UserInfoSpaceWithSlash().Replace(url, "$1" + Mask + "@");
        masked = UserInfoSpaceNoSlash().Replace(masked, "$1" + Mask + "@");
        masked = UserInfoNoSpace().Replace(masked, "$1" + Mask + "@");
        return PasswordParameter().Replace(masked, "$1=" + Mask);
    }

    [GeneratedRegex(Scheme + User + SlashRun + "\\s" + SlashRun + "@" + PlausibleHostAfter)]
    private static partial Regex UserInfoSpaceWithSlash();

    [GeneratedRegex(Scheme + User + NoSlashRun + "\\s" + NoSlashRun + "@" + HostAfter)]
    private static partial Regex UserInfoSpaceNoSlash();

    [GeneratedRegex(Scheme + "(?:[^\\s/@]*:(?:(?!://)\\S)*|[^\\s/@]*)@" + HostAfter)]
    private static partial Regex UserInfoNoSpace();

    [GeneratedRegex("([?&;](?:password|pwd|secret|token))=[^&;\\s]*", RegexOptions.IgnoreCase)]
    private static partial Regex PasswordParameter();
}
