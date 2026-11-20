using System.Text.Json;
using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class FirestoreTests
{
    public static IEnumerable<object[]> Fixtures() => Directory.GetFiles(Path.Combine(AppContext.BaseDirectory, "Fixtures"), "*.json")
        .Select(path => new object[] { Path.GetFileNameWithoutExtension(path) });

    [Theory]
    [MemberData(nameof(Fixtures))]
    public void TypedFixturesRetainValidationAndIntervals(string name)
    {
        var fixture = Samples.Fixture(name);
        var original = fixture.GetProperty("timetable").GetRawText();
        // Non-object roots are tested directly by the core; Firestore documents are maps.
        if (fixture.GetProperty("timetable").ValueKind != JsonValueKind.Object)
        {
            Assert.Null(FirestoreDocument.Convert(original));
            return;
        }
        var converted = FirestoreDocument.Convert(Samples.Typed(original));
        Assert.NotNull(converted);
        var before = TimetableParser.Parse(original);
        var after = TimetableParser.Parse(converted);
        Assert.Equal(before.Valid, after.Valid);
        Assert.Equal(before.ErrorCode, after.ErrorCode);
        if (!before.Valid) return;
        var from = fixture.GetProperty("from").GetDateTimeOffset();
        var to = fixture.GetProperty("to").GetDateTimeOffset();
        Assert.Equal(TimetableExpander.Expand(before.Timetable!, from, to), TimetableExpander.Expand(after.Timetable!, from, to));
    }

    [Fact]
    public void NestedEmptyAndMetadataValuesKeepTheirTypes()
    {
        const string typed = """
        {"fields":{"nested":{"mapValue":{"fields":{
          "array":{"arrayValue":{}},"map":{"mapValue":{}},"int":{"integerValue":"10"},
          "whole":{"doubleValue":10.0},"fraction":{"doubleValue":10.5},"flag":{"booleanValue":true},
          "nil":{"nullValue":null},"stamp":{"timestampValue":"2026-01-05T00:00:00Z"},
          "future":{"futureValue":{"something":1}}
        }}}}}
        """;
        using var doc = JsonDocument.Parse(FirestoreDocument.Convert(typed)!);
        var nested = doc.RootElement.GetProperty("nested");
        Assert.Empty(nested.GetProperty("array").EnumerateArray());
        Assert.Empty(nested.GetProperty("map").EnumerateObject());
        Assert.Equal(10, nested.GetProperty("int").GetInt32());
        Assert.Equal("10.0", nested.GetProperty("whole").GetRawText());
        Assert.Equal(10.5, nested.GetProperty("fraction").GetDouble());
        Assert.True(nested.GetProperty("flag").GetBoolean());
        Assert.Equal(JsonValueKind.Null, nested.GetProperty("nil").ValueKind);
        Assert.Equal(JsonValueKind.String, nested.GetProperty("stamp").ValueKind);
        Assert.Equal(JsonValueKind.String, nested.GetProperty("future").ValueKind);
    }

    [Theory]
    [InlineData("{\"integerValue\":\"10\"}", true)]
    [InlineData("{\"doubleValue\":10.0}", true)]
    [InlineData("{\"doubleValue\":10.5}", false)]
    [InlineData("{\"booleanValue\":true}", false)]
    [InlineData("{\"stringValue\":\"10\"}", false)]
    public void CoreReceivesNumbersWithoutCoercion(string replacement, bool valid)
    {
        var typed = Samples.Typed(Samples.Timetable("basic")).Replace("{\"integerValue\":\"10\"}", replacement);
        var result = TimetableParser.Parse(FirestoreDocument.Convert(typed));
        Assert.Equal(valid, result.Valid);
        if (!valid) Assert.Equal("segment_minutes", result.ErrorCode);
    }

    [Theory]
    [InlineData("{")]
    [InlineData("[]")]
    [InlineData("{\"fields\":[]}")]
    [InlineData("{\"fields\":{\"a\":{\"stringValue\":null}}}")]
    [InlineData("{\"fields\":{\"a\":{}}}")]
    [InlineData("{\"fields\":{\"a\":{\"integerValue\":\"1.5\"}}}")]
    [InlineData("{\"fields\":{\"a\":{\"integerValue\":true}}}")]
    [InlineData("{\"fields\":{\"a\":{\"doubleValue\":\"10\"}}}")]
    [InlineData("{\"fields\":{\"a\":{\"mapValue\":[]}}}")]
    [InlineData("{\"fields\":{\"a\":{\"arrayValue\":{\"values\":{}}}}}")]
    [InlineData("{\"fields\":{\"a\":{\"booleanValue\":1}}}")]
    [InlineData("{\"fields\":{\"a\":{\"stringValue\":\"x\",\"integerValue\":\"1\"}}}")]
    public void MalformedShapesAreRejected(string typed) => Assert.Null(FirestoreDocument.Convert(typed));
}
