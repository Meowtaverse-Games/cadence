using Cadence.Core;

namespace Cadence.Tray.Logic;

public static class UpdatePolicy
{
    public static bool CanRestart(Timetable? timetable, TimeProvider clock, bool settingsOpen)
    {
        if (settingsOpen) return false;
        // Evaluate both the schedule and the margin at one instant, even at a boundary.
        var now = clock.GetUtcNow();
        var status = ScheduleStatus.Evaluate(timetable, new FrozenClock(now));
        return !status.Blocked && (status.NextChange is null || status.NextChange - now >= TimeSpan.FromMinutes(5));
    }

    private sealed class FrozenClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}

public sealed record UpdateSnapshot(string Version, string Status, DateTimeOffset? LastChecked, bool CanCheck);

public interface IAppUpdater
{
    bool Installed { get; }
    string Version { get; }
    bool HasPendingUpdate { get; }
    Task<bool> CheckAsync();
    Task DownloadAsync(CancellationToken cancellation);
    void ApplyAndRestart();
}

public static class UpdateFailure
{
    // Raw messages can contain account paths, signed download URLs or response bodies.
    // Keep the exception type and use a short, allowlisted explanation instead.
    public static string Describe(Exception error) => error.GetType().Name + ": " + (error switch
    {
        HttpRequestException => "ネットワークまたは配信元に接続できません",
        OperationCanceledException => "通信がタイムアウトまたは中断しました",
        UnauthorizedAccessException => "更新ファイルにアクセスできません",
        IOException => "更新ファイルを読み書きできません",
        _ => "リリースの取得・検証または適用ができません"
    });
}

// Called on the UI thread. Awaited network/package work must not block that thread.
public sealed class UpdateCoordinator
{
    private readonly Func<IAppUpdater> create;
    private readonly TimeProvider clock;
    private readonly Func<bool> canRestart;
    private readonly Action<string> log;
    private readonly Action changed;
    private IAppUpdater? updater;
    private DateTimeOffset nextCheck;
    private DateTimeOffset retryApplyAfter;
    private bool busy;
    private bool stopped;
    private bool pending;
    public UpdateSnapshot Snapshot { get; private set; }

    public UpdateCoordinator(Func<IAppUpdater> create, string version, TimeProvider clock,
        Func<bool> canRestart, Action<string> log, Action changed)
    {
        this.create = create; this.clock = clock; this.canRestart = canRestart;
        this.log = log; this.changed = changed;
        nextCheck = clock.GetUtcNow().AddMinutes(1);
        Snapshot = new(version, "起動から1分後に確認します", null, true);
        Initialize();
    }

    private void Initialize()
    {
        try
        {
            updater = create();
            Snapshot = Snapshot with { Version = updater.Version, CanCheck = updater.Installed,
                Status = updater.Installed ? "起動から1分後に確認します" : "手動で置いた版のため自動更新しない" };
        }
        catch (Exception error) { Fail("initialize", error); }
    }

    public async Task TickAsync(bool force, CancellationToken cancellation)
    {
        if (busy || stopped || cancellation.IsCancellationRequested) return;
        busy = true;
        try
        {
            if (force || clock.GetUtcNow() >= nextCheck)
            {
                nextCheck = clock.GetUtcNow().AddHours(6);
                if (updater is null) Initialize();
                if (updater is null || !updater.Installed) return;
                Snapshot = Snapshot with { LastChecked = clock.GetUtcNow(), CanCheck = false, Status = "確認中" };
                changed();
                // Also recover a download left by a previous process without auto-applying at startup.
                pending = updater.HasPendingUpdate;
                if (await updater.CheckAsync())
                {
                    if (stopped || cancellation.IsCancellationRequested) return;
                    Snapshot = Snapshot with { Status = "ダウンロード中" };
                    changed();
                    await updater.DownloadAsync(cancellation);
                    pending = updater.HasPendingUpdate;
                }
                if (stopped || cancellation.IsCancellationRequested) return;
                Snapshot = Snapshot with { Status = pending ? "ダウンロード済み・自由時間に再起動して入れ替え" : "最新" };
            }
            if (pending && !stopped && !cancellation.IsCancellationRequested
                && clock.GetUtcNow() >= retryApplyAfter && canRestart())
            {
                // No await between the final safety decision and handing off to the updater.
                try { updater!.ApplyAndRestart(); }
                catch (Exception error)
                {
                    retryApplyAfter = nextCheck;
                    Fail("apply", error);
                }
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception error) { Fail("check_or_download", error); }
        finally
        {
            busy = false;
            Snapshot = Snapshot with { CanCheck = updater?.Installed ?? true };
            if (!stopped) changed();
        }
    }

    private void Fail(string stage, Exception error)
    {
        var reason = UpdateFailure.Describe(error);
        log("update_" + stage + "_failed " + reason);
        Snapshot = Snapshot with { Status = "確認に失敗: " + reason };
    }

    public void Stop() => stopped = true;
}
