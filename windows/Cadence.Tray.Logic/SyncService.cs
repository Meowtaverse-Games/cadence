using System.Net;
using Cadence.Core;

namespace Cadence.Tray.Logic;

public sealed class SyncState
{
    public Timetable? Timetable { get; private set; }
    public DateTimeOffset? LastSync { get; private set; }
    public string? Problem { get; private set; }

    public SyncState(TimetableCache? cache)
    {
        if (cache is null) return;
        var parsed = TimetableParser.Parse(cache.Json);
        if (!parsed.Valid) return;
        Timetable = parsed.Timetable;
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
            LastSync = clock.GetUtcNow();
            Problem = null;
            return new(json!, LastSync.Value);
        }
        Problem = status switch
        {
            HttpStatusCode.NotFound => "サーバーに時間割がありません",
            HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden => "サインインが必要です",
            _ => "同期できません（次の周期で再試行）"
        };
        return null;
    }
    public void SetProblem(string problem) => Problem = problem;
}

// All mutations are serialized, including sign-out vs an in-flight refresh/fetch.
public sealed class SyncService : IDisposable
{
    private readonly FirebaseClient? client;
    private readonly ISessionStore sessions;
    private readonly Action<TimetableCache> saveCache;
    private readonly Action<string> log;
    private readonly TimeProvider clock;
    private readonly SemaphoreSlim gate = new(1, 1);
    private SavedSession? saved;
    private AuthToken? token;
    private bool authNotified;
    private bool authNotificationPending;
    public SyncState State { get; }
    public bool Configured => client is not null;
    public string? Email => saved?.Email;
    public string? StorageProblem { get; private set; }
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
        catch { StorageFailed(); }
    }

    public bool TakeAuthNotification()
    {
        var pending = authNotificationPending;
        authNotificationPending = false;
        return pending;
    }

    public async Task<string?> SignInAsync(string email, string password, CancellationToken cancellation = default)
    {
        await gate.WaitAsync(cancellation);
        try
        {
            if (client is null) return AuthProblem;
            var fresh = await client.SignInAsync(email, password, cancellation);
            token = fresh;
            saved = new(fresh.RefreshToken, email);
            authNotified = false;
            authNotificationPending = false;
            PersistSession();
            await SyncLockedAsync(cancellation);
            return null;
        }
        catch (FirebaseFailure failure) { return SignInErrors.Message(failure.Code); }
        catch { return "サインインできません。接続や設定を確認してください。"; }
        finally { gate.Release(); }
    }

    public async Task SignOutAsync(CancellationToken cancellation = default)
    {
        await gate.WaitAsync(cancellation);
        try
        {
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
        await gate.WaitAsync(cancellation);
        try { await SyncLockedAsync(cancellation); }
        finally { gate.Release(); }
    }

    private async Task SyncLockedAsync(CancellationToken cancellation)
    {
        if (client is null || saved is null) return;
        try
        {
            if (token is null || token.NeedsRefresh(clock))
            {
                token = await client.RefreshAsync(saved.RefreshToken, cancellation);
                saved = saved with { RefreshToken = token.RefreshToken };
                PersistSession();
            }
            var response = await client.FetchAsync(token, cancellation);
            var cache = State.Apply(response.Status, response.Body, clock);
            if (response.Status is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden) RequireSignIn();
            if (cache is not null)
            {
                try { saveCache(cache); }
                catch { StorageFailed(); }
            }
        }
        catch (FirebaseFailure failure) when (failure.RequiresSignIn) { RequireSignIn(); }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch
        {
            State.SetProblem("同期できません（次の周期で再試行）");
            log("sync_failed");
        }
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
        try { sessions.Save(saved); }
        catch { StorageFailed(); }
    }
    private void StorageFailed()
    {
        StorageProblem = "ローカルファイルを保存・読み込みできません";
        log("storage_failed");
    }
    public void Dispose() => client?.Dispose();
}
