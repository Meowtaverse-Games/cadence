using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class StartupCommandTests
{
    private static readonly string Root = Path.GetFullPath(Path.Combine("synthetic apps", "Cadence"));
    private static readonly string CurrentExe = Path.Combine(Root, "current", "Cadence.Tray.exe");

    [Fact]
    public void InstalledAppRegistersRootStubInsteadOfCurrentExecutable()
    {
        var executable = StartupCommand.Executable(CurrentExe, true, false, Root, "Cadence");
        Assert.Equal(Path.Combine(Root, "Cadence.exe"), executable);
        Assert.NotEqual(CurrentExe, executable);
        Assert.NotEqual(Path.Combine(Root, "Cadence.Tray.exe"), executable);
        Assert.Equal("\"" + Path.Combine(Root, "Cadence.exe") + "\"", StartupCommand.RegistryValue(executable));
    }

    [Fact]
    public void ManualAppRegistersRunningExecutableWithoutLocatorMetadata()
    {
        Assert.Equal(CurrentExe, StartupCommand.Executable(CurrentExe, false, false, null, null));
    }

    [Fact]
    public void PortableAppRegistersRunningExecutable()
    {
        Assert.Equal(CurrentExe, StartupCommand.Executable(CurrentExe, true, true, Root, "Cadence"));
    }

    [Fact]
    public void InstalledLauncherUsesLocatorAppId()
    {
        Assert.Equal(Path.Combine(Root, "SyntheticApp.exe"),
            StartupCommand.Executable(CurrentExe, true, false, Root, "SyntheticApp"));
    }

    [Theory]
    [InlineData(null, "Cadence")]
    [InlineData("synthetic", null)]
    [InlineData("", "Cadence")]
    [InlineData("synthetic", "")]
    public void InstalledAppDoesNotFallBackToCurrentWhenMetadataIsMissing(string? root, string? appId)
    {
        Assert.ThrowsAny<ArgumentException>(() => StartupCommand.Executable(CurrentExe, true, false, root, appId));
    }
}
