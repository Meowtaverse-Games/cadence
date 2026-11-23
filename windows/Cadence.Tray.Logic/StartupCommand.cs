namespace Cadence.Tray.Logic;

public static class StartupCommand
{
    public static string Executable(string processPath, bool installed, bool portable,
        string? rootDirectory, string? appId)
    {
        if (!installed || portable) return Path.GetFullPath(processPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(appId);
        // Velopack 1.2.161 uses packTitle ?? packId for its launcher name.
        // Our packaging does not set packTitle; the locator's AppId is packId.
        return Path.GetFullPath(Path.Combine(rootDirectory, appId + ".exe"));
    }

    public static string RegistryValue(string executable) => "\"" + executable + "\"";
}
