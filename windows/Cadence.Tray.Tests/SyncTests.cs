using System.Net;
using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class SyncTests
{
    public static IEnumerable<object[]> Results()
    {
        foreach (var cached in new[] { true, false })
        foreach (var status in new[] { 200, 400, 401, 403, 404, 429, 500, 503 })
            yield return new object[] { cached, status };
    }

    [Theory]
    [MemberData(nameof(Results))]
    public void FetchStatusSelectsValidTimetableAndProblem(bool cached, int status)
    {
        var clock = new FakeClock();
        var cache = new TimetableCache(Samples.Timetable("basic"), clock.Now.AddHours(-1));
        var state = new SyncState(cached ? cache : null);
        var previous = state.Timetable;
        var result = state.Apply((HttpStatusCode)status, Samples.Typed(Samples.Empty), clock);
        if (status == 200)
        {
            Assert.NotNull(result);
            Assert.NotNull(state.Timetable);
            Assert.NotSame(previous, state.Timetable);
            Assert.Empty(TimetableExpander.Expand(state.Timetable!, clock.Now, clock.Now.AddDays(14)));
            Assert.Equal(clock.Now, state.LastSync);
            Assert.Null(state.Problem);
        }
        else
        {
            Assert.Null(result);
            Assert.Same(previous, state.Timetable);
            Assert.Equal(cached ? cache.FetchedAt : (DateTimeOffset?)null, state.LastSync);
            Assert.Equal(status switch
            {
                403 => "時間割を読む権限がありません",
                404 => "サーバーに時間割がありません",
                _ => "同期できません（次の周期で再試行）"
            }, state.Problem);
        }
    }

    [Theory]
    [InlineData(true, "{}", "schema_version")]
    [InlineData(false, "{}", "schema_version")]
    [InlineData(true, "{", "invalid_document")]
    [InlineData(false, "{", "invalid_document")]
    public void InvalidSuccessNeverReplacesCache(bool cached, string body, string code)
    {
        var clock = new FakeClock();
        var state = new SyncState(cached ? new(Samples.Timetable("basic"), clock.Now) : null);
        var previous = state.Timetable;
        Assert.Null(state.Apply(HttpStatusCode.OK, body, clock));
        Assert.Same(previous, state.Timetable);
        Assert.Equal("時間割を読み込めません: " + code, state.Problem);
        Assert.Null(new SyncState(new("invalid", clock.Now)).Timetable);
    }

    [Theory]
    [InlineData("INVALID_REFRESH_TOKEN", true)]
    [InlineData("TOKEN_EXPIRED", true)]
    [InlineData("USER_DISABLED", true)]
    [InlineData("USER_NOT_FOUND", true)]
    [InlineData("TOO_MANY_ATTEMPTS_TRY_LATER", false)]
    [InlineData("UNKNOWN", false)]
    public async Task RefreshFailuresDistinguishPermanentAndRetryable(string code, bool requiresSignIn)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.BadRequest, "{\"error\":{\"message\":\"" + code + "\"}}");
        var sessions = new MemorySessions();
        using var service = Service(http, sessions);
        var timetable = service.State.Timetable;
        await service.SyncAsync();
        Assert.Equal(requiresSignIn, service.Email is null);
        Assert.Equal(requiresSignIn, service.TakeAuthNotification());
        Assert.False(service.TakeAuthNotification());
        Assert.Same(timetable, service.State.Timetable);
        if (requiresSignIn)
        {
            await service.SyncAsync();
            Assert.Equal(1, http.Calls);
            Assert.Null(sessions.Value);
        }
        else
        {
            http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
            http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
            await service.SyncAsync();
            Assert.Null(service.State.Problem);
            Assert.Equal(3, http.Calls);
        }
    }

    [Theory]
    [InlineData(200)]
    [InlineData(401)]
    public async Task UnauthorizedFetchRefreshesAndRetriesOnlyOnce(int retryStatus)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue(HttpStatusCode.Unauthorized, "{}");
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh.Replace("synthetic-id", "synthetic-renewed-id").Replace("synthetic-next", "synthetic-rotated"));
        http.Enqueue((HttpStatusCode)retryStatus, Samples.Typed(Samples.Empty));
        var sessions = new MemorySessions();
        using var service = Service(http, sessions);
        var previous = service.State.Timetable;
        await service.SyncAsync();
        Assert.Equal(4, http.Calls);
        Assert.Equal("securetoken.googleapis.com", http.Requests[2].Host);
        Assert.Equal(http.Requests[1].Path, http.Requests[3].Path);
        Assert.Equal("synthetic-renewed-id", http.Requests[3].Bearer);
        Assert.Equal("synthetic-rotated", sessions.Value!.RefreshToken);
        Assert.NotNull(service.Email);
        Assert.False(service.TakeAuthNotification());
        if (retryStatus == 200)
        {
            Assert.Null(service.State.Problem);
            Assert.NotSame(previous, service.State.Timetable);
        }
        else
        {
            Assert.Contains("再試行", service.State.Problem);
            Assert.Same(previous, service.State.Timetable);
            // The next period may retry again, but the current period has stopped.
            http.Enqueue(HttpStatusCode.Unauthorized, "{}");
            http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
            http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
            await service.SyncAsync();
            Assert.Equal(7, http.Calls);
            Assert.Null(service.State.Problem);
        }
        Assert.Empty(http.Replies);
    }

    [Fact]
    public async Task ForbiddenFetchKeepsSessionAndBlockingWithoutRefreshingAgain()
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue(HttpStatusCode.Forbidden, "{}");
        var sessions = new MemorySessions();
        using var service = Service(http, sessions);
        var previous = service.State.Timetable;
        await service.SyncAsync();
        Assert.Equal(2, http.Calls);
        Assert.Equal("時間割を読む権限がありません", service.State.Problem);
        Assert.NotNull(service.Email);
        Assert.NotNull(sessions.Value);
        Assert.False(service.TakeAuthNotification());
        Assert.Same(previous, service.State.Timetable);
        Assert.True(TimetableExpander.IsBlockedAt(service.State.Timetable!, DateTimeOffset.Parse("2026-01-05T10:15:00Z")));
        http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        await service.SyncAsync();
        Assert.Equal(3, http.Calls);
        Assert.Null(service.State.Problem);
    }

    [Theory]
    [InlineData("INVALID_REFRESH_TOKEN")]
    [InlineData("TOKEN_EXPIRED")]
    [InlineData("USER_DISABLED")]
    [InlineData("USER_NOT_FOUND")]
    public async Task PermanentForcedRefreshFailureClearsSessionAndNotifiesOnce(string code)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue(HttpStatusCode.Unauthorized, "{}");
        http.Enqueue(HttpStatusCode.BadRequest, "{\"error\":{\"message\":\"" + code + "\"}}");
        var sessions = new MemorySessions();
        using var service = Service(http, sessions);
        var previous = service.State.Timetable;
        await service.SyncAsync();
        Assert.Equal(3, http.Calls);
        Assert.Null(sessions.Value);
        Assert.Null(service.Email);
        Assert.Equal("サインインが必要です", service.State.Problem);
        Assert.Same(previous, service.State.Timetable);
        Assert.True(service.TakeAuthNotification());
        await service.SyncAsync();
        Assert.False(service.TakeAuthNotification());
        Assert.Equal(3, http.Calls);
    }

    [Theory]
    [InlineData(403)]
    [InlineData(401)]
    [InlineData(500)]
    [InlineData(0)]
    public async Task OtherForcedRefreshFailuresRetainSessionAndRetryRefreshNextCycle(int status)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue(HttpStatusCode.Unauthorized, "{}");
        if (status == 0) http.Replies.Enqueue(_ => throw new HttpRequestException());
        else http.Enqueue((HttpStatusCode)status, "{\"error\":{\"message\":\"PERMISSION_DENIED\"}}");
        var sessions = new MemorySessions();
        using var service = Service(http, sessions);
        var previous = service.State.Timetable;
        await service.SyncAsync();
        Assert.Equal(3, http.Calls);
        Assert.NotNull(service.Email);
        Assert.Equal("synthetic-next", sessions.Value!.RefreshToken);
        Assert.False(service.TakeAuthNotification());
        Assert.Same(previous, service.State.Timetable);
        if (status is 0 or 500) Assert.Null(service.State.Problem);
        else Assert.Contains("認証の設定を確認してください", service.State.Problem);
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        await service.SyncAsync();
        Assert.Equal("securetoken.googleapis.com", http.Requests[3].Host);
        Assert.Equal(5, http.Calls);
        Assert.Null(service.State.Problem);
    }

    [Theory]
    [InlineData(true, true)]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(false, false)]
    public async Task NetworkFailureOrTimeoutKeepsCacheAndRetries(bool timeout, bool cached)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Replies.Enqueue(_ => throw (timeout ? (Exception)new TaskCanceledException() : new HttpRequestException()));
        using var service = Service(http, new(), cached);
        var previous = service.State.Timetable;
        await service.SyncAsync();
        Assert.Same(previous, service.State.Timetable);
        Assert.NotNull(service.Email);
        Assert.Null(service.State.Problem);
        http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        await service.SyncAsync();
        Assert.Null(service.State.Problem);
        Assert.Equal(3, http.Calls);
    }

    [Fact]
    public async Task LoginRefreshAndFetchUseExpectedHttpContractsAndClock()
    {
        var clock = new FakeClock();
        var http = new FakeHttp();
        http.Replies.Enqueue(async request =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Equal("identitytoolkit.googleapis.com", request.RequestUri!.Host);
            var body = await request.Content!.ReadAsStringAsync();
            Assert.Contains("\"returnSecureToken\":true", body);
            Assert.Contains("\"password\":\"synthetic-password\"", body);
            return new(HttpStatusCode.OK) { Content = new StringContent(Samples.Login) };
        });
        void Fetch()
        {
            http.Replies.Enqueue(request =>
            {
                Assert.Equal(HttpMethod.Get, request.Method);
                Assert.EndsWith("/users/synthetic-user/config/timetable", request.RequestUri!.AbsolutePath);
                Assert.Equal("Bearer", request.Headers.Authorization!.Scheme);
                Assert.Equal("synthetic-id", request.Headers.Authorization.Parameter);
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(Samples.Typed(Samples.Empty)) });
            });
        }
        Fetch();
        var sessions = new MemorySessions { Value = null };
        TimetableCache? persisted = null;
        using var service = new SyncService(new(new("synthetic", "synthetic-project"), clock, http), sessions, null, cache => persisted = cache, clock);
        Assert.Null(await service.SignInAsync("example@example.invalid", "synthetic-password"));
        Assert.NotNull(persisted);
        Assert.Equal("synthetic-next", sessions.Value!.RefreshToken);
        clock.Now = clock.Now.AddMinutes(54);
        Fetch();
        await service.SyncAsync();
        clock.Now = clock.Now.AddMinutes(1);
        http.Replies.Enqueue(async request =>
        {
            Assert.Equal("securetoken.googleapis.com", request.RequestUri!.Host);
            Assert.Equal("application/x-www-form-urlencoded", request.Content!.Headers.ContentType!.MediaType);
            Assert.Equal("grant_type=refresh_token&refresh_token=synthetic-next", await request.Content.ReadAsStringAsync());
            return new(HttpStatusCode.OK) { Content = new StringContent(Samples.Refresh) };
        });
        Fetch();
        await service.SyncAsync();
        Assert.Equal(5, http.Calls);
        var previous = service.State.Timetable;
        await service.SignOutAsync();
        Assert.Null(sessions.Value);
        Assert.Same(previous, service.State.Timetable);
        await service.SyncAsync();
        Assert.Equal(5, http.Calls);
    }

    [Fact]
    public async Task SignOutWaitsForInFlightSyncAndCannotBeUndoneByIt()
    {
        var http = new FakeHttp();
        var reply = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        http.Replies.Enqueue(_ => reply.Task);
        http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        var sessions = new MemorySessions();
        using var service = Service(http, sessions);
        var sync = service.SyncAsync();
        var signOut = service.SignOutAsync();
        Assert.False(signOut.IsCompleted);
        reply.SetResult(new(HttpStatusCode.OK) { Content = new StringContent(Samples.Refresh) });
        await Task.WhenAll(sync, signOut);
        Assert.Null(sessions.Value);
        Assert.Null(service.Email);
        Assert.NotNull(service.State.Timetable);
    }

    [Fact]
    public async Task MissingConfigStillKeepsCacheAndReportsSignInUnavailable()
    {
        using var service = new SyncService(null, new MemorySessions(), new(Samples.Timetable("basic"), new FakeClock().Now), _ => { }, new FakeClock());
        await service.SyncAsync();
        Assert.False(service.Configured);
        Assert.Contains("サインインできません", service.AuthProblem);
        Assert.NotNull(service.State.Timetable);
    }

    [Fact]
    public async Task CacheWriteFailureDoesNotDiscardValidFetchedTimetable()
    {
        var clock = new FakeClock();
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        var failSave = true;
        using var service = new SyncService(new(new("synthetic", "synthetic-project"), clock, http), new MemorySessions(), null,
            _ => { if (failSave) throw new IOException(); }, clock);
        await service.SyncAsync();
        Assert.NotNull(service.State.Timetable);
        Assert.NotNull(service.StorageProblem);
        Assert.Equal(clock.Now, service.State.LastSync);
        // A successful session write must not hide an unresolved cache failure.
        http.Enqueue(HttpStatusCode.OK, Samples.Login);
        http.Enqueue(HttpStatusCode.NotFound, "{}");
        await service.SignInAsync("example@example.invalid", "synthetic");
        Assert.NotNull(service.StorageProblem);
        failSave = false;
        http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
        await service.SyncAsync();
        Assert.Null(service.StorageProblem);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task SuccessfulSessionSaveClearsPriorLoadOrSaveError(bool loadFailure)
    {
        var http = new FakeHttp();
        var sessions = new MemorySessions { FailLoad = loadFailure, FailSave = !loadFailure };
        using var service = Service(http, sessions);
        if (!loadFailure)
        {
            http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
            http.Enqueue(HttpStatusCode.OK, Samples.Typed(Samples.Empty));
            await service.SyncAsync();
        }
        // Successful cache writes cannot hide session persistence failures either.
        Assert.NotNull(service.StorageProblem);
        sessions.FailLoad = false;
        sessions.FailSave = false;
        http.Enqueue(HttpStatusCode.OK, Samples.Login);
        http.Enqueue(HttpStatusCode.NotFound, "{}");
        Assert.Null(await service.SignInAsync("example@example.invalid", "synthetic"));
        Assert.Null(service.StorageProblem);
        Assert.NotNull(sessions.Value);
    }

    [Theory]
    [InlineData("INVALID_LOGIN_CREDENTIALS", "メールアドレスまたはパスワードが違います。")]
    [InlineData("TOO_MANY_ATTEMPTS_TRY_LATER", "試行回数が多すぎます。しばらく待ってください。")]
    public async Task LoginErrorsAreShortJapaneseMessages(string code, string expected)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.BadRequest, "{\"error\":{\"message\":\"" + code + "\"}}");
        using var service = Service(http, new MemorySessions { Value = null });
        Assert.Equal(expected, await service.SignInAsync("example@example.invalid", "synthetic"));
    }

    private static SyncService Service(FakeHttp http, MemorySessions sessions, bool cached = true)
    {
        var clock = new FakeClock();
        return new(new(new("synthetic", "synthetic-project"), clock, http), sessions,
            cached ? new(Samples.Timetable("basic"), clock.Now.AddHours(-1)) : null, _ => { }, clock);
    }
}
