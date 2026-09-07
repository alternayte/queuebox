using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace QueueBox.Inbox.Tests;

/// <summary>
/// Runs the shared redaction corpus. See <c>clients/redaction-corpus.json</c>.
/// <para>
/// QueueBox and every client library redact the same text in the same way. A library that
/// redacts almost the same as the others is a security defect, not a difference of idiom, so the
/// cases live in one file that all of them read.
/// </para>
/// </summary>
public sealed class RedactionCorpusTest
{
    private static readonly RedactionCorpus Corpus = Load();

    public static TheoryData<string> CaseNames()
    {
        var names = new TheoryData<string>();

        foreach (var name in Corpus.Cases.Select(one => one.Name))
        {
            names.Add(name);
        }

        return names;
    }

    [Theory]
    [MemberData(nameof(CaseNames))]
    public void TheSharedCaseHolds(string name)
    {
        var one = Corpus.Cases.Single(candidate => candidate.Name == name);
        var result = ErrorSanitizer.Sanitize(one.Input) ?? string.Empty;

        if (one.Expected is not null)
        {
            Assert.Equal(one.Expected, result);
        }

        // A case that omits the member arrives with a null list, whatever the initialiser says,
        // because the source generated deserialiser does not run it.
        foreach (var secret in one.MustNotContain ?? [])
        {
            Assert.DoesNotContain(secret, result, StringComparison.Ordinal);
        }

        foreach (var kept in one.MustContain ?? [])
        {
            Assert.Contains(kept, result, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void TheCorpusStatesTheSameMaximumLength() => Assert.Equal(ErrorSanitizer.MaxLength, Corpus.MaxLength);

    [Fact]
    public void TheCorpusHoldsCases() => Assert.NotEmpty(Corpus.Cases);

    [Fact]
    public void TheRedactionStaysLinearInTheLengthOfTheText()
    {
        // The key prefix and the scheme name were unbounded, so the patterns were quadratic and a
        // five thousand character message cost hundreds of milliseconds. The redaction runs on
        // every failure, and the length of an error text is not ours to choose, so the cost was
        // also a denial of service. This bound is generous: the quadratic version needed minutes.
        var text = "amqp://user:aa  bb@rabbit:5672/vh " + new string('x', 50_000);

        var started = Stopwatch.StartNew();
        ErrorSanitizer.Sanitize(text);
        started.Stop();

        Assert.True(
            started.Elapsed < TimeSpan.FromSeconds(5),
            $"The redaction took {started.Elapsed}, which means it is quadratic again.");
    }

    private static RedactionCorpus Load()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);

        while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, ".git")))
        {
            directory = directory.Parent;
        }

        var root = directory?.FullName ?? throw new InvalidOperationException("The repository root was not found.");
        var content = File.ReadAllText(Path.Combine(root, "clients", "redaction-corpus.json"));

        return JsonSerializer.Deserialize(content, CorpusContext.Default.RedactionCorpus)
            ?? throw new InvalidOperationException("The corpus did not parse.");
    }
}

/// <summary>One case of the shared corpus.</summary>
public sealed class RedactionCase
{
    [JsonPropertyName("name")]
    public string Name { get; init; } = string.Empty;

    [JsonPropertyName("input")]
    public string Input { get; init; } = string.Empty;

    [JsonPropertyName("expected")]
    public string? Expected { get; init; }

    [JsonPropertyName("mustNotContain")]
    public IReadOnlyList<string>? MustNotContain { get; init; }

    [JsonPropertyName("mustContain")]
    public IReadOnlyList<string>? MustContain { get; init; }
}

/// <summary>The shared corpus.</summary>
public sealed class RedactionCorpus
{
    [JsonPropertyName("maxLength")]
    public int MaxLength { get; init; }

    [JsonPropertyName("truncationMarker")]
    public string TruncationMarker { get; init; } = string.Empty;

    [JsonPropertyName("cases")]
    public IReadOnlyList<RedactionCase> Cases { get; init; } = [];
}

// Section 9A: the library consumes the System.Text.Json generator and writes none of its own.
[JsonSerializable(typeof(RedactionCorpus))]
[JsonSourceGenerationOptions(PropertyNameCaseInsensitive = true)]
internal sealed partial class CorpusContext : JsonSerializerContext;
