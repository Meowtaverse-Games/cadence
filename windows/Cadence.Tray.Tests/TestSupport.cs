using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using Cadence.Tray.Logic;

namespace Cadence.Tray.Tests;

internal sealed class FakeClock : TimeProvider
{
    public DateTimeOffset Now { get; set; } = DateTimeOffset.Parse("2026-01-05T10:00:00Z");
    public override DateTimeOffset GetUtcNow() => Now;
    private readonly List<ManualTimer> timers = new();
    public int PendingTimerCount { get { lock (timers) return timers.Count; } }
    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    {
        var timer = new ManualTimer(this, callback, state);
        lock (timers) timers.Add(timer);
        timer.Change(dueTime, period);
        return timer;
    }
    public void Advance(TimeSpan elapsed)
    {
        Now += elapsed;
        ManualTimer[] pending;
        lock (timers) pending = timers.ToArray();
        foreach (var timer in pending) timer.Fire();
    }
    private sealed class ManualTimer(FakeClock clock, TimerCallback callback, object? state) : ITimer
    {
        private DateTimeOffset? due;
        private TimeSpan period;
        public bool Change(TimeSpan dueTime, TimeSpan interval)
        {
            due = dueTime == Timeout.InfiniteTimeSpan ? null : clock.Now + dueTime;
            period = interval;
            return true;
        }
        public void Fire()
        {
            if (due is null || due > clock.Now) return;
            due = period == Timeout.InfiniteTimeSpan ? null : clock.Now + period;
            callback(state);
        }
        public void Dispose() { lock (clock.timers) clock.timers.Remove(this); }
        public ValueTask DisposeAsync() { Dispose(); return ValueTask.CompletedTask; }
    }
}

internal sealed class MemorySessions : ISessionStore
{
    public SavedSession? Value { get; set; } = new("synthetic-refresh", "example@example.invalid");
    public bool FailLoad { get; set; }
    public bool FailSave { get; set; }
    public SavedSession? Load() => FailLoad ? throw new IOException() : Value;
    public void Save(SavedSession? session)
    {
        if (FailSave) throw new IOException();
        Value = session;
    }
}

internal sealed class FakeHttp : HttpMessageHandler
{
    public Queue<Func<HttpRequestMessage, Task<HttpResponseMessage>>> Replies { get; } = new();
    public int Calls { get; private set; }
    public bool Disposed { get; private set; }
    public List<(string Host, string Path, string? Bearer)> Requests { get; } = new();
    public void Enqueue(HttpStatusCode status, string body) => Replies.Enqueue(_ => Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(body) }));
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Calls++;
        Requests.Add((request.RequestUri!.Host, request.RequestUri.AbsolutePath, request.Headers.Authorization?.Parameter));
        return Replies.Dequeue()(request);
    }
    protected override void Dispose(bool disposing)
    {
        Disposed = true;
        base.Dispose(disposing);
    }
}

internal static class Samples
{
    public const string Empty = """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[]}""";
    public const string Refresh = """{"id_token":"synthetic-id","refresh_token":"synthetic-next","user_id":"synthetic-user","expires_in":"3600"}""";
    public const string Login = """{"idToken":"synthetic-id","refreshToken":"synthetic-next","localId":"synthetic-user","expiresIn":"3600"}""";
    public static JsonElement Fixture(string name)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json")));
        return doc.RootElement.Clone();
    }
    public static string Timetable(string name) => Fixture(name).GetProperty("timetable").GetRawText();
    public static string Typed(string ordinary)
    {
        using var doc = JsonDocument.Parse(ordinary);
        return new JsonObject { ["fields"] = Fields(doc.RootElement) }.ToJsonString();
    }
    private static JsonObject Fields(JsonElement element)
    {
        var result = new JsonObject();
        foreach (var field in element.EnumerateObject()) result[field.Name] = Value(field.Value);
        return result;
    }
    private static JsonObject Value(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Object => new() { ["mapValue"] = new JsonObject { ["fields"] = Fields(value) } },
        JsonValueKind.Array => new() { ["arrayValue"] = new JsonObject { ["values"] = new JsonArray(value.EnumerateArray().Select(v => (JsonNode)Value(v)).ToArray()) } },
        JsonValueKind.String => new() { ["stringValue"] = value.GetString() },
        JsonValueKind.Number when value.TryGetInt64(out var integer) => new() { ["integerValue"] = integer.ToString(System.Globalization.CultureInfo.InvariantCulture) },
        JsonValueKind.Number => new() { ["doubleValue"] = JsonNode.Parse(value.GetRawText()) },
        JsonValueKind.True or JsonValueKind.False => new() { ["booleanValue"] = value.GetBoolean() },
        _ => new() { ["nullValue"] = null }
    };
}
