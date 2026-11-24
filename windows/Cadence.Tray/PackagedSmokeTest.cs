using System.Runtime.CompilerServices;
using Cadence.Core;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal static class PackagedSmokeTest
{
    public static int Run(string expectedVersion)
    {
        try
        {
            Verify(expectedVersion);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    // Keep dependency resolution/JIT failures inside Run's exception handler.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void Verify(string expectedVersion)
    {
        Console.WriteLine(typeof(TimetableParser).Assembly.FullName);
        Console.WriteLine(typeof(LocalFiles).Assembly.FullName);
        foreach (var reference in typeof(LocalFiles).Assembly.GetReferencedAssemblies())
            Console.WriteLine("Logic reference: " + reference.FullName);

        const string json = """
            {"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[]}
            """;
        if (!TimetableParser.Parse(json).Valid)
            throw new InvalidOperationException("Synthetic timetable validation failed.");

        var directory = Path.Combine(Path.GetTempPath(), "Cadence-smoke-" + Guid.NewGuid().ToString("N"));
        try
        {
            var files = new LocalFiles(directory);
            var cache = new TimetableCache(json, DateTimeOffset.UnixEpoch);
            files.SaveCache(cache);
            if (files.LoadCache() != cache)
                throw new InvalidOperationException("Synthetic timetable cache round-trip failed.");
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }

        if (AppUpdater.BuildVersion != expectedVersion)
            throw new InvalidOperationException($"Expected version {expectedVersion}, got {AppUpdater.BuildVersion}.");
        Console.WriteLine("Packaged smoke test passed: " + AppUpdater.BuildVersion);
    }
}
