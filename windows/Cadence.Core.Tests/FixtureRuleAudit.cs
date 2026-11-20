using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Cadence.Core.Tests;

/// <summary>
/// Test-only audit: collects every applicable rule, without calling the production validator
/// or stopping at the first error. Missing/malformed containers have no child rules to evaluate.
/// </summary>
internal static class FixtureRuleAudit
{
    public static IReadOnlySet<string> Violations(JsonElement document)
    {
        var errors = new HashSet<string>(StringComparer.Ordinal);
        void Check(bool valid, string code) { if (!valid) errors.Add(code); }

        Check(Integer(Field(document, "schemaVersion"), 1, 1), "schema_version");
        Check(Text(Field(document, "timeZone")) == "Asia/Tokyo", "time_zone");
        var patterns = Field(document, "patterns");
        Check(patterns.ValueKind == JsonValueKind.Array && patterns.GetArrayLength() <= 20, "patterns");
        if (patterns.ValueKind != JsonValueKind.Array) return errors;

        var ids = new HashSet<string>(StringComparer.Ordinal);
        // Independent overlap oracle: mark each minute of a cyclic week by pattern index.
        var owners = Enumerable.Repeat(-1, 7 * 1440).ToArray();
        var index = -1;
        foreach (var pattern in patterns.EnumerateArray())
        {
            index++;
            Check(pattern.ValueKind == JsonValueKind.Object, "patterns");
            if (pattern.ValueKind != JsonValueKind.Object) continue;
            var id = Text(Field(pattern, "id"));
            Check(!string.IsNullOrEmpty(id) && ids.Add(id), "pattern_id");
            var name = Text(Field(pattern, "name"));
            Check(name is not null && name.EnumerateRunes().Count() is >= 1 and <= 40, "pattern_name");
            var enabled = Field(pattern, "enabled");
            Check(enabled.ValueKind is JsonValueKind.True or JsonValueKind.False, "enabled");

            var days = Field(pattern, "weekdays");
            var validDays = days.ValueKind == JsonValueKind.Array && days.GetArrayLength() > 0;
            var dayNumbers = new HashSet<int>();
            if (days.ValueKind == JsonValueKind.Array)
                foreach (var day in days.EnumerateArray())
                {
                    if (!Integer(day, 1, 7)) validDays = false;
                    else if (!dayNumbers.Add((int)day.GetDouble())) validDays = false;
                }
            Check(validDays, "weekdays");
            var start = Minute(Field(pattern, "start"));
            var end = Minute(Field(pattern, "end"));
            Check(start.HasValue && end.HasValue, "window_time");

            var segments = Field(pattern, "segments");
            Check(segments.ValueKind == JsonValueKind.Array && segments.GetArrayLength() is >= 1 and <= 20, "segments");
            if (segments.ValueKind == JsonValueKind.Array)
            {
                var objectsOnly = true;
                var hasBlock = false;
                foreach (var segment in segments.EnumerateArray())
                {
                    Check(segment.ValueKind == JsonValueKind.Object, "segments");
                    if (segment.ValueKind != JsonValueKind.Object)
                    {
                        objectsOnly = false;
                        continue;
                    }
                    var kind = Text(Field(segment, "kind"));
                    Check(kind is "free" or "block", "segment_kind");
                    hasBlock |= kind == "block";
                    var minutes = Field(segment, "minutes");
                    Check(Integer(minutes, 1, 720), "segment_minutes");
                    // Numeric values can violate the block minimum independently of integrality/range.
                    if (kind == "block" && minutes.ValueKind == JsonValueKind.Number)
                        Check(minutes.GetDouble() >= 15, "block_minutes");
                }
                if (enabled.ValueKind == JsonValueKind.True && objectsOnly && segments.GetArrayLength() > 0)
                    Check(hasBlock, "missing_block");
            }

            // Only window prerequisites are needed: segment/name/id validity must not hide overlaps.
            if (enabled.ValueKind == JsonValueKind.True && validDays && start.HasValue && end.HasValue)
            {
                var duration = (end.Value - start.Value + 1440) % 1440;
                if (duration == 0) duration = 1440;
                foreach (var day in dayNumbers)
                for (var offset = 0; offset < duration; offset++)
                {
                    var minute = ((day - 1) * 1440 + start.Value + offset) % owners.Length;
                    Check(owners[minute] == -1 || owners[minute] == index, "window_overlap");
                    owners[minute] = index;
                }
            }
        }
        return errors;
    }

    private static JsonElement Field(JsonElement value, string name) =>
        value.TryGetProperty(name, out var field) ? field : default;

    private static string? Text(JsonElement value) =>
        value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static bool Integer(JsonElement value, int min, int max)
    {
        if (value.ValueKind != JsonValueKind.Number) return false;
        var number = value.GetDouble();
        return double.IsFinite(number) && number == Math.Truncate(number) && number >= min && number <= max;
    }

    private static int? Minute(JsonElement value)
    {
        var text = Text(value);
        if (text is null || !Regex.IsMatch(text, @"\A([01][0-9]|2[0-3]):[0-5][0-9]\z")) return null;
        return int.Parse(text[..2], System.Globalization.CultureInfo.InvariantCulture) * 60
            + int.Parse(text[3..], System.Globalization.CultureInfo.InvariantCulture);
    }
}
