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

    public static string? RegisteredExecutable(string? registryValue) => registryValue?.Trim().Trim('"');

    public static string? RepairValue(string? currentValue, bool currentExecutableExists,
        bool installed, string executable)
    {
        if (currentValue is null) return null; // An absent value means startup is disabled.
        var currentExecutable = RegisteredExecutable(currentValue);
        // A manual build must not replace a working Setup.exe launcher.
        if (!installed && currentExecutableExists &&
            string.Equals(Path.GetFileName(currentExecutable), "Cadence.exe", StringComparison.OrdinalIgnoreCase))
            return null;
        if (string.Equals(currentExecutable, executable, StringComparison.OrdinalIgnoreCase)) return null;
        return RegistryValue(executable);
    }
}
