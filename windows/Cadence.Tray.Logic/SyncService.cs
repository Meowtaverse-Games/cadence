using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Cadence.Core;

namespace Cadence.Tray.Logic;

public sealed class SyncState
{
    public Timetable? Timetable { get; private set; }
    public string? TimetableJson { get; private set; }
    public DateTimeOffset? LastSync { get; private set; }
    public string? Problem { get; private set; }

    public SyncState(TimetableCache? cache)
    {
        if (cache is null) return;
        var parsed = TimetableParser.Parse(cache.Json);
        if (!parsed.Valid) return;
        Timetable = parsed.Timetable;
        TimetableJson = cache.Json;
        LastSync = cache.FetchedAt;
    }

    public TimetableCache? Apply(HttpStatusCode status, string body, TimeProvider clock)
    {
        if (status == HttpStatusCode.OK)
        {
            var json = FirestoreDocument.Convert(body);
            var parsed = json is null ? new ValidationResult(null, "invalid_document") : TimetableParser.Parse(json);
            if (!parsed.Valid)
            {
                Problem = "時間割を読み込めません: " + parsed.ErrorCode;
                return null;
            }
            Timetable = parsed.Timetable;
            TimetableJson = json;
            LastSync = clock.GetUtcNow();
            Problem = null;
            return new(json!, LastSync.Value);
        }
        Problem = status switch
        {
            HttpStatusCode.NotFound => "サーバーに時間割がありません",
            HttpStatusCode.Forbidden => "時間割を読む権限がありません",
            _ => "同期できません（次の周期で再試行）"
        };
        return null;
    }
    public void SetProblem(string problem) => Problem = problem;
}

// All mutations are serialized, including sign-out vs an in-flight refresh/fetch.
public sealed class SyncService : IDisposable
{
    private FirebaseClient? client;
    private readonly ISessionStore sessions;
    private readonly Action<TimetableCache> saveCache;
    private readonly Action<string> log;
    private readonly TimeProvider clock;
    private readonly SemaphoreSlim gate = new(1, 1);
    private SavedSession? saved;
    private AuthToken? token;
    private CancellationTokenSource? retryCancellation;
    private int transientFailures;
    private bool disposed;
    private bool authNotified;
    private bool authNotificationPending;
    public SyncState State { get; }
    public Task RetryCompletion { get; private set; } = Task.CompletedTask;
    public bool IsSynchronizing => gate.CurrentCount == 0;
    public bool Configured => client is not null;
    public bool HasSavedSession => saved is not null;
    public string? Email => saved?.Email;
    private bool sessionStorageFailed;
    private bool cacheStorageFailed;
    public string? StorageProblem => sessionStorageFailed || cacheStorageFailed
        ? "ローカルファイルを保存・読み込みできません" : null;
    public string? AuthProblem => !Configured ? "接続設定がないか不正なためサインインできません" : saved is null ? "サインインが必要です" : null;

    public SyncService(FirebaseClient? client, ISessionStore sessions, TimetableCache? cache,
        Action<TimetableCache> saveCache, TimeProvider clock, Action<string>? log = null)
    {
        this.client = client;
        this.sessions = sessions;
        this.saveCache = saveCache;
        this.clock = clock;
        this.log = log ?? (_ => { });
        State = new(cache);
        try { saved = sessions.Load(); }
        catch { SessionStorageFailed(); }
    }

    public bool TakeAuthNotification()
    {
        var pending = authNotificationPending;
        authNotificationPending = false;
        return pending;
    }

    public async Task<FirebaseConfigLoadResult> ReloadConfigurationAsync(string path, string? directlyUnderAppDataPath,
        Func<FirebaseConfig, FirebaseClient>? createClient = null, CancellationToken cancellation = default)
    {
        await gate.WaitAsync(cancellation);
        try
        {
            var result = FirebaseConfig.Load(path, directlyUnderAppDataPath);
            var replacement = result.Config is { } config
                ? (createClient is null ? new FirebaseClient(config, clock, new HttpClientHandler()) : createClient(config))
                : null;
            CancelRetry();
            transientFailures = 0;
            var previous = client;
            client = replacement;
            // A token from the previous connection must not be sent to a changed project.
            // Keep the saved refresh token and timetable; normal auth handling still applies.
            token = null;
            previous?.Dispose();
            log(result.Code);
            return result;
        }
        finally { gate.Release(); }
    }

    public async Task<string?> SignInAsync(string email, string password, CancellationToken cancellation = default)
    {
        await gate.WaitAsync(cancellation);
        try
        {
            CancelRetry();
            transientFailures = 0;
            if (client is null) return AuthProblem;
            var fresh = await client.SignInAsync(email, password, cancellation);
            token = fresh;
            saved = new(fresh.RefreshToken, email);
            authNotified = false;
            authNotificationPending = false;
            PersistSession();
            ScheduleRetry(await SyncLockedAsync(cancellation), cancellation);
        }
        catch (FirebaseFailure failure) { return SignInErrors.Message(failure.Code); }
        catch { return "サインインできません。接続や設定を確認してください。"; }
        finally { gate.Release(); }
        return null;
    }

    public async Task SignOutAsync(CancellationToken cancellation = default)
    {
        await gate.WaitAsync(cancellation);
        try
        {
            CancelRetry();
            transientFailures = 0;
            saved = null;
            token = null;
            authNotificationPending = false;
            PersistSession();
            State.SetProblem("サインインが必要です");
        }
        finally { gate.Release(); }
    }

    public async Task SyncAsync(CancellationToken cancellation = default)
    {
        // A periodic/manual sync and the delayed retry must never queue two fetches.
        if (!await gate.WaitAsync(0, cancellation)) return;
        try
        {
            CancelRetry();
            ScheduleRetry(await SyncLockedAsync(cancellation), cancellation);
        }
        finally { gate.Release(); }
    }

    private void ScheduleRetry(string? reason, CancellationToken cancellation)
    {
        if (reason is null || disposed || cancellation.IsCancellationRequested) return;
        var source = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        retryCancellation = source;
        log("sync_retry_scheduled");
        RetryCompletion = RetryAsync(source);
    }

    private async Task RetryAsync(CancellationTokenSource source)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), clock, source.Token);
            if (!await gate.WaitAsync(0, source.Token)) return;
            try
            {
                if (disposed || source.IsCancellationRequested || retryCancellation != source) return;
                // Do not schedule again, regardless of this attempt's result.
                var reason = await SyncLockedAsync(source.Token);
                if (source.IsCancellationRequested) return;
                log(reason is not null ? "sync_retry_failed reason=" + reason
                    : State.Problem is null ? "sync_retry_succeeded" : "sync_retry_completed_with_problem");
            }
            finally { gate.Release(); }
        }
        catch (OperationCanceledException) when (source.IsCancellationRequested) { }
        finally
        {
            if (retryCancellation == source) retryCancellation = null;
            source.Dispose();
        }
    }

    private void CancelRetry()
    {
        retryCancellation?.Cancel();
        retryCancellation = null;
    }

    private async Task<string?> SyncLockedAsync(CancellationToken cancellation)
    {
        if (disposed || client is null || saved is null) return null;
        try
        {
            if (token is null || token.NeedsRefresh(clock))
                await RefreshTokenAsync(cancellation);
            var response = await client.FetchAsync(token!, cancellation);
            if (response.Status == HttpStatusCode.Unauthorized)
            {
                // Refresh even before expiry, then retry the GET exactly once this cycle.
                await RefreshTokenAsync(cancellation);
                response = await client.FetchAsync(token!, cancellation);
            }
            if (IsTransient(response.Status)) return TransientFailure("http_" + (int)response.Status);
            transientFailures = 0;
            var cache = State.Apply(response.Status, response.Body, clock);
            if (response.Status != HttpStatusCode.OK) log("sync_failed reason=http_" + (int)response.Status);
            else if (cache is null) log("sync_failed reason=parse");
            if (cache is not null)
            {
                try
                {
                    saveCache(cache);
                    cacheStorageFailed = false;
                }
                catch
                {
                    cacheStorageFailed = true;
                    log("storage_failed");
                }
            }
        }
        catch (FirebaseFailure failure) when (failure.RequiresSignIn)
        {
            transientFailures = 0;
            RequireSignIn();
        }
        catch (FirebaseFailure failure) when (IsTransient(failure.Status))
        {
            return TransientFailure("http_" + (int)failure.Status);
        }
        catch (FirebaseFailure)
        {
            transientFailures = 0;
            State.SetProblem("認証の設定を確認してください（次の周期で再試行）");
            log("token_refresh_failed");
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception failure) { return TransientFailure(FailureReason(failure)); }
        return null;
    }

    private static bool IsTransient(HttpStatusCode status) => (int)status is >= 500 and <= 599 or 429;

    private string TransientFailure(string reason)
    {
        transientFailures = Math.Min(2, transientFailures + 1);
        if (transientFailures >= 2) State.SetProblem("同期できません（次の周期で再試行）");
        log("sync_failed reason=" + reason);
        return reason;
    }

    // Only fixed categories leave this boundary; never log exception messages/URLs.
    public static string FailureReason(Exception failure)
    {
        if (failure is OperationCanceledException or TimeoutException) return "timeout";
        if (failure is HttpRequestException { StatusCode: { } status }) return "http_" + (int)status;
        for (Exception? cause = failure; cause is not null; cause = cause.InnerException)
        {
            if (cause is SocketException socket)
                return socket.SocketErrorCode is SocketError.HostNotFound or SocketError.NoData or SocketError.TryAgain
                    ? "dns" : "connect";
        }
        return failure switch
        {
            HttpRequestException { HttpRequestError: HttpRequestError.NameResolutionError } => "dns",
            HttpRequestException => "connect",
            JsonException or FormatException or InvalidOperationException or KeyNotFoundException or OverflowException => "parse",
            _ => "other"
        };
    }

    private async Task RefreshTokenAsync(CancellationToken cancellation)
    {
        // A failed forced refresh must be retried next cycle even if the old token
        // had not reached its scheduled refresh time. Keep the saved session.
        var session = saved!;
        token = null;
        token = await client!.RefreshAsync(session.RefreshToken, cancellation);
        saved = session with { RefreshToken = token.RefreshToken };
        PersistSession();
    }

    private void RequireSignIn()
    {
        saved = null;
        token = null;
        PersistSession();
        State.SetProblem("サインインが必要です");
        if (!authNotified) authNotificationPending = true;
        authNotified = true;
    }

    private void PersistSession()
    {
        try
        {
            sessions.Save(saved);
            sessionStorageFailed = false;
        }
        catch { SessionStorageFailed(); }
    }
    private void SessionStorageFailed()
    {
        sessionStorageFailed = true;
        log("storage_failed");
    }
    public void Dispose()
    {
        disposed = true;
        CancelRetry();
        client?.Dispose();
    }
}
