using System.Globalization;
using System.Text.Json;
using Cadence.Core;

namespace Cadence.Tray.Logic;

public enum TrayState { Focus, Free, Alert }

public static class TrayPresentation
{
    public static TrayState Evaluate(bool hasTimetable, bool blocked, bool authProblem, bool syncProblem, bool storageProblem) =>
        !hasTimetable || authProblem || syncProblem || storageProblem ? TrayState.Alert : blocked ? TrayState.Focus : TrayState.Free;
}

public sealed record MarkArc(double Start, double Sweep, bool Focus);
public sealed record MarkGeometry(double ViewBox, double Center, double Radius, double Stroke,
    double DiamondSize, double DiamondRadius, double DiamondStroke, IReadOnlyList<MarkArc> Arcs);

public static class CadenceMark
{
    // SVG dash lengths are absolute lengths, not multiples of the pen width.
    public static MarkGeometry App { get; } = new(100, 50, 30, 9, 14, 1.5, 0,
        Enumerable.Range(0, 3).SelectMany(i => new[] {
            new MarkArc(-90 + i * 120, Degrees(38.9, 30), true),
            new MarkArc(-90 + i * 120 + Degrees(41.4, 30), Degrees(18.93, 30), false)
        }).ToArray());

    public static MarkGeometry Tray(TrayState state) => new(32, 16, 10, state == TrayState.Free ? 2.6 : 4,
        6, 0.8, state == TrayState.Free ? 1.6 : 0,
        state == TrayState.Alert ? [new(-90, Degrees(40, 10), true)] :
        Enumerable.Range(0, 3).Select(i => new MarkArc(-90 + i * 120, Degrees(17.94, 10), state == TrayState.Focus)).ToArray());

    private static double Degrees(double length, double radius) => length / (2 * Math.PI * radius) * 360;
}

public sealed record TimelineSegment(DateTimeOffset Start, DateTimeOffset End, bool Blocked);
public sealed record TodayTimeline(DateTimeOffset Start, DateTimeOffset End, IReadOnlyList<TimelineSegment> Segments, double? NowFraction)
{
    private static readonly TimeSpan Japan = TimeSpan.FromHours(9);

    // Read only window metadata from the validated document. Core remains the sole
    // source of block intervals, including its minimum-length and clipping rules.
    public static TodayTimeline? Build(string? json, DateTimeOffset now)
    {
        var parsed = TimetableParser.Parse(json);
        if (parsed.Timetable is not { } timetable) return null;
        using var document = JsonDocument.Parse(json!);
        var today = now.ToOffset(Japan).Date;
        var windows = new List<(DateTimeOffset Start, DateTimeOffset End)>();
        foreach (var pattern in document.RootElement.GetProperty("patterns").EnumerateArray())
        {
            if (!pattern.GetProperty("enabled").GetBoolean()) continue;
            var start = TimeSpan.ParseExact(pattern.GetProperty("start").GetString()!, @"hh\:mm", CultureInfo.InvariantCulture);
            var end = TimeSpan.ParseExact(pattern.GetProperty("end").GetString()!, @"hh\:mm", CultureInfo.InvariantCulture);
            for (var date = today.AddDays(-1); date <= today; date = date.AddDays(1))
            {
                var weekday = ((int)date.DayOfWeek + 6) % 7 + 1;
                if (!pattern.GetProperty("weekdays").EnumerateArray().Any(day => day.GetInt32() == weekday)) continue;
                var from = new DateTimeOffset(date, Japan).Add(start);
                var to = new DateTimeOffset(date, Japan).Add(end);
                if (to <= from) to = to.AddDays(1);
                if (from <= now && now < to || from >= now && from.Date == today) windows.Add((from, to));
            }
        }
        if (windows.Count == 0) return null;
        var window = windows.OrderBy(w => w.Start).First();
        var segments = new List<TimelineSegment>();
        var cursor = window.Start;
        foreach (var interval in TimetableExpander.Expand(timetable, window.Start, window.End))
        {
            var from = interval.Start > cursor ? interval.Start : cursor;
            var to = interval.End < window.End ? interval.End : window.End;
            if (to <= cursor) continue;
            if (from > cursor) segments.Add(new(cursor, from, false));
            segments.Add(new(from, to, true));
            cursor = to;
        }
        if (cursor < window.End) segments.Add(new(cursor, window.End, false));
        return new(window.Start, window.End, segments,
            now >= window.Start && now < window.End ? (now - window.Start).TotalSeconds / (window.End - window.Start).TotalSeconds : null);
    }
}
