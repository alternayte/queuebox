using System.Text;
using System.Text.RegularExpressions;

namespace QueueBox.Inbox;

/// <summary>
/// Prepares an error text for the <c>last_error</c> column and for a log line.
/// This is a port of <c>ErrorSanitizer</c> in QueueBox. The two must not drift apart.
/// </summary>
public static partial class ErrorSanitizer
{
    /// <summary>The longest text that the sanitiser returns.</summary>
    public const int MaxLength = 2000;

    private const string Redacted = "[REDACTED]";
    private const string TruncationMarker = "...[truncated]";
    private const int MaxCauseDepth = 5;

    private static readonly string[] SecretKeys =
    [
        "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey", "cookie",
        "set-cookie", "x-auth-token", "token", "access_token", "refresh_token", "client_secret",
        "secret", "password", "pwd", "passwd", "credential", "credentials", "passphrase",
        "private_key",
    ];

    // The schemes that carry the credential directly after the scheme name.
    private static readonly string[] AuthSchemes = ["Basic", "Bearer", "Digest", "Negotiate", "Token"];

    private const string QuotedValue = "\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?";

    private static readonly Regex SecretPattern = BuildSecretPattern();

    private static readonly Regex SchemePattern = BuildSchemePattern();

    /// <summary>Redact every secret value in the text, then truncate the result.</summary>
    /// <param name="text">The text, which can be null.</param>
    /// <returns>The safe text, or null when the input was null.</returns>
    public static string? Sanitize(string? text)
    {
        if (text is null)
        {
            return null;
        }

        var redacted = SecretPattern.Replace(text, match => match.Groups[1].Value + "=" + Redacted);

        // A URL password is not a "key=value" pair. The host and the port stay, because an
        // operator needs them.
        redacted = CredentialMasking.MaskUrl(redacted);

        redacted = SchemePattern.Replace(redacted, match => match.Groups[1].Value + " " + Redacted);

        return redacted.Length <= MaxLength
            ? redacted
            : string.Concat(redacted.AsSpan(0, MaxLength - TruncationMarker.Length), TruncationMarker);
    }

    /// <summary>Name the exception and every cause below it, then redact the whole text.</summary>
    /// <param name="error">The exception.</param>
    /// <returns>The safe text.</returns>
    public static string? Sanitize(Exception error)
    {
        ArgumentNullException.ThrowIfNull(error);
        return Sanitize(DescribeChain(error));
    }

    // A driver puts the connection string in the message of the cause, not of the wrapper. The
    // chain therefore reaches the redaction, and no cause message escapes it.
    private static string DescribeChain(Exception error)
    {
        var parts = new StringBuilder();
        var current = error;
        var depth = 0;

        while (current is not null && depth < MaxCauseDepth)
        {
            if (depth > 0)
            {
                parts.Append(" | caused by ");
            }

            parts.Append(current.GetType().Name).Append(": ").Append(current.Message);

            var next = current.InnerException;
            current = ReferenceEquals(next, current) ? null : next;
            depth++;
        }

        return parts.ToString();
    }

    // A key writes its word separator as '-', '_' or '.', so every form of one key matches one
    // pattern. The key accepts a prefix, so "PGPASSWORD" matches, and it ends on a word
    // boundary, so "passwordless" does not.
    //
    // The prefix is BOUNDED. An unbounded run in front of a required literal makes the whole
    // pattern quadratic in the length of the text, because the engine scans to the end at every
    // start position. The redaction runs on every failure, and an error text can be long, so the
    // cost was also a denial of service. No real key carries a prefix of more than sixty four
    // characters.
    //
    // A ':' separator is the log, the YAML and the header notation, where a value can hold a
    // space, so the value runs to a comma, a semicolon, a brace, a bracket, an ampersand, a
    // quote or the end of the line. An '=' separator is the environment, the query and the
    // connection-string notation, where the value ends at the first whitespace. Both stop at a
    // semicolon, which is what bounds a .NET connection string value.
    private static Regex BuildSecretPattern()
    {
        var keys = string.Join("|", SecretKeys.Select(KeyAlternative));
        var schemePrefix = "(?:(?:" + string.Join("|", AuthSchemes) + ")\\s+)?";

        return new Regex(
            "([A-Za-z0-9_]{0,64}(?:" + keys + "))\\b\"?\\s*(?:" +
            ":+\\s*(?:" + QuotedValue + "|" + schemePrefix + "[^,;}\\]&\\n\"]*)" +
            "|" +
            "=+\\s*(?:" + QuotedValue + "|" + schemePrefix + "[^\\s,;}\\]&\\n\"]*)" +
            ")",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    }

    // Matches a bare authentication scheme and the token that follows it.
    //
    // The token must look like a credential, or an ordinary sentence loses a word. "Token" is
    // both a scheme name and an English word, so "the token bucket is empty" must stay whole. A
    // credential is long, or it carries a digit or an '='. "bucket" is neither. The sixteen
    // character line keeps "Digest authentication failed" readable.
    private static Regex BuildSchemePattern() => new(
        "\\b(" + string.Join("|", AuthSchemes) + ")\\s+" +
        "(?=[A-Za-z0-9._~+/\\-]*[0-9=]|[A-Za-z0-9._~+/\\-]{16})" +
        "[A-Za-z0-9._~+/\\-]+=*",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private static string KeyAlternative(string key) =>
        string.Join("[-_.]?", key.Split('-', '_', '.').Select(Regex.Escape));
}
