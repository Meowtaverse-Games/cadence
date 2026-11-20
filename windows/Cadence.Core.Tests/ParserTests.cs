using Xunit;

namespace Cadence.Core.Tests;

public sealed class ParserTests
{
    [Theory]
    [InlineData("{", "invalid_json")]
    [InlineData("{\"schemaVersion\":1,}", "invalid_json")]
    [InlineData("null", "invalid_document")]
    [InlineData("[]", "invalid_document")]
    [InlineData("1", "invalid_document")]
    [InlineData("{\"schemaVersion\":1e999}", "schema_version")]
    public void InvalidJsonAndNonDocumentsReturnErrors(string json, string code)
    {
        var result = TimetableParser.Parse(json);
        Assert.False(result.Valid);
        Assert.Null(result.Timetable);
        Assert.Equal(code, result.ErrorCode);
    }
}
