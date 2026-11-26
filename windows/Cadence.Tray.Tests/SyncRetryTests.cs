using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class SyncRetryTests
{
    private sealed class Scenario : IDisposable
    {
        public FakeClock Clock { get; } = new();
        public FakeHttp Http { get; } = new();
        public List<string> Logs { get; } = new();
        public SyncService Service { get; }
        public Scenario(CancellationToken shutdown = default)
        {
            Http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
            Service = new(new(new("synthetic", "synthetic-project"), Clock, Http), new MemorySessions(),
                new(Samples.Timetable("basic"), Clock.Now), _ => { }, Clock, Logs.Add, shutdown: shutdown);
        }
        public void Failure() => Http.Replies.Enqueue(_ => throw new HttpRequestException());
        public void Success() => Http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        public async Task AdvanceRetry()
        {
            Clock.Advance(TimeSpan.FromSeconds(10));
            await Service.RetryCompletion.WaitAsync(TimeSpan.FromSeconds(5));
        }
        public void Dispose() => Service.Dispose();
    }

    [Theory]
    [InlineData(null)]
    [InlineData("サーバーに時間割がありません")]
    public async Task FirstFailurePreservesPreviousProblemAndRetriesOnlyAfterTenSeconds(string? previousProblem)
    {
        using var s = new Scenario();
        if (previousProblem is not null) s.Service.State.SetProblem(previousProblem);
        s.Failure();
        var timetable = s.Service.State.Timetable;
        await s.Service.SyncAsync();
        Assert.Equal(previousProblem, s.Service.State.Problem);
        Assert.Same(timetable, s.Service.State.Timetable);
        Assert.Equal(2, s.Http.Calls);
        Assert.Equal(new[] { "sync_failed reason=connect", "sync_retry_scheduled" }, s.Logs);
        s.Success();
        s.Clock.Advance(TimeSpan.FromSeconds(9));
        Assert.Equal(2, s.Http.Calls);
        s.Clock.Advance(TimeSpan.FromSeconds(1));
        await s.Service.RetryCompletion.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(3, s.Http.Calls);
        Assert.Null(s.Service.State.Problem);
        Assert.Contains("sync_retry_succeeded", s.Logs);
    }

    [Fact]
    public async Task SecondFailureShowsProblemAndDoesNotChainRetries()
    {
        using var s = new Scenario();
        s.Failure();
        s.Failure();
        await s.Service.SyncAsync();
        await s.AdvanceRetry();
        Assert.Equal("同期できません（次の周期で再試行）", s.Service.State.Problem);
        Assert.Contains("sync_retry_failed reason=connect", s.Logs);
        s.Clock.Advance(TimeSpan.FromSeconds(50));
        Assert.Equal(3, s.Http.Calls);
        Assert.Single(s.Logs, line => line == "sync_retry_scheduled");
        s.Success();
        await s.Service.SyncAsync();
        Assert.Null(s.Service.State.Problem);
    }

    [Fact]
    public async Task ValidSuccessResetsConsecutiveFailureCount()
    {
        using var s = new Scenario();
        s.Failure();
        s.Success();
        await s.Service.SyncAsync();
        await s.AdvanceRetry();
        s.Failure();
        await s.Service.SyncAsync();
        Assert.Null(s.Service.State.Problem);
        s.Failure();
        await s.AdvanceRetry();
        Assert.Contains("同期できません", s.Service.State.Problem);
    }

    [Theory]
    [InlineData(404, "サーバーに時間割がありません")]
    [InlineData(403, "時間割を読む権限がありません")]
    [InlineData(401, "同期できません（次の周期で再試行）")]
    [InlineData(200, "時間割を読み込めません: invalid_document")]
    public async Task NonTransientResultsPreserveBehaviorResetCountAndNeverScheduleRetry(int status, string problem)
    {
        using var s = new Scenario();
        s.Failure();
        await s.Service.SyncAsync();
        if (status == 401)
        {
            s.Http.Enqueue(HttpStatusCode.Unauthorized, "{}");
            s.Http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        }
        s.Http.Enqueue((HttpStatusCode)status, "{");
        await s.Service.SyncAsync();
        Assert.Equal(problem, s.Service.State.Problem);
        Assert.True(s.Service.HasSavedSession);
        var calls = s.Http.Calls;
        await s.AdvanceRetry();
        Assert.Equal(calls, s.Http.Calls);
        Assert.Single(s.Logs, line => line == "sync_retry_scheduled");
        s.Failure();
        await s.Service.SyncAsync();
        Assert.Equal(problem, s.Service.State.Problem);
        if (status == 200) Assert.Contains("sync_failed reason=parse", s.Logs);
    }

    [Theory]
    [InlineData("timeout", "timeout")]
    [InlineData("socket_dns", "dns")]
    [InlineData("http_dns", "dns")]
    [InlineData("socket_connect", "connect")]
    [InlineData("http_connect", "connect")]
    [InlineData("parse", "parse")]
    [InlineData("other", "other")]
    [InlineData("disposed", "other")]
    public async Task ExceptionsAreClassifiedWithoutLoggingPrivateDetails(string kind, string reason)
    {
        Exception failure = kind switch
        {
            "timeout" => new TaskCanceledException("synthetic-private-message"),
            "socket_dns" => new HttpRequestException("synthetic-private-message", new SocketException((int)SocketError.HostNotFound)),
            "http_dns" => new HttpRequestException(HttpRequestError.NameResolutionError),
            "socket_connect" => new SocketException((int)SocketError.ConnectionRefused),
            "http_connect" => new HttpRequestException(),
            "parse" => new JsonException("synthetic-private-message"),
            "disposed" => new ObjectDisposedException("synthetic-private-object"),
            _ => new IOException("synthetic-private-message")
        };
        using var s = new Scenario();
        s.Http.Replies.Enqueue(_ => throw failure);
        await s.Service.SyncAsync();
        Assert.Equal(new[] { "sync_failed reason=" + reason, "sync_retry_scheduled" }, s.Logs);
        Assert.Null(s.Service.State.Problem);
    }

    [Theory]
    [InlineData(503, false)]
    [InlineData(429, false)]
    [InlineData(503, true)]
    [InlineData(429, true)]
    public async Task TransientHttpFailuresIncludingRefreshAreRetried(int status, bool refreshing)
    {
        using var s = new Scenario();
        if (refreshing) s.Http.Replies.Clear();
        s.Http.Enqueue((HttpStatusCode)status, "{}");
        await s.Service.SyncAsync();
        Assert.Null(s.Service.State.Problem);
        Assert.Contains("sync_failed reason=http_" + status, s.Logs);
        Assert.DoesNotContain("token_refresh_failed", s.Logs);
        if (refreshing) s.Http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        s.Success();
        await s.AdvanceRetry();
        Assert.Null(s.Service.State.Problem);
        Assert.Contains("sync_retry_succeeded", s.Logs);
    }

    [Theory]
    [InlineData("INVALID_REFRESH_TOKEN", "サインインが必要です")]
    [InlineData("UNKNOWN", "認証の設定を確認してください（次の周期で再試行）")]
    public async Task AuthenticationFailuresResetCountAndDoNotRetry(string code, string problem)
    {
        using var s = new Scenario();
        s.Http.Replies.Clear();
        s.Failure();
        await s.Service.SyncAsync();
        s.Http.Enqueue(HttpStatusCode.BadRequest, "{\"error\":{\"message\":\"" + code + "\"}}");
        await s.Service.SyncAsync();
        Assert.Equal(problem, s.Service.State.Problem);
        await s.AdvanceRetry();
        Assert.Equal(2, s.Http.Calls);
        if (code == "UNKNOWN")
        {
            s.Failure();
            await s.Service.SyncAsync();
            Assert.Equal(problem, s.Service.State.Problem);
        }
        else Assert.True(s.Service.TakeAuthNotification());
    }

    [Theory]
    [InlineData("signed_out", null)]
    [InlineData("INVALID_REFRESH_TOKEN", null)]
    [InlineData("UNKNOWN", null)]
    [InlineData("missing_document", "サーバーに時間割がありません")]
    public async Task SuccessfulSignInClearsOnlyOldAuthenticationProblemBeforeTransientFetchFailure(string origin, string? expectedProblem)
    {
        using var s = new Scenario();
        if (origin == "signed_out") await s.Service.SignOutAsync();
        else
        {
            if (origin == "missing_document") s.Http.Enqueue(HttpStatusCode.NotFound, "{}");
            else
            {
                s.Http.Replies.Clear();
                s.Http.Enqueue(HttpStatusCode.BadRequest, "{\"error\":{\"message\":\"" + origin + "\"}}");
            }
            await s.Service.SyncAsync();
        }
        Assert.NotNull(s.Service.State.Problem);
        s.Http.Replies.Clear();
        s.Http.Enqueue(HttpStatusCode.OK, Samples.Login);
        s.Failure();
        Assert.Null(await s.Service.SignInAsync("example@example.invalid", "synthetic-password"));
        Assert.Null(s.Service.AuthProblem);
        Assert.Equal(expectedProblem, s.Service.State.Problem);
        Assert.False(s.Service.TakeAuthNotification());
        Assert.False(s.Service.RetryCompletion.IsCompleted);
        s.Success();
        await s.AdvanceRetry();
        Assert.Null(s.Service.State.Problem);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ShutdownOrDisposeCancelsSignInRetryWithoutCallerToken(bool shutdown)
    {
        using var lifetime = new CancellationTokenSource();
        using var s = new Scenario(lifetime.Token);
        s.Http.Replies.Clear();
        s.Http.Enqueue(HttpStatusCode.OK, Samples.Login);
        s.Failure();
        Assert.Null(await s.Service.SignInAsync("example@example.invalid", "synthetic-password"));
        Assert.False(s.Service.RetryCompletion.IsCompleted);
        Assert.Contains("sync_retry_scheduled", s.Logs);
        var logsBeforeShutdown = s.Logs.ToArray();
        if (shutdown) lifetime.Cancel();
        else s.Service.Dispose();
        s.Success();
        await s.AdvanceRetry();
        Assert.Equal(2, s.Http.Calls);
        Assert.Equal(logsBeforeShutdown, s.Logs);
        Assert.Single(s.Http.Replies);
    }

    [Fact]
    public async Task SignOutCancelsScheduledRetry()
    {
        using var s = new Scenario();
        s.Failure();
        await s.Service.SyncAsync();
        await s.Service.SignOutAsync();
        await s.AdvanceRetry();
        Assert.Equal(2, s.Http.Calls);
        Assert.False(s.Service.HasSavedSession);
        Assert.Equal("サインインが必要です", s.Service.State.Problem);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ShutdownOrDisposeCancelsScheduledRetry(bool shutdown)
    {
        using var s = new Scenario();
        using var cancellation = new CancellationTokenSource();
        s.Failure();
        await s.Service.SyncAsync(cancellation.Token);
        if (shutdown) cancellation.Cancel();
        else s.Service.Dispose();
        await s.AdvanceRetry();
        Assert.Equal(2, s.Http.Calls);
        Assert.DoesNotContain(s.Logs, line => line.StartsWith("sync_retry_failed"));
    }

    [Fact]
    public async Task CallerCancellationDoesNotCountAsFailureOrScheduleRetry()
    {
        using var s = new Scenario();
        using var cancellation = new CancellationTokenSource();
        s.Http.Replies.Enqueue(_ => { cancellation.Cancel(); throw new TaskCanceledException(); });
        await s.Service.SyncAsync(cancellation.Token);
        Assert.Empty(s.Logs);
        Assert.Null(s.Service.State.Problem);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task PeriodicTickAndRetryDoNotFetchTwiceWhenTheyOverlap(bool retryFirst)
    {
        using var s = new Scenario();
        s.Failure();
        await s.Service.SyncAsync();
        var pendingRetry = s.Service.RetryCompletion;
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var reply = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        s.Http.Replies.Enqueue(_ => { entered.SetResult(); return reply.Task; });
        Task periodic;
        if (retryFirst)
        {
            s.Clock.Advance(TimeSpan.FromSeconds(10));
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            periodic = s.Service.SyncAsync();
        }
        else
        {
            periodic = s.Service.SyncAsync();
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            s.Clock.Advance(TimeSpan.FromSeconds(10));
        }
        Assert.True(s.Service.IsSynchronizing);
        Assert.Equal(3, s.Http.Calls);
        reply.SetResult(new(HttpStatusCode.OK) { Content = new StringContent(Samples.Typed(Samples.Empty)) });
        await Task.WhenAll(periodic, pendingRetry).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(3, s.Http.Calls);
        Assert.Null(s.Service.State.Problem);
    }

    [Fact]
    public async Task RetryBecomingDueDuringPeriodicFetchSkipsBusyGateWithoutCancellingFetch()
    {
        using var s = new Scenario();
        s.Failure();
        await s.Service.SyncAsync();
        var pendingRetry = s.Service.RetryCompletion;
        var reply = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        s.Http.Replies.Enqueue(_ => reply.Task);
        var periodic = s.Service.SyncAsync();
        Assert.True(s.Service.IsSynchronizing);
        // This reservation must still be waiting, not cancelled by the periodic tick.
        Assert.False(pendingRetry.IsCompleted);
        s.Clock.Advance(TimeSpan.FromSeconds(10));
        await pendingRetry.WaitAsync(TimeSpan.FromSeconds(5));
        // The retry completed while the periodic fetch still holds the gate.
        Assert.False(periodic.IsCompleted);
        Assert.True(s.Service.IsSynchronizing);
        Assert.Equal(3, s.Http.Calls);
        reply.SetResult(new(HttpStatusCode.OK) { Content = new StringContent(Samples.Typed(Samples.Empty)) });
        await periodic.WaitAsync(TimeSpan.FromSeconds(5));
        s.Clock.Advance(TimeSpan.FromSeconds(60));
        Assert.Equal(3, s.Http.Calls);
        Assert.Null(s.Service.State.Problem);
        Assert.Single(s.Logs, line => line == "sync_retry_scheduled");
        Assert.DoesNotContain(s.Logs, line => line.StartsWith("sync_retry_failed"));
    }

}
