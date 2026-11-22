using System.Text.Json;
using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class DesignTests
{
    [Fact]
    public void ProblemsTakePrecedenceOverBlocking()
    {
        foreach (var blocked in new[] { false, true })
        {
            Assert.Equal(TrayState.Alert, TrayPresentation.Evaluate(false, blocked, false, false, false));
            Assert.Equal(TrayState.Alert, TrayPresentation.Evaluate(true, blocked, true, false, false));
            Assert.Equal(TrayState.Alert, TrayPresentation.Evaluate(true, blocked, false, true, false));
            Assert.Equal(TrayState.Alert, TrayPresentation.Evaluate(true, blocked, false, false, true));
        }
        Assert.Equal(TrayState.Focus, TrayPresentation.Evaluate(true, true, false, false, false));
        Assert.Equal(TrayState.Free, TrayPresentation.Evaluate(true, false, false, false, false));
    }

    [Fact]
    public void MarkGeometryKeepsThreeCyclesAndAccessibleShapeDifferences()
    {
        var app = CadenceMark.App;
        Assert.Equal(6, app.Arcs.Count);
        Assert.Equal(30, app.Radius);
        Assert.Equal(9, app.Stroke);
        Assert.Equal(14, app.DiamondSize);
        Assert.Equal(-90, app.Arcs[0].Start);
        Assert.Equal(150, app.Arcs[4].Start);
        Assert.InRange(app.Arcs.Sum(a => a.Sweep), 330, 332);
        var focus = CadenceMark.Tray(TrayState.Focus);
        var free = CadenceMark.Tray(TrayState.Free);
        var alert = CadenceMark.Tray(TrayState.Alert);
        Assert.Equal(3, focus.Arcs.Count);
        Assert.Equal(focus.Arcs.Select(a => (a.Start, a.Sweep)), free.Arcs.Select(a => (a.Start, a.Sweep)));
        Assert.Equal(4, focus.Stroke);
        Assert.Equal(2.6, free.Stroke);
        Assert.Equal(0, focus.DiamondStroke);
        Assert.Equal(1.6, free.DiamondStroke);
        Assert.InRange(focus.Arcs.Sum(a => a.Sweep), 308, 309);
        Assert.InRange(Assert.Single(alert.Arcs).Sweep, 229, 230);
        foreach (var geometry in new[] { app, focus, free, alert })
        {
            Assert.All(geometry.Arcs, arc => Assert.InRange(arc.Sweep, 0, 360));
            Assert.True(geometry.Arcs[^1].Start + geometry.Arcs[^1].Sweep < 270);
        }
    }

    private static string Schedule(string start = "19:00", string end = "22:00", int[]? weekdays = null) =>
        JsonSerializer.Serialize(new { schemaVersion = 1, timeZone = "Asia/Tokyo", patterns = new[] {
            new { id = "synthetic", name = "合成の予定", enabled = true, weekdays = weekdays ?? [1, 2, 3, 4, 5, 6, 7], start, end,
                segments = new[] { new { kind = "free", minutes = 10 }, new { kind = "block", minutes = 20 } } }
        } });

    [Theory]
    [InlineData("2026-01-05T19:20:00+09:00", true, 1.0 / 9)]
    [InlineData("2026-01-05T18:00:00+09:00", false, 0)]
    public void SelectsCurrentOrFirstUpcomingWindow(string instant, bool current, double fraction)
    {
        var json = Schedule();
        var now = DateTimeOffset.Parse(instant);
        var timeline = Assert.IsType<TodayTimeline>(TodayTimeline.Build(json, now));
        Assert.Equal(DateTimeOffset.Parse("2026-01-05T19:00:00+09:00"), timeline.Start);
        Assert.Equal(12, timeline.Segments.Count);
        if (current) Assert.Equal(fraction, timeline.NowFraction!.Value, 8);
        else Assert.Null(timeline.NowFraction);
        AssertCoverageAndCore(json, timeline);
    }

    [Fact]
    public void NoWindowAfterEndOrOnAnotherWeekdayOrWithoutTimetable()
    {
        var now = DateTimeOffset.Parse("2026-01-05T22:00:00+09:00");
        Assert.Null(TodayTimeline.Build(Schedule(), now));
        Assert.Null(TodayTimeline.Build(Schedule(weekdays: [2]), now.AddHours(-2)));
        Assert.Null(TodayTimeline.Build(null, now));
        Assert.Null(TodayTimeline.Build("{}", now));
    }

    [Fact]
    public void OvernightWindowIncludesYesterdayAndFreeTail()
    {
        var json = Schedule("23:30", "00:40", [1]);
        var timeline = Assert.IsType<TodayTimeline>(TodayTimeline.Build(json, DateTimeOffset.Parse("2026-01-06T00:35:00+09:00")));
        Assert.Equal(DateTimeOffset.Parse("2026-01-05T23:30:00+09:00"), timeline.Start);
        Assert.Equal(DateTimeOffset.Parse("2026-01-06T00:40:00+09:00"), timeline.End);
        Assert.False(timeline.Segments[^1].Blocked);
        Assert.Equal(TimeSpan.FromMinutes(10), timeline.Segments[^1].End - timeline.Segments[^1].Start);
        AssertCoverageAndCore(json, timeline);
    }

    [Fact]
    public void UsesCoreMinimumLengthForTruncatedLastBlock()
    {
        var json = Schedule("19:00", "19:20");
        var timeline = Assert.IsType<TodayTimeline>(TodayTimeline.Build(json, DateTimeOffset.Parse("2026-01-05T19:15:00+09:00")));
        Assert.False(Assert.Single(timeline.Segments).Blocked);
        AssertCoverageAndCore(json, timeline);
    }

    private static void AssertCoverageAndCore(string json, TodayTimeline timeline)
    {
        Assert.Equal(timeline.Start, timeline.Segments[0].Start);
        Assert.Equal(timeline.End, timeline.Segments[^1].End);
        for (var i = 1; i < timeline.Segments.Count; i++) Assert.Equal(timeline.Segments[i - 1].End, timeline.Segments[i].Start);
        var timetable = TimetableParser.Parse(json).Timetable!;
        foreach (var segment in timeline.Segments)
        {
            Assert.True(segment.End > segment.Start);
            Assert.Equal(segment.Blocked, TimetableExpander.IsBlockedAt(timetable, segment.Start));
            Assert.Equal(segment.Blocked, TimetableExpander.IsBlockedAt(timetable, segment.End.AddTicks(-1)));
        }
    }

    [Theory]
    [InlineData("2026-01-05T11:30:00Z", "集中の時間です（20:30 まで）")]
    [InlineData(null, "集中の時間です")]
    public void SuccessfulNotificationUsesExecutableTitleAndJapanBlockEnd(string? end, string expected)
    {
        var notices = new List<(string Title, string Body, bool Warning)>();
        var reporter = new ProcessNotifications(new FakeClock(), _ => { }, (t, b, w) => notices.Add((t, b, w)));
        reporter.Success("game.exe", end is null ? null : DateTimeOffset.Parse(end));
        reporter.Success("GAME.EXE");
        var notice = Assert.Single(notices);
        Assert.Equal("game.exe を終了しました", notice.Title);
        Assert.Equal(expected, notice.Body);
        Assert.False(notice.Warning);
    }
}
