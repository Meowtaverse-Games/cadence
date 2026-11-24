using Velopack;

namespace Cadence.Tray;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        // Run before Velopack, user data, registry access and any UI initialization.
        if (args.Length == 2 && args[0] == "--cadence-ci-packaged-smoke-test")
            return PackagedSmokeTest.Run(args[1]);

        VelopackApp.Build()
            .SetAutoApplyOnStartup(false)
            .OnAfterInstallFastCallback(_ => StartupRegistration.Install())
            .OnAfterUpdateFastCallback(_ => StartupRegistration.Update())
            .OnBeforeUninstallFastCallback(_ => StartupRegistration.Uninstall())
            .Run();
        if (args.Length == 2 && args[0] == "--design-preview")
        {
            ApplicationConfiguration.Initialize();
            DesignPreview.Generate(args[1]);
            return 0;
        }
        using var mutex = new Mutex(true, @"Local\Cadence.Tray", out var created);
        if (!created) return 0;
        try
        {
            StartupRegistration.RepairOnStartup();
            ApplicationConfiguration.Initialize();
            using var context = new TrayContext(TimeProvider.System);
            Application.Run(context);
        }
        finally { mutex.ReleaseMutex(); }
        return 0;
    }
}
