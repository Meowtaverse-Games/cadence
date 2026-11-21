using System.Globalization;
using System.Text;

namespace Cadence.Tray.Logic;

public sealed class DailyLog(string directory, TimeProvider clock)
{
    private readonly object gate = new();
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

