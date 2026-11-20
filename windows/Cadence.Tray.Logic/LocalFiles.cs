using System.Text.Json;
using Cadence.Core;

namespace Cadence.Tray.Logic;

public sealed record FirebaseConfig(string ApiKey, string ProjectId)
{
    public static FirebaseConfig? Load(string path)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(path));
            var key = doc.RootElement.GetProperty("apiKey").GetString();
            var project = doc.RootElement.GetProperty("projectId").GetString();
            return string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(project)
                ? null : new FirebaseConfig(key, project);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or InvalidOperationException or KeyNotFoundException or ArgumentException)
        {
            return null;
        }
    }
}

public static class AtomicFile
{
    public static void Write(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                file.Write(bytes);
                file.Flush(flushToDisk: true);
            }
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public static void WriteJson<T>(string path, T value) => Write(path, JsonSerializer.SerializeToUtf8Bytes(value));
}

public sealed record TimetableCache(string Json, DateTimeOffset FetchedAt);

public sealed class LocalFiles(string directory)
{
    public string PathFor(string file) => Path.Combine(directory, file);

    public TimetableCache? LoadCache()
    {
        var cache = Read<TimetableCache>("timetable.json");
        return cache is not null && TimetableParser.Parse(cache.Json).Valid ? cache : null;
    }

    public void SaveCache(TimetableCache cache) => AtomicFile.WriteJson(PathFor("timetable.json"), cache);
    public string[] LoadExecutables() => (Read<string[]>("executables.json") ?? [])
        .Select(ExecutableNames.Normalize).OfType<string>().Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
    public void SaveExecutables(IEnumerable<string> names) => AtomicFile.WriteJson(PathFor("executables.json"), names);

    private T? Read<T>(string name)
    {
        try { return JsonSerializer.Deserialize<T>(File.ReadAllText(PathFor(name))); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or ArgumentException) { return default; }
    }
}
