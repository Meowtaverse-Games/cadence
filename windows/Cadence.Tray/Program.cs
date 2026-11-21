namespace Cadence.Tray;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(true, @"Local\Cadence.Tray", out var created);
        if (!created) return;
        try
        {
            ApplicationConfiguration.Initialize();
            using var context = new TrayContext(TimeProvider.System);
            Application.Run(context);
        }
        finally { mutex.ReleaseMutex(); }
    }
}
