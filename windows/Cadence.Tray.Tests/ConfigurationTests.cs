using System.Net;
using System.Text;
using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class ConfigurationTests : IDisposable
{
    private const string Valid = """{"apiKey":"synthetic-key","projectId":"synthetic-project"}""";
    private readonly string directory = Path.Combine(Path.GetTempPath(), "cadence-config-tests-" + Guid.NewGuid().ToString("N"));
    private string ConfigPath => Path.Combine(directory, "AppData", "Roaming", "Cadence", "cadence-firebase.json");
    private string MisplacedPath => Path.Combine(directory, "AppData", "Cadence", "cadence-firebase.json");

    private void Write(string path, string contents)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, contents);
    }

    [Fact]
    public void MissingFileShowsAbsoluteReadPathAndSafeCode()
    {
        var result = FirebaseConfig.Load(ConfigPath, MisplacedPath);
        Assert.Equal(FirebaseConfigStatus.Missing, result.Status);
        Assert.False(result.Usable);
        Assert.Null(result.Config);
        Assert.Equal("config_missing", result.Code);
        Assert.Equal(Path.GetFullPath(ConfigPath), result.Path);
        Assert.Contains(result.Path, result.Message);
        Assert.Null(result.PlacementHint);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public void MissingFileDetectsCommonPlacementMistakes(bool textExtension, bool directlyUnderAppData)
    {
        if (textExtension) Write(ConfigPath + ".txt", Valid);
        if (directlyUnderAppData) Write(MisplacedPath, Valid);
        var result = FirebaseConfig.Load(ConfigPath, MisplacedPath);
        Assert.Equal(FirebaseConfigStatus.Missing, result.Status);
        Assert.Null(result.Config);
        Assert.Equal(textExtension, result.Message.Contains("拡張子が .txt"));
        Assert.Equal(directlyUnderAppData, result.Message.Contains(@"AppData\Roaming\Cadence に置いてください"));
        Assert.Contains(ConfigPath, result.Message);
    }

    [Fact]
    public void ReadFailureIsNotReportedAsMissing()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        // A directory at the file path cannot be read as a file, on Windows or Unix.
        Directory.CreateDirectory(ConfigPath);
        var result = FirebaseConfig.Load(ConfigPath, MisplacedPath);
        Assert.Equal(FirebaseConfigStatus.Unreadable, result.Status);
        Assert.Equal("config_unreadable", result.Code);
        Assert.Contains(ConfigPath, result.Message);
        Assert.Null(result.Config);
    }

    [Fact]
    public void InvalidJsonIncludesLocationButNeverValuesOrExceptionText()
    {
        Write(ConfigPath, "{\n\"apiKey\":\"synthetic-key\",\n\"projectId\":synthetic-project\"\n}");
        var result = FirebaseConfig.Load(ConfigPath);
        Assert.Equal(FirebaseConfigStatus.InvalidJson, result.Status);
        Assert.Equal("config_invalid_json", result.Code);
        Assert.Equal(2L, result.JsonLine);
        Assert.NotNull(result.JsonBytePosition);
        Assert.Contains("3 行目", result.Message);
        Assert.Contains("バイト位置", result.Message);
        Assert.Contains(ConfigPath, result.Message);
        Assert.DoesNotContain("synthetic-key", result.Message);
        Assert.DoesNotContain("synthetic-project", result.Message);
        Assert.Null(result.Config);
    }

    [Theory]
    [InlineData("{}", "apiKey がありません、projectId がありません")]
    [InlineData("{\"apiKey\":\"synthetic-key\"}", "projectId がありません")]
    [InlineData("{\"projectId\":\"synthetic-project\"}", "apiKey がありません")]
    [InlineData("{\"apiKey\":42,\"projectId\":false}", "apiKey が文字列ではありません、projectId が文字列ではありません")]
    [InlineData("{\"apiKey\":null,\"projectId\":{}}", "apiKey が文字列ではありません、projectId が文字列ではありません")]
    [InlineData("{\"apiKey\":\"\",\"projectId\":\"  \"}", "apiKey が空です、projectId が空です")]
    [InlineData("[]", "ルートを JSON オブジェクトにしてください")]
    [InlineData("null", "ルートを JSON オブジェクトにしてください")]
    public void InvalidKeysDescribeOnlyFieldNamesAndProblems(string json, string problem)
    {
        Write(ConfigPath, json);
        var result = FirebaseConfig.Load(ConfigPath);
        Assert.Equal(FirebaseConfigStatus.InvalidKeys, result.Status);
        Assert.Equal("config_missing_keys", result.Code);
        Assert.Equal(problem, result.KeyProblem);
        Assert.False(result.Usable);
        Assert.Null(result.Config);
        Assert.Contains(problem, result.Message);
        Assert.Contains(ConfigPath, result.Message);
        Assert.DoesNotContain("synthetic-key", result.Message);
        Assert.DoesNotContain("synthetic-project", result.Message);
    }

    [Fact]
    public void ValidFileWinsOverMisplacedCopies()
    {
        Write(ConfigPath, Valid);
        Write(ConfigPath + ".txt", "invalid");
        Write(MisplacedPath, "invalid");
        var result = FirebaseConfig.Load(ConfigPath, MisplacedPath);
        Assert.Equal(FirebaseConfigStatus.Ready, result.Status);
        Assert.True(result.Usable);
        Assert.Equal(new FirebaseConfig("synthetic-key", "synthetic-project"), result.Config);
        Assert.Equal("config_ready", result.Code);
        Assert.Contains(ConfigPath, result.Message);
        Assert.Null(result.PlacementHint);
        Assert.DoesNotContain("synthetic-key", result.Message);
        Assert.DoesNotContain("synthetic-project", result.Message);
    }

    [Fact]
    public void ValidFileWithUtf8BomCanBeRead()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        File.WriteAllText(ConfigPath, Valid, new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
        var result = FirebaseConfig.Load(ConfigPath);
        Assert.True(result.Usable);
        Assert.Equal(new FirebaseConfig("synthetic-key", "synthetic-project"), result.Config);
    }

    [Theory]
    [InlineData(false, false, true)]
    [InlineData(false, true, true)]
    [InlineData(true, false, true)]
    [InlineData(true, true, false)]
    public void StartupOpensSettingsOnlyWhenSetupIsNeeded(bool configured, bool savedSession, bool expected)
    {
        Assert.Equal(expected, StartupSettings.ShouldOpen(configured, savedSession));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ReloadRepairsConfigAndEnablesSignInWithoutLosingCache(bool invalidJson)
    {
        if (invalidJson) Write(ConfigPath, "{");
        var clock = new FakeClock();
        var files = new LocalFiles(Path.GetDirectoryName(ConfigPath)!);
        var cache = new TimetableCache(Samples.Timetable("basic"), clock.Now.AddHours(-1));
        files.SaveCache(cache);
        var cacheBytes = File.ReadAllBytes(files.PathFor("timetable.json"));
        var sessions = new MemorySessions { Value = null };
        var logs = new List<string>();
        using var service = new SyncService(null, sessions, files.LoadCache(), files.SaveCache, clock, logs.Add);
        var timetable = service.State.Timetable;
        Assert.False(service.HasSavedSession);
        Assert.NotNull(await service.SignInAsync("example@example.invalid", "synthetic-password"));
        var first = await service.ReloadConfigurationAsync(ConfigPath, MisplacedPath);
        Assert.False(first.Usable);
        Assert.False(service.Configured);

        Write(ConfigPath, Valid);
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Login);
        http.Enqueue(HttpStatusCode.ServiceUnavailable, "{}");
        var result = await service.ReloadConfigurationAsync(ConfigPath, MisplacedPath, config => new(config, clock, http));
        Assert.True(result.Usable);
        Assert.True(service.Configured);
        Assert.Null(await service.SignInAsync("example@example.invalid", "synthetic-password"));
        Assert.True(service.HasSavedSession);
        Assert.Equal(2, http.Calls);
        Assert.Same(timetable, service.State.Timetable);
        Assert.Equal(cache.FetchedAt, service.State.LastSync);
        Assert.True(TimetableExpander.IsBlockedAt(service.State.Timetable!, DateTimeOffset.Parse("2026-01-05T10:15:00Z")));
        Assert.Equal(cacheBytes, File.ReadAllBytes(files.PathFor("timetable.json")));
        Assert.Equal(new[] { invalidJson ? "config_invalid_json" : "config_missing", "config_ready",
            "sync_failed reason=http_503", "sync_retry_scheduled" }, logs);
    }

    [Fact]
    public async Task ReloadWaitsForSyncPreservesSessionAndReplacesClientAndToken()
    {
        var clock = new FakeClock();
        var oldHttp = new FakeHttp();
        var reply = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        oldHttp.Replies.Enqueue(_ => reply.Task);
        oldHttp.Enqueue(HttpStatusCode.Forbidden, "{}");
        var sessions = new MemorySessions();
        using var service = new SyncService(new(new("synthetic-old-key", "synthetic-old-project"), clock, oldHttp),
            sessions, new(Samples.Timetable("basic"), clock.Now), _ => Assert.Fail("Cache must not be rewritten"), clock);
        var timetable = service.State.Timetable;
        Write(ConfigPath, Valid);
        var newHttp = new FakeHttp();
        newHttp.Replies.Enqueue(request =>
        {
            Assert.Equal("securetoken.googleapis.com", request.RequestUri!.Host);
            Assert.Equal("?key=synthetic-key", request.RequestUri.Query);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(Samples.Refresh) });
        });
        newHttp.Enqueue(HttpStatusCode.Forbidden, "{}");
        var sync = service.SyncAsync();
        var reload = service.ReloadConfigurationAsync(ConfigPath, MisplacedPath, config => new(config, clock, newHttp));
        Assert.False(reload.IsCompleted);
        reply.SetResult(new(HttpStatusCode.OK) { Content = new StringContent(Samples.Refresh) });
        await Task.WhenAll(sync, reload);
        Assert.True(oldHttp.Disposed);
        Assert.Equal("synthetic-next", sessions.Value!.RefreshToken);
        Assert.True(service.HasSavedSession);
        Assert.Same(timetable, service.State.Timetable);
        await service.SyncAsync();
        Assert.Equal(2, oldHttp.Calls);
        Assert.Equal(2, newHttp.Calls);
        Assert.Contains("/projects/synthetic-project/", newHttp.Requests[1].Path);
        Assert.Same(timetable, service.State.Timetable);
        Assert.False(service.TakeAuthNotification());

        File.Delete(ConfigPath);
        var saved = sessions.Value;
        await service.ReloadConfigurationAsync(ConfigPath, MisplacedPath);
        Assert.True(newHttp.Disposed);
        Assert.False(service.Configured);
        Assert.Same(saved, sessions.Value);
        Assert.True(service.HasSavedSession);
        Assert.Same(timetable, service.State.Timetable);
        await service.SyncAsync();
        Assert.Equal(2, newHttp.Calls);
        Assert.False(service.TakeAuthNotification());
    }

    public void Dispose()
    {
        if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
    }
}
