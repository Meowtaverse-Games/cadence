namespace Cadence.Tray.Logic;

public enum TerminationResult { Killed, AlreadyExited, Failed }

public static class ProcessTermination
{
    public static TerminationResult TryKill(Action kill, Func<bool> hasExited)
    {
        try
        {
            kill();
            return TerminationResult.Killed;
        }
        catch (InvalidOperationException)
        {
            // Kill(true) also rejects a tree containing this process. Do not
            // interpret that as an exited target or inspect exception messages.
            try { if (hasExited()) return TerminationResult.AlreadyExited; }
            catch { /* An unconfirmed exit is a failure too. */ }
            return TerminationResult.Failed;
        }
        catch { return TerminationResult.Failed; }
    }
}

public sealed class ProcessNotifications(TimeProvider clock, Action<string> log, Action<string, string, bool> notify)
{
    private readonly NotificationLimiter notifications = new(clock);
    private readonly NotificationLimiter failureLogs = new(clock);

    private readonly HashSet<string> successesThisScan = new(StringComparer.OrdinalIgnoreCase);

    // A scan, not a wall-clock timeout, is the unit of a successful termination record.
    // Failure logging and notification rate limits deliberately keep their own lifetimes.
    public void BeginScan() => successesThisScan.Clear();

    public void Success(string executable, DateTimeOffset? blockEnd = null)
    {
        var safeName = executable.Replace("\r", "").Replace("\n", "");
        if (ExecutableNames.Normalize(safeName) == safeName && successesThisScan.Add(safeName)) log("process_terminated exe=" + safeName);
        if (notifications.Allow(executable)) notify(executable + " を終了しました", blockEnd is { } end
            ? "集中の時間です（" + end.ToOffset(TimeSpan.FromHours(9)).ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture) + " まで）"
            : "集中の時間です", false);
    }

    public void Failure(string executable, int processId)
    {
        if (failureLogs.Allow(executable))
            log("process_termination_failed pid=" + processId + " exe=" + executable.Replace("\r", "").Replace("\n", ""));
        if (notifications.Allow(executable))
            notify("アプリを終了できません", executable + " の権限などを確認してください。", true);
    }
}
