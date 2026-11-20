using System.Text;
using System.Text.Json;

namespace Cadence.Core;

public static class TimetableParser
{
    /// <summary>Validates the entire document, including disabled patterns.
    /// Unknown fields are ignored. Error codes are documented in spec/fixtures/README.md.</summary>
    public static ValidationResult Parse(string json)
    {
        ArgumentNullException.ThrowIfNull(json);
        try
        {
            using var document = JsonDocument.Parse(json);
            return Validate(document.RootElement);
        }
        catch (JsonException)
        {
            return Invalid("invalid_json");
        }
    }

    private static ValidationResult Validate(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object) return Invalid("invalid_document");
        if (!Integer(Field(root, "schemaVersion"), 1, 1, out _)) return Invalid("schema_version");
        if (Text(Field(root, "timeZone")) != "Asia/Tokyo") return Invalid("time_zone");
        var patternArray = Field(root, "patterns");
        if (patternArray.ValueKind != JsonValueKind.Array || patternArray.GetArrayLength() > 20)
            return Invalid("patterns");

        var patterns = new List<Pattern>();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in patternArray.EnumerateArray())
        {
            var id = Text(Field(item, "id"));
            if (string.IsNullOrEmpty(id) || !ids.Add(id)) return Invalid("pattern_id");
            var name = Text(Field(item, "name"));
            if (name is null || name.EnumerateRunes().Count() is < 1 or > 40)
                return Invalid("pattern_name");
            var enabledValue = Field(item, "enabled");
            if (enabledValue.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                return Invalid("enabled");
            var enabled = enabledValue.GetBoolean();
            var weekdaysValue = Field(item, "weekdays");
            if (weekdaysValue.ValueKind != JsonValueKind.Array || weekdaysValue.GetArrayLength() == 0)
                return Invalid("weekdays");
            var weekdays = new HashSet<int>();
            foreach (var day in weekdaysValue.EnumerateArray())
                if (!Integer(day, 1, 7, out var weekday) || !weekdays.Add(weekday))
                    return Invalid("weekdays");
            if (!Time(Field(item, "start"), out var start) || !Time(Field(item, "end"), out var end))
                return Invalid("window_time");
            var segmentArray = Field(item, "segments");
            if (segmentArray.ValueKind != JsonValueKind.Array || segmentArray.GetArrayLength() is < 1 or > 20)
                return Invalid("segments");
            var segments = new List<Segment>();
            foreach (var segment in segmentArray.EnumerateArray())
            {
                var kind = Text(Field(segment, "kind"));
                if (kind is not ("free" or "block")) return Invalid("segment_kind");
                if (!Integer(Field(segment, "minutes"), 1, 720, out var minutes))
                    return Invalid("segment_minutes");
                if (kind == "block" && minutes < 15) return Invalid("block_minutes");
                segments.Add(new Segment(kind == "block", minutes));
            }
            if (enabled && !segments.Any(segment => segment.IsBlock)) return Invalid("missing_block");
            patterns.Add(new Pattern(id, enabled, weekdays.ToArray(), start,
                end > start ? end - start : end - start + 1440, segments.ToArray()));
        }
        if (Overlaps(patterns)) return Invalid("window_overlap");
        return new ValidationResult(new Timetable(patterns), null);
    }

    private static bool Overlaps(List<Pattern> patterns)
    {
        const int week = 7 * 1440;
        var windows = patterns.Where(pattern => pattern.Enabled)
            .SelectMany(pattern => pattern.Weekdays.Select(day =>
                (pattern.Id, Start: (day - 1) * 1440 + pattern.StartMinute,
                    End: (day - 1) * 1440 + pattern.StartMinute + pattern.DurationMinutes)))
            .ToArray();
        for (var i = 0; i < windows.Length; i++)
        for (var j = i + 1; j < windows.Length; j++)
        {
            if (windows[i].Id == windows[j].Id) continue;
            // Include the adjacent weeks so Sunday's overnight window meets Monday's window.
            for (var shift = -week; shift <= week; shift += week)
                if (windows[i].Start < windows[j].End + shift && windows[j].Start + shift < windows[i].End)
                    return true;
        }
        return false;
    }

    private static JsonElement Field(JsonElement value, string name) =>
        value.ValueKind == JsonValueKind.Object && value.TryGetProperty(name, out var field) ? field : default;

    private static string? Text(JsonElement value) =>
        value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static bool Integer(JsonElement value, int minimum, int maximum, out int result)
    {
        result = 0;
        if (value.ValueKind != JsonValueKind.Number) return false;
        var number = value.GetDouble();
        if (!double.IsFinite(number) || number != Math.Truncate(number) || number < minimum || number > maximum)
            return false;
        result = (int)number;
        return true;
    }

    private static bool Time(JsonElement value, out int minute)
    {
        minute = 0;
        var text = Text(value);
        // Exact ASCII HH:mm; no trimming, Unicode digits, or trailing newline.
        if (text is null || text.Length != 5 || text[2] != ':' ||
            text[0] is < '0' or > '2' || text[1] is < '0' or > '9' ||
            text[3] is < '0' or > '5' || text[4] is < '0' or > '9') return false;
        var hour = (text[0] - '0') * 10 + text[1] - '0';
        if (hour > 23) return false;
        minute = hour * 60 + (text[3] - '0') * 10 + text[4] - '0';
        return true;
    }

    private static ValidationResult Invalid(string code) => new(null, code);
}
