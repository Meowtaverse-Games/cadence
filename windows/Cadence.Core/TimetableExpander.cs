namespace Cadence.Core;

public static class TimetableExpander
{
    private static readonly TimeSpan JapanOffset = TimeSpan.FromHours(9);

    /// <summary>Returns whole UTC block intervals intersecting [from, to), sorted by start.
    /// Empty ranges return no intervals; reversed ranges throw ArgumentException.</summary>
    public static IReadOnlyList<BlockInterval> Expand(Timetable timetable, DateTimeOffset from, DateTimeOffset to)
    {
        ArgumentNullException.ThrowIfNull(timetable);
        if (from > to) throw new ArgumentException("The range end must not precede its start.", nameof(to));
        if (from == to) return Array.Empty<BlockInterval>();

        var firstDate = from.ToOffset(JapanOffset).Date.AddDays(-1);
        var lastDate = to.ToOffset(JapanOffset).Date;
        var result = new List<BlockInterval>();
        for (var date = firstDate; ; date = date.AddDays(1))
        {
            var weekday = ((int)date.DayOfWeek + 6) % 7 + 1;
            foreach (var pattern in timetable.Patterns)
            {
                if (!pattern.Enabled || !pattern.Weekdays.Contains(weekday)) continue;
                var windowStart = new DateTimeOffset(date, JapanOffset).AddMinutes(pattern.StartMinute);
                var windowEnd = windowStart.AddMinutes(pattern.DurationMinutes);
                if (windowStart >= to || windowEnd <= from) continue;

                DateTimeOffset? blockStart = null;
                var cursor = windowStart;
                var segmentIndex = 0;
                while (cursor < windowEnd)
                {
                    var segment = pattern.Segments[segmentIndex];
                    var end = cursor.AddMinutes(Math.Min(segment.Minutes, (windowEnd - cursor).TotalMinutes));
                    if (segment.IsBlock)
                        blockStart ??= cursor;
                    else if (blockStart is not null)
                    {
                        AddBlock(blockStart.Value, cursor);
                        blockStart = null;
                    }
                    cursor = end;
                    segmentIndex = (segmentIndex + 1) % pattern.Segments.Length;
                }
                if (blockStart is not null) AddBlock(blockStart.Value, windowEnd);
            }
            if (date == lastDate) break;
        }
        result.Sort((left, right) => left.Start.CompareTo(right.Start));
        return result.AsReadOnly();

        void AddBlock(DateTimeOffset start, DateTimeOffset end)
        {
            // Merge within a window before testing the minimum length or the query range.
            if (end - start >= TimeSpan.FromMinutes(15) && start < to && end > from)
                result.Add(new BlockInterval(start.ToUniversalTime(), end.ToUniversalTime()));
        }
    }

    /// <summary>Evaluates an explicitly supplied instant, without reading the system clock.</summary>
    public static bool IsBlockedAt(Timetable timetable, DateTimeOffset now) =>
        Expand(timetable, now, now.AddTicks(1)).Count != 0;
}
