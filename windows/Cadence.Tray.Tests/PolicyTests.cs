using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class PolicyTests
{
    [Theory]
    [InlineData("basic", "2026-01-05T10:00:00Z", false, "2026-01-05T10:10:00Z")]
    [InlineData("basic", "2026-01-05T10:10:00Z", true, "2026-01-05T10:30:00Z")]
    [InlineData("basic", "2026-01-05T10:30:00Z", false, "2026-01-05T10:40:00Z")]
    [InlineData("do-not-merge-windows", "2026-01-04T16:00:00Z", true, "2026-01-06T15:00:00Z")]
    [InlineData("do-not-merge-patterns", "2026-01-05T10:00:00Z", true, "2026-01-05T11:00:00Z")]
    [InlineData("disabled", "2026-01-05T10:00:00Z", false, null)]
    public void NextTransitionMergesTouchingIntervals(string fixture, string now, bool blocked, string? next)
    {
        var clock = new FakeClock { Now = DateTimeOffset.Parse(now) };
        var status = ScheduleStatus.Evaluate(TimetableParser.Parse(Samples.Timetable(fixture)).Timetable, clock);
        Assert.Equal(blocked, status.Blocked);
        Assert.Equal(next is null ? (DateTimeOffset?)null : DateTimeOffset.Parse(next), status.NextChange);
    }

    [Fact]
    public void AlwaysBlockedDoesNotInventTransitionAtHorizon()
    {
        const string json = """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[{"id":"p","name":"合成","enabled":true,"weekdays":[1,2,3,4,5,6,7],"start":"00:00","end":"00:00","segments":[{"kind":"block","minutes":720}]}]}""";
        var status = ScheduleStatus.Evaluate(TimetableParser.Parse(json).Timetable, new FakeClock());
        Assert.True(status.Blocked);
        Assert.Null(status.NextChange);
        Assert.Equal(new BlockStatus(false, null), ScheduleStatus.Evaluate(null, new FakeClock()));
    }

    [Fact]
    public void DisplayUsesJapanAndFitsTooltip()
    {
        var clock = new FakeClock();
        Assert.Equal("次 19:00", ScheduleStatus.NextText(clock.Now, clock));
        Assert.Equal("次 火 19:00", ScheduleStatus.NextText(clock.Now.AddDays(1), clock));
        Assert.Equal("2026/01/05 19:00", ScheduleStatus.JapanTime(clock.Now.ToOffset(TimeSpan.FromHours(-8))));
        Assert.True(ScheduleStatus.Tooltip(new string('あ', 200), new(false, clock.Now), clock.Now, clock).Length <= 127);
    }

    [Theory]
    [InlineData(" Game ", "Game.exe")]
    [InlineData("Game.EXE", "Game.EXE")]
    [InlineData("game.exe.exe", "game.exe.exe")]
    [InlineData("", null)]
    [InlineData("   ", null)]
    [InlineData(null, null)]
    [InlineData("C:\\game.exe", null)]
    [InlineData("folder/game", null)]
    public void NormalizesExecutableNames(string? input, string? expected) => Assert.Equal(expected, ExecutableNames.Normalize(input));

    [Fact]
    public void DuplicateAndMatchingNamesIgnoreCase()
    {
        var names = new List<string>();
        Assert.True(ExecutableNames.Add(names, " Game "));
        Assert.False(ExecutableNames.Add(names, "game.EXE"));
        Assert.True(ExecutableNames.Matches(names[0], "GAME"));
        Assert.False(ExecutableNames.Matches(names[0], "other"));
        Assert.Equal("Game.exe", Assert.Single(names));
    }

    [Fact]
    public void NotificationsAreLimitedPerExecutableAtExactMinuteBoundary()
    {
        var clock = new FakeClock();
        var limiter = new NotificationLimiter(clock);
        Assert.True(limiter.Allow("Game.exe"));
        Assert.False(limiter.Allow("GAME.EXE"));
        Assert.True(limiter.Allow("Another.exe"));
        clock.Now = clock.Now.AddSeconds(59);
        Assert.False(limiter.Allow("game.exe"));
        clock.Now = clock.Now.AddSeconds(1);
        Assert.True(limiter.Allow("game.exe"));
    }

    [Theory]
    [InlineData(-301, false)]
    [InlineData(-300, true)]
    [InlineData(-299, true)]
    [InlineData(0, true)]
    public void RefreshStartsFiveMinutesBeforeExpiry(int relativeSeconds, bool expected)
    {
        var clock = new FakeClock();
        var expires = clock.Now;
        clock.Now = expires.AddSeconds(relativeSeconds);
        Assert.Equal(expected, new AuthToken("id", "refresh", "user", expires).NeedsRefresh(clock));
    }

    [Theory]
    [InlineData("{")]
    [InlineData("null")]
    [InlineData("[]")]
    [InlineData("{}")]
    [InlineData("{\"apiKey\":\"synthetic\"}")]
    [InlineData("{\"apiKey\":3,\"projectId\":true}")]
    [InlineData("{\"apiKey\":\" \",\"projectId\":\"synthetic\"}")]
    public void InvalidConfigurationNeverEscapes(string json)
    {
        var path = Path.GetTempFileName();
        try { File.WriteAllText(path, json); Assert.Null(FirebaseConfig.Load(path)); }
        finally { File.Delete(path); }
        Assert.Null(FirebaseConfig.Load(path));
    }

    [Fact]
    public void ValidConfigurationAndAtomicCacheRoundTrip()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        var files = new LocalFiles(directory);
        try
        {
            AtomicFile.WriteJson(files.PathFor("config.json"), new { apiKey = "synthetic", projectId = "synthetic-project" });
            Assert.Equal(new FirebaseConfig("synthetic", "synthetic-project"), FirebaseConfig.Load(files.PathFor("config.json")));
            var cache = new TimetableCache(Samples.Empty, new FakeClock().Now);
            files.SaveCache(cache);
            Assert.Equal(cache, files.LoadCache());
            files.SaveCache(cache with { Json = "{}" });
            Assert.Null(files.LoadCache());
            files.SaveExecutables([" game ", "GAME.exe", "a/b", "Other.EXE"]);
            Assert.Equal(new[] { "game.exe", "Other.EXE" }, files.LoadExecutables());
            Assert.Empty(Directory.GetFiles(directory, "*.tmp"));
        }
        finally { Directory.Delete(directory, recursive: true); }
    }
}
