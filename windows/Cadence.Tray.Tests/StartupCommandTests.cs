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
    [InlineData(false)]
    [InlineData(true)]
    public void DisabledStartupIsNeverRepaired(bool installed)
    {
        Assert.Null(StartupCommand.RepairValue(null, false, installed, CurrentExe));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void ManualAppPreservesExistingInstalledStub(bool quoted, bool differentCase)
    {
        var stub = Path.Combine(Root, "Cadence.exe");
        if (differentCase) stub = stub.ToUpperInvariant();
        var value = quoted ? StartupCommand.RegistryValue(stub) : stub;
        Assert.Equal(stub, StartupCommand.RegisteredExecutable(value));
        Assert.Null(StartupCommand.RepairValue(value, true, false, CurrentExe));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ManualAppReplacesMissingStub(bool quoted)
    {
        var stub = Path.Combine(Root, "Cadence.exe");
        Assert.Equal(StartupCommand.RegistryValue(CurrentExe),
            StartupCommand.RepairValue(quoted ? StartupCommand.RegistryValue(stub) : stub,
                false, false, CurrentExe));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void ManualAppRepairsMovedExecutable(bool quoted, bool oldExecutableExists)
    {
        var old = Path.Combine(Root, "old", "Cadence.Tray.exe");
        Assert.Equal(StartupCommand.RegistryValue(CurrentExe),
            StartupCommand.RepairValue(quoted ? StartupCommand.RegistryValue(old) : old,
                oldExecutableExists, false, CurrentExe));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void InstalledAppRepairsRegistrationToItsStub(bool oldExecutableExists)
    {
        var stub = Path.Combine(Root, "Cadence.exe");
        Assert.Equal(StartupCommand.RegistryValue(stub),
            StartupCommand.RepairValue(StartupCommand.RegistryValue(CurrentExe), oldExecutableExists, true, stub));
        Assert.Equal(StartupCommand.RegistryValue(stub),
            StartupCommand.RepairValue(Path.Combine(Root, "old", "Cadence.exe"), oldExecutableExists, true, stub));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void SameExecutableIsNotRewrittenIgnoringCase(bool quoted, bool installed)
    {
        var executable = (installed ? Path.Combine(Root, "Cadence.exe") : CurrentExe).ToUpperInvariant();
        var current = executable.ToLowerInvariant();
        Assert.Null(StartupCommand.RepairValue(quoted ? StartupCommand.RegistryValue(current) : current,
            true, installed, executable));
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
