namespace Cadence.Core;

/// <summary>A validated, immutable timetable. Create through TimetableParser.Parse.</summary>
public sealed class Timetable
{
    internal IReadOnlyList<Pattern> Patterns { get; }

    internal Timetable(IEnumerable<Pattern> patterns) => Patterns = patterns.ToArray();
}

internal sealed record Pattern(string Id, bool Enabled, int[] Weekdays,
    int StartMinute, int DurationMinutes, Segment[] Segments);

internal sealed record Segment(bool IsBlock, int Minutes);

public sealed record ValidationResult(Timetable? Timetable, string? ErrorCode)
{
    public bool Valid => Timetable is not null;
}

/// <summary>A half-open interval. Expansion always returns UTC offsets.</summary>
public sealed record BlockInterval(DateTimeOffset Start, DateTimeOffset End);
