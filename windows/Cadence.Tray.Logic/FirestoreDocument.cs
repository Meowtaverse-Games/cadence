using System.Globalization;
using System.Text;
using System.Text.Json;

namespace Cadence.Tray.Logic;

public static class FirestoreDocument
{
    public static string? Convert(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            using var stream = new MemoryStream();
            using (var writer = new Utf8JsonWriter(stream))
            {
                var root = document.RootElement;
                if (root.ValueKind != JsonValueKind.Object) return null;
                WriteFields(writer, root);
            }
            return Encoding.UTF8.GetString(stream.ToArray());
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or FormatException or OverflowException or ArgumentException)
        {
            return null;
        }
    }

    private static void WriteFields(Utf8JsonWriter writer, JsonElement container)
    {
        if (container.ValueKind != JsonValueKind.Object) throw new FormatException();
        writer.WriteStartObject();
        if (container.TryGetProperty("fields", out var fields))
        {
            foreach (var field in fields.EnumerateObject())
            {
                writer.WritePropertyName(field.Name);
                WriteValue(writer, field.Value);
            }
        }
        writer.WriteEndObject();
    }

    private static void WriteValue(Utf8JsonWriter writer, JsonElement value)
    {
        var properties = value.EnumerateObject().ToArray();
        if (properties.Length != 1) throw new FormatException();
        var property = properties[0];
        var data = property.Value;
        switch (property.Name)
        {
            case "integerValue":
                writer.WriteNumberValue(long.Parse(data.GetString()!, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture));
                break;
            case "doubleValue":
                if (data.ValueKind != JsonValueKind.Number) throw new FormatException();
                // Preserve the JSON number, including fractions and integral doubles.
                data.WriteTo(writer);
                break;
            case "booleanValue": writer.WriteBooleanValue(data.GetBoolean()); break;
            case "stringValue":
                if (data.ValueKind != JsonValueKind.String) throw new FormatException();
                writer.WriteStringValue(data.GetString());
                break;
            case "nullValue":
                if (data.ValueKind != JsonValueKind.Null) throw new FormatException();
                writer.WriteNullValue();
                break;
            case "mapValue": WriteFields(writer, data); break;
            case "arrayValue":
                if (data.ValueKind != JsonValueKind.Object) throw new FormatException();
                writer.WriteStartArray();
                if (data.TryGetProperty("values", out var values))
                    foreach (var item in values.EnumerateArray()) WriteValue(writer, item);
                writer.WriteEndArray();
                break;
            default:
                // Metadata (timestamps, references, geopoints, future types) stays nonnumeric.
                writer.WriteStringValue(data.ValueKind == JsonValueKind.String ? data.GetString() : data.GetRawText());
                break;
        }
    }
}
