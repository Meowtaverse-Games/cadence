using System.Globalization;
using System.Text.Json;
using Cadence.Core;
using Xunit;

namespace Cadence.Core.Tests;

public sealed class FixtureTests
{
    public static IEnumerable<object[]> Cases()
    {
        var files = Directory.GetFiles(Path.Combine(AppContext.BaseDirectory, "Fixtures"), "*.json")
            .OrderBy(path => path, StringComparer.Ordinal).ToArray();
        Assert.NotEmpty(files);
        return files.Select(path => new object[] { Path.GetFileNameWithoutExtension(path) });
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
