using System.Diagnostics;
using System.Security.Cryptography;
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
    private readonly ProcessNotifications notifications = new(clock, log.Write, notify);
    public void Tick(Timetable? timetable, IReadOnlyCollection<string> executables)
    {
        try
        {
            if (timetable is null || !TimetableExpander.IsBlockedAt(timetable, clock.GetUtcNow())) return;
            foreach (var process in Process.GetProcesses())
            {
                using (process)
                {
                    try
                    {
                        var id = process.Id;
                        if (id == Environment.ProcessId) continue;
                        var name = process.ProcessName + ".exe";
                        if (!executables.Contains(name, StringComparer.OrdinalIgnoreCase)) continue;
                        var result = ProcessTermination.TryKill(() => process.Kill(entireProcessTree: true), () => process.HasExited);
                        if (result == TerminationResult.Killed) notifications.Success(name);
                        else if (result == TerminationResult.Failed) notifications.Failure(name, id);
                    }
                    catch { log.Write("process_inspection_failed"); }
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
