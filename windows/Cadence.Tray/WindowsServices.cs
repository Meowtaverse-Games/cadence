using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Cadence.Core;
using Cadence.Tray.Logic;
using Microsoft.Win32;

namespace Cadence.Tray;

internal sealed class DpapiSessionStore(string path) : ISessionStore
{
    public SavedSession? Load()
    {
        if (!File.Exists(path)) return null;
        var plaintext = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
        try
        {
            var session = JsonSerializer.Deserialize<SavedSession>(plaintext);
            return string.IsNullOrWhiteSpace(session?.RefreshToken) || string.IsNullOrWhiteSpace(session.Email) ? null : session;
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }

    public void Save(SavedSession? session)
    {
        // A signed-out marker is also replaced atomically. No password is serialized.
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(session);
        try { AtomicFile.Write(path, ProtectedData.Protect(plaintext, null, DataProtectionScope.CurrentUser)); }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }
}

internal sealed class DailyLog(string directory, TimeProvider clock)
{
    private readonly object gate = new();
    public void Write(string code)
    {
        lock (gate)
        {
            try
            {
                Directory.CreateDirectory(directory);
                var now = clock.GetUtcNow().ToOffset(TimeSpan.FromHours(9));
                foreach (var file in Directory.EnumerateFiles(directory, "*.txt"))
                    if (DateTime.TryParseExact(Path.GetFileNameWithoutExtension(file), "yyyy-MM-dd", CultureInfo.InvariantCulture,
                        DateTimeStyles.None, out var date) && date < now.Date.AddDays(-13)) File.Delete(file);
                var path = Path.Combine(directory, now.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + ".txt");
                // Never log exception messages, HTTP bodies, credentials, or account identifiers.
                var existing = File.Exists(path) ? File.ReadAllText(path) : "";
                AtomicFile.Write(path, Encoding.UTF8.GetBytes(existing + now.ToString("HH:mm:ss", CultureInfo.InvariantCulture) + " " + code + Environment.NewLine));
            }
            catch { /* Logging failure must not stop the tray or block loop. */ }
        }
    }
}

internal static class StartupRegistration
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public static bool Enabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(Key);
        return key?.GetValue("Cadence") is string;
    }
    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(Key);
        if (enabled) key.SetValue("Cadence", "\"" + Path.GetFullPath(Environment.ProcessPath!) + "\"");
        else key.DeleteValue("Cadence", throwOnMissingValue: false);
    }
}

internal sealed class ProcessMonitor(TimeProvider clock, DailyLog log, Action<string, string, bool> notify)
{
    private readonly NotificationLimiter limiter = new(clock);
    public void Tick(Timetable? timetable, IReadOnlyCollection<string> executables)
    {
        try
        {
            if (timetable is null || !TimetableExpander.IsBlockedAt(timetable, clock.GetUtcNow())) return;
            foreach (var process in Process.GetProcesses())
            {
                using (process)
                {
                    string? name = null;
                    try
                    {
                        if (process.Id == Environment.ProcessId) continue;
                        name = process.ProcessName + ".exe";
                        if (!executables.Contains(name, StringComparer.OrdinalIgnoreCase)) continue;
                        process.Kill(entireProcessTree: true);
                        if (limiter.Allow(name)) notify("集中の時間です", name + " を終了しました。", false);
                    }
                    catch (InvalidOperationException) { /* The process already exited. */ }
                    catch
                    {
                        log.Write("process_termination_failed pid=" + process.Id + " exe=" + name?.Replace("\r", "").Replace("\n", ""));
                        if (name is not null && executables.Contains(name, StringComparer.OrdinalIgnoreCase) && limiter.Allow(name))
                            notify("アプリを終了できません", name + " の権限などを確認してください。", true);
                    }
                }
            }
        }
        catch { log.Write("process_scan_failed"); }
    }

    public static string[] RunningNames()
    {
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var process in Process.GetProcesses())
            using (process)
            {
                try { if (process.Id != Environment.ProcessId) names.Add(process.ProcessName + ".exe"); }
                catch { /* Processes can exit during enumeration. */ }
            }
        return names.Order(StringComparer.OrdinalIgnoreCase).ToArray();
    }
}
