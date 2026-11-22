using System.Globalization;
using System.Text;

namespace Cadence.Tray.Logic;

public sealed class DailyLog(string directory, TimeProvider clock)
{
    private readonly object gate = new();
    public IReadOnlyList<ActivityEntry> Read(DateTimeOffset now)
    {
        lock (gate)
        {
            try
            {
                var date = DateOnly.FromDateTime(now.ToOffset(TimeSpan.FromHours(9)).Date);
                var path = Path.Combine(directory, date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + ".txt");
                using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                using var reader = new StreamReader(stream, Encoding.UTF8);
                var lines = new List<string>();
                while (reader.ReadLine() is { } line) lines.Add(line);
                return TodayActivity.Parse(lines, date);
            }
            catch { return []; }
        }
    }

    public void Write(string code)
    {
        lock (gate)
        {
            try
            {
                Directory.CreateDirectory(directory);
                var now = clock.GetUtcNow().ToOffset(TimeSpan.FromHours(9));
                foreach (var file in Directory.EnumerateFiles(directory, "*.txt"))
                    if (DateTime.TryParseExact(Path.GetFileNameWithoutExtension(file), "yyyy-MM-dd", CultureInfo.InvariantCulture,
                        DateTimeStyles.None, out var date) && date < now.Date.AddDays(-13)) File.Delete(file);
                var path = Path.Combine(directory, now.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + ".txt");
                // Never log exception messages, HTTP bodies, credentials, or account identifiers.
                File.AppendAllText(path, now.ToString("HH:mm:ss", CultureInfo.InvariantCulture) + " " + code + Environment.NewLine, Encoding.UTF8);
            }
            catch { /* Logging failure must not stop the tray or block loop. */ }
        }
    }
}
