using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Cadence.Core;
using Xunit;

namespace Cadence.Core.Tests;

public sealed class FixtureTests
{
    [Theory]
    [InlineData("segments-element-not-object")]
    [InlineData("segments-empty")]
    [InlineData("segments-null")]
    [InlineData("segments-not-array")]
    [InlineData("missing-pattern-segments")]
    public void AuditCountsMissingBlockDespiteMalformedSegments(string name)
    {
        var fixture = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json")))!;
        // Recreate the compound violation by enabling the otherwise unchanged fixture.
        fixture["timetable"]!["patterns"]![0]!["enabled"] = true;
        using var document = JsonDocument.Parse(fixture["timetable"]!.ToJsonString());
        var violations = FixtureRuleAudit.Violations(document.RootElement);
        Assert.Equal(2, violations.Count);
        Assert.Contains("segments", violations);
        Assert.Contains("missing_block", violations);
    }

    [Theory]
    [InlineData("minutes-fraction", "block_minutes")]
    [InlineData("minutes-zero", "block_minutes")]
    [InlineData("minutes-negative", "block_minutes")]
    [InlineData("kind-null", "missing_block")]
    [InlineData("kind-number", "missing_block")]
    [InlineData("kind-unknown", "missing_block")]
    [InlineData("missing-segment-kind", "missing_block")]
    public void AuditDetectsOriginalCompoundViolations(string name, string additionalCode)
    {
        var fixture = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json")))!;
        var segments = fixture["timetable"]!["patterns"]![0]!["segments"]!.AsArray();
        segments.RemoveAt(1);
        if (name.StartsWith("minutes-", StringComparison.Ordinal)) segments[0]!["kind"] = "block";
        using var document = JsonDocument.Parse(fixture["timetable"]!.ToJsonString());
        var violations = FixtureRuleAudit.Violations(document.RootElement);
        Assert.Equal(2, violations.Count);
        Assert.Contains(fixture["expected"]!["error"]!.GetValue<string>(), violations);
        Assert.Contains(additionalCode, violations);
    }

    public static IEnumerable<object[]> Cases()
    {
        var files = Directory.GetFiles(Path.Combine(AppContext.BaseDirectory, "Fixtures"), "*.json")
            .OrderBy(path => path, StringComparer.Ordinal).ToArray();
        Assert.NotEmpty(files);
        return files.Select(path => new object[] { Path.GetFileNameWithoutExtension(path) });
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public void SharedFixtureViolatesExactlyItsExpectedRule(string name)
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json")));
        var fixture = document.RootElement;
        var expected = fixture.GetProperty("expected");
        var violations = FixtureRuleAudit.Violations(fixture.GetProperty("timetable"));
        if (expected.GetProperty("valid").GetBoolean()) Assert.Empty(violations);
        else Assert.Equal(expected.GetProperty("error").GetString(), Assert.Single(violations));
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public void SharedFixtureMatchesSpecification(string name)
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json")));
        var fixture = document.RootElement;
        Assert.Equal(name, fixture.GetProperty("name").GetString());
        Assert.False(string.IsNullOrWhiteSpace(fixture.GetProperty("description").GetString()));
        var actual = TimetableParser.Parse(fixture.GetProperty("timetable").GetRawText());
        var expected = fixture.GetProperty("expected");
        Assert.Equal(expected.GetProperty("valid").GetBoolean(), actual.Valid);
        if (!actual.Valid)
        {
            Assert.Null(actual.Timetable);
            Assert.Equal(expected.GetProperty("error").GetString(), actual.ErrorCode);
            return;
        }
        Assert.Null(actual.ErrorCode);
        var intervals = TimetableExpander.Expand(actual.Timetable!, Instant(fixture, "from"), Instant(fixture, "to"));
        var expectedIntervals = expected.GetProperty("intervals").EnumerateArray()
            .Select(interval => new BlockInterval(Instant(interval, "start"), Instant(interval, "end"))).ToArray();
        Assert.Equal(expectedIntervals, intervals);
        Assert.All(intervals, interval =>
        {
            Assert.Equal(TimeSpan.Zero, interval.Start.Offset);
            Assert.Equal(TimeSpan.Zero, interval.End.Offset);
        });
    }

    private static DateTimeOffset Instant(JsonElement value, string property) =>
        DateTimeOffset.ParseExact(value.GetProperty(property).GetString()!, "yyyy-MM-dd'T'HH:mm:ss'Z'",
            CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal);
}
