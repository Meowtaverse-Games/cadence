using System.Diagnostics;
using System.Text.Json;
using Xunit;
using Xunit.Abstractions;

namespace Cadence.Core.Tests;

public sealed class ExpansionTests(ITestOutputHelper output)
{
    private const string DailyJson = """
        {"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[
          {"id":"p1","name":"合成例","enabled":true,"weekdays":[1,2,3,4,5,6,7],
           "start":"19:00","end":"20:00","segments":[{"kind":"block","minutes":25}]}]}
        """;
    private static readonly DateTimeOffset Monday = new(2026, 1, 5, 10, 0, 0, TimeSpan.Zero);
    private static Timetable Daily() => TimetableParser.Parse(DailyJson).Timetable!;

    [Fact]
    public void QueryInsideBlockReturnsWholeInterval()
    {
        var interval = Assert.Single(TimetableExpander.Expand(Daily(), Monday.AddMinutes(10), Monday.AddMinutes(20)));
        Assert.Equal(new BlockInterval(Monday, Monday.AddHours(1)), interval);
    }

    [Theory]
    [InlineData(-1, false)]
    [InlineData(0, true)]
    [InlineData(3599, true)]
    [InlineData(3600, false)]
    public void CurrentStateUsesExplicitInstantAndHalfOpenBounds(int seconds, bool expected) =>
        Assert.Equal(expected, TimetableExpander.IsBlockedAt(Daily(), Monday.AddSeconds(seconds)));

    [Fact]
    public void ExpansionWorksAcrossYearBoundaryForSixWeeks()
    {
        var start = new DateTimeOffset(2025, 12, 15, 0, 0, 0, TimeSpan.Zero);
        var intervals = TimetableExpander.Expand(Daily(), start, start.AddDays(42));
        Assert.Equal(42, intervals.Count);
        for (var i = 0; i < intervals.Count; i++)
            Assert.Equal(new BlockInterval(start.AddDays(i).AddHours(10), start.AddDays(i).AddHours(11)), intervals[i]);
    }

    [Fact]
    public void OffsetsOfQueryInstantsDoNotChangeExpansion()
    {
        var timetable = Daily();
        var expected = TimetableExpander.Expand(timetable, Monday, Monday.AddDays(2));
        var actual = TimetableExpander.Expand(timetable, Monday.ToOffset(TimeSpan.FromHours(-8)),
            Monday.AddDays(2).ToOffset(TimeSpan.FromHours(12)));
        Assert.Equal(expected, actual);
    }

    [Fact]
    public void EmptyRangeHasNoIntersection() =>
        Assert.Empty(TimetableExpander.Expand(Daily(), Monday, Monday));

    [Fact]
    public void ReversedRangeIsRejected() =>
        Assert.Throws<ArgumentException>(() => TimetableExpander.Expand(Daily(), Monday.AddHours(1), Monday));

    [Fact]
    public void TwentyPatternsWithTwentySegmentsExpandFourteenDaysWithinBudget()
    {
        // Every pattern owns a separate hour; reverse input order to also exercise sorting.
        var patterns = Enumerable.Range(0, 20).Reverse().Select(hour => new
        {
            id = $"p{hour}", name = "合成負荷例", enabled = true,
            weekdays = new[] { 1, 2, 3, 4, 5, 6, 7 },
            start = $"{hour:00}:00", end = $"{hour + 1:00}:00",
            segments = Enumerable.Range(0, 20).Select(index => new
            {
                kind = index % 2 == 0 ? "free" : "block", minutes = index % 2 == 0 ? 1 : 15
            }).ToArray()
        }).ToArray();
        var validation = TimetableParser.Parse(JsonSerializer.Serialize(new { schemaVersion = 1, timeZone = "Asia/Tokyo", patterns }));
        Assert.True(validation.Valid, validation.ErrorCode);
        var timetable = validation.Timetable!;
        var start = new DateTimeOffset(2026, 1, 5, 0, 0, 0, TimeSpan.FromHours(9));
        var end = start.AddDays(14);
        // Warm up JIT; measure expansion only, without parsing, assertions, or logging.
        for (var i = 0; i < 5; i++) TimetableExpander.Expand(timetable, start, end);
        var stopwatch = Stopwatch.StartNew();
        var intervals = TimetableExpander.Expand(timetable, start, end);
        stopwatch.Stop();
        output.WriteLine($"Expansion: {stopwatch.Elapsed.TotalMilliseconds:F3} ms (target 50 ms; CI limit 500 ms)");
        Assert.True(stopwatch.Elapsed < TimeSpan.FromMilliseconds(500), $"Expansion took {stopwatch.Elapsed.TotalMilliseconds:F3} ms");
        // Per hour: :01–:16, :17–:32, :33–:48; :49–:60 is only 11 minutes and is discarded.
        Assert.Equal(14 * 20 * 3, intervals.Count);
        for (var day = 0; day < 14; day++)
        for (var hour = 0; hour < 20; hour++)
        for (var segment = 0; segment < 3; segment++)
        {
            var blockStart = start.AddDays(day).AddHours(hour).AddMinutes(1 + segment * 16).ToUniversalTime();
            Assert.Equal(new BlockInterval(blockStart, blockStart.AddMinutes(15)), intervals[(day * 20 + hour) * 3 + segment]);
        }
    }
}
