using System.Globalization;
using Cadence.Core;

namespace Cadence.Tray.Logic;

public sealed record ActivityEntry(DateTimeOffset At, TrayState State, string Message, string? Executable = null);

public static class TodayActivity
{
    private static readonly TimeSpan Japan = TimeSpan.FromHours(9);

    // The date is supplied by the caller from the daily file name, never the machine zone.
    public static IReadOnlyList<ActivityEntry> Parse(IEnumerable<string> lines, DateOnly fileDate)
    {
        var entries = new List<ActivityEntry>();
        const string prefix = " process_terminated exe=";
        foreach (var line in lines)
        {
            if (line.Length <= 8 + prefix.Length || !line.AsSpan(8).StartsWith(prefix, StringComparison.Ordinal) ||
                !TimeOnly.TryParseExact(line[..8], "HH:mm:ss", CultureInfo.InvariantCulture, DateTimeStyles.None, out var time)) continue;
            var name = line[(8 + prefix.Length)..];
            if (name.Any(char.IsControl) || !name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
                ExecutableNames.Normalize(name) != name) continue;
            entries.Add(new(new DateTimeOffset(fileDate.ToDateTime(time), Japan), TrayState.Focus, name + " を終了しました", name));
        }
        return entries;
    }

    public static IReadOnlyList<ActivityEntry> Build(IEnumerable<ActivityEntry> logged, Timetable? timetable, DateTimeOffset now)
    {
        var start = new DateTimeOffset(now.ToOffset(Japan).Date, Japan);
        var end = start.AddDays(1);
        var entries = logged.Where(e => e.At >= start && e.At < end && e.At <= now).ToList();
        if (timetable is not null)
        {
            // Include both adjacent days so a block ending at midnight is not lost;
            // merge touching intervals to avoid a spurious FREE/FOCUS pair.
            var merged = new List<BlockInterval>();
            foreach (var interval in TimetableExpander.Expand(timetable, start.AddDays(-1), end.AddDays(1)).OrderBy(i => i.Start))
            {
                if (merged.Count > 0 && interval.Start <= merged[^1].End)
                    merged[^1] = new(merged[^1].Start, interval.End > merged[^1].End ? interval.End : merged[^1].End);
                else merged.Add(interval);
            }
            foreach (var interval in merged)
            {
                if (interval.Start >= start && interval.Start < end && interval.Start <= now)
                    entries.Add(new(interval.Start, TrayState.Focus, "集中の時間が始まりました"));
                if (interval.End >= start && interval.End < end && interval.End <= now)
                    entries.Add(new(interval.End, TrayState.Free, "自由時間になりました"));
            }
        }
        return entries.OrderByDescending(e => e.At).ThenBy(e => e.Message, StringComparer.Ordinal).Take(8).ToArray();
    }

    public static int Count(IEnumerable<ActivityEntry> logged, string executable, DateTimeOffset now) =>
        logged.Count(e => e.At.ToOffset(Japan).Date == now.ToOffset(Japan).Date && e.At <= now &&
            string.Equals(e.Executable, executable, StringComparison.OrdinalIgnoreCase));
}
