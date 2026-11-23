using System.Reflection;
using Cadence.Tray.Logic;
using Velopack;
using Velopack.Sources;

namespace Cadence.Tray;

internal sealed class AppUpdater : IAppUpdater
{
    private readonly UpdateManager manager = new(new GithubSource(
        "https://github.com/Meowtaverse-Games/cadence", accessToken: null, prerelease: false));
    private UpdateInfo? available;
    public static string BuildVersion => typeof(AppUpdater).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "不明";
    public bool Installed => manager.IsInstalled && !manager.IsPortable;
    public string Version => manager.CurrentVersion?.ToString() ?? BuildVersion;
    public bool HasPendingUpdate => manager.UpdatePendingRestart is not null;
    public async Task<bool> CheckAsync()
    {
        available = await Task.Run(() => manager.CheckForUpdatesAsync());
        return available is not null;
    }
    public Task DownloadAsync(CancellationToken cancellation) => Task.Run(
        () => manager.DownloadUpdatesAsync(available!, cancelToken: cancellation), cancellation);
    public void ApplyAndRestart() => manager.ApplyUpdatesAndRestart(
        manager.UpdatePendingRestart ?? throw new InvalidOperationException("No pending update package."));
}
