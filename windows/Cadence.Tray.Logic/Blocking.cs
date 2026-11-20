using System.Globalization;
using Cadence.Core;

namespace Cadence.Tray.Logic;

public static class ExecutableNames
{
    public static string? Normalize(string? input)
    {
        var name = input?.Trim();
        if (string.IsNullOrEmpty(name) || name.IndexOfAny(['/', '\\', ':', '\0']) >= 0) return null;
        return name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? name : name + ".exe";
    }

    public static bool Matches(string executable, string processName) =>
        string.Equals(executable, processName + ".exe", StringComparison.OrdinalIgnoreCase);

    public static bool Add(ICollection<string> names, string? input)
    {
        var name = Normalize(input);
        if (name is null || names.Contains(name, StringComparer.OrdinalIgnoreCase)) return false;
        names.Add(name);
        return true;
    }
}

public sealed class NotificationLimiter(TimeProvider clock)
{
    private readonly Dictionary<string, DateTimeOffset> last = new(StringComparer.OrdinalIgnoreCase);
    public bool Allow(string executable)
    {
        var now = clock.GetUtcNow();
        if (last.TryGetValue(executable, out var previous) && now - previous < TimeSpan.FromMinutes(1)) return false;
        last[executable] = now;
        return true;
    }
}

public sealed record BlockStatus(bool Blocked, DateTimeOffset? NextChange);

public static class ScheduleStatus
{
    public static BlockStatus Evaluate(Timetable? timetable, TimeProvider clock)
    {
        if (timetable is null) return new(false, null);
        var now = clock.GetUtcNow();
        var horizon = now.AddDays(14);
        var intervals = TimetableExpander.Expand(timetable, now, horizon);
        DateTimeOffset? end = null;
        foreach (var interval in intervals)
        {
            if (end is null)
            {
                if (interval.Start > now) return new(false, interval.Start);
                end = interval.End;
            }
            else if (interval.Start <= end) end = interval.End > end ? interval.End : end;
            else break;
        }
        // A continuous weekly schedule has no transition at the expansion horizon.
        return new(TimetableExpander.IsBlockedAt(timetable, now), end < horizon ? end : null);
    }

    public static string JapanTime(DateTimeOffset instant) =>
        instant.ToOffset(TimeSpan.FromHours(9)).ToString("yyyy/MM/dd HH:mm", CultureInfo.InvariantCulture);

    public static string NextText(DateTimeOffset? next, TimeProvider clock)
    {
        if (next is null) return "次の切替なし（14日以内）";
        var japan = next.Value.ToOffset(TimeSpan.FromHours(9));
        var today = clock.GetUtcNow().ToOffset(TimeSpan.FromHours(9)).Date;
        return "次 " + japan.ToString(japan.Date == today ? "HH:mm" : "ddd HH:mm", CultureInfo.GetCultureInfo("ja-JP"));
    }

    public static string Tooltip(string state, BlockStatus block, DateTimeOffset? syncedAt, TimeProvider clock)
    {
        state = state.Length > 65 ? state[..64] + "…" : state;
        var text = $"{state}\n{NextText(block.NextChange, clock)}\n同期 { (syncedAt is null ? "未取得" : JapanTime(syncedAt.Value))}";
        return text.Length <= 127 ? text : text[..126] + "…";
    }
}
