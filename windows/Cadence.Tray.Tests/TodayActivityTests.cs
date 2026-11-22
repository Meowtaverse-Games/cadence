using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class TodayActivityTests
{
    [Fact]
    public void ParsesOnlyCompleteSuccessLinesAndPreservesNamesWithSpaces()
    {
        var result = TodayActivity.Parse(["19:01:02 process_terminated exe=Sample Game.exe",
            "25:00:00 process_terminated exe=bad.exe", "broken", "19:00:00 process_termination_failed pid=123 exe=bad.exe",
            "19:00:00 process_terminated exe=C:\\bad.exe", "19:00:00 process_terminated exe=bad.exe\r", "19:00:00 process_terminated exe="], new(2026, 1, 5));
        var entry = Assert.Single(result);
        Assert.Equal("Sample Game.exe", entry.Executable);
        Assert.Equal(DateTimeOffset.Parse("2026-01-05T19:01:02+09:00"), entry.At);
    }

    [Fact]
    public void UsesJapanDayExcludesFutureAndCountsBeyondEightVisibleEntries()
    {
        var now = DateTimeOffset.Parse("2026-01-05T15:00:10Z");
        var today = TodayActivity.Parse(Enumerable.Range(0, 10).Select(i => $"00:00:{i:00} process_terminated exe=game.exe"), new(2026, 1, 6));
        var others = TodayActivity.Parse(["23:59:59 process_terminated exe=game.exe"], new(2026, 1, 5));
        var future = TodayActivity.Parse(["00:00:11 process_terminated exe=game.exe"], new(2026, 1, 6));
        var logs = today.Concat(others).Concat(future).ToArray();
        var result = TodayActivity.Build(logs, null, now);
        Assert.Equal(8, result.Count);
        Assert.Equal(now.AddSeconds(-1), result[0].At);
        Assert.Equal(now.AddSeconds(-8), result[^1].At);
        Assert.Equal(10, TodayActivity.Count(logs, "GAME.EXE", now));
    }

    [Fact]
    public void MidnightEndIsIncludedWithoutInventingStartAtMidnight()
    {
        const string json = """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[{"id":"test","name":"合成","enabled":true,"weekdays":[1],"start":"23:00","end":"00:00","segments":[{"kind":"block","minutes":60}]}]}""";
        var result = TodayActivity.Build([], TimetableParser.Parse(json).Timetable, DateTimeOffset.Parse("2026-01-06T00:00:00+09:00"));
        Assert.Equal("自由時間になりました", Assert.Single(result).Message);
    }

    [Fact]
    public void TouchingBlocksDoNotGenerateFalseTransitions()
    {
        const string json = """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[{"id":"test","name":"合成","enabled":true,"weekdays":[1],"start":"19:00","end":"20:00","segments":[{"kind":"block","minutes":20}]}]}""";
        var result = TodayActivity.Build([], TimetableParser.Parse(json).Timetable, DateTimeOffset.Parse("2026-01-05T19:50:00+09:00"));
        Assert.Equal("集中の時間が始まりました", Assert.Single(result).Message);
    }

    [Fact]
    public void SuccessLoggingIsNotThrottledWithNotificationsAndMissingLogIsHarmless()
    {
        var lines = new List<string>(); var notifications = 0;
        var reporter = new ProcessNotifications(new FakeClock(), lines.Add, (_, _, _) => notifications++);
        reporter.Success("sample.exe"); reporter.Success("sample.exe");
        Assert.Equal(2, lines.Count); Assert.Equal(1, notifications);
        Assert.All(lines, line => Assert.Equal("process_terminated exe=sample.exe", line));
        Assert.Empty(new DailyLog(Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()), new FakeClock()).Read(DateTimeOffset.UtcNow));
    }
}
