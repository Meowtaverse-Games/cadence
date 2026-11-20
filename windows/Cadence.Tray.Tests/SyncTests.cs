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
                401 or 403 => "サインインが必要です",
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
    [InlineData(401)]
    [InlineData(403)]
    public async Task UnauthorizedFetchNotifiesOnceAndRetainsBlocking(int status)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Enqueue((HttpStatusCode)status, "{}");
        using var service = Service(http, new());
        await service.SyncAsync();
        Assert.True(service.TakeAuthNotification());
        Assert.False(service.TakeAuthNotification());
        Assert.Null(service.Email);
        Assert.True(TimetableExpander.IsBlockedAt(service.State.Timetable!, DateTimeOffset.Parse("2026-01-05T10:15:00Z")));
        await service.SyncAsync();
        Assert.Equal(2, http.Calls);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task NetworkFailureOrTimeoutKeepsCacheAndRetries(bool timeout)
    {
        var http = new FakeHttp();
        http.Enqueue(HttpStatusCode.OK, Samples.Refresh);
        http.Replies.Enqueue(_ => throw (timeout ? (Exception)new TaskCanceledException() : new HttpRequestException()));
        using var service = Service(http, new());
        var previous = service.State.Timetable;
        await service.SyncAsync();
        Assert.Same(previous, service.State.Timetable);
        Assert.NotNull(service.Email);
        Assert.Contains("再試行", service.State.Problem);
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
        using var service = new SyncService(new(new("synthetic", "synthetic-project"), clock, http), new MemorySessions(), null, _ => throw new IOException(), clock);
        await service.SyncAsync();
        Assert.NotNull(service.State.Timetable);
        Assert.NotNull(service.StorageProblem);
        Assert.Equal(clock.Now, service.State.LastSync);
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

    private static SyncService Service(FakeHttp http, MemorySessions sessions)
    {
        var clock = new FakeClock();
        return new(new(new("synthetic", "synthetic-project"), clock, http), sessions,
            new(Samples.Timetable("basic"), clock.Now.AddHours(-1)), _ => { }, clock);
    }
}
