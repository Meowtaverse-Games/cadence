using System.Drawing.Imaging;
using System.Globalization;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal static class DesignPreview
{
    // Deliberately separate from TrayContext: no local settings/session/cache stores,
    // startup registry, real processes, Firebase client or system clock are created.
    public static void Generate(string directory)
    {
        Directory.CreateDirectory(directory);
        using (var theme = new DesignTheme())
        using (var bitmap = new Bitmap(640, 300))
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.Clear(DesignTheme.Ground);
            var sizes = new[] { 16, 20, 24, 32 };
            for (var background = 0; background < 2; background++)
            {
                using var brush = new SolidBrush(background == 0 ? Color.FromArgb(31, 31, 31) : Color.FromArgb(243, 243, 243));
                graphics.FillRectangle(brush, 0, background * 150, 640, 150);
                foreach (var state in Enum.GetValues<TrayState>())
                {
                    var x = (int)state * 210 + 12;
                    graphics.DrawString(state.ToString().ToUpperInvariant(), theme.Tag, background == 0 ? Brushes.White : Brushes.Black, x, background * 150 + 12);
                    for (var i = 0; i < sizes.Length; i++)
                    {
                        using var mark = MarkRenderer.Draw(sizes[i], state);
                        graphics.DrawImageUnscaled(mark, x + i * 46, background * 150 + 48);
                        graphics.DrawString(sizes[i].ToString(CultureInfo.InvariantCulture), theme.Mono, background == 0 ? Brushes.White : Brushes.Black, x + i * 46, background * 150 + 94);
                    }
                }
            }
            bitmap.Save(Path.Combine(directory, "tray-icons.png"), ImageFormat.Png);
        }
        const string json = """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[{"id":"preview","name":"合成の予定","enabled":true,"weekdays":[1,2,3,4,5,6,7],"start":"19:00","end":"22:00","segments":[{"kind":"free","minutes":10},{"kind":"block","minutes":20}]}]}""";
        var now = DateTimeOffset.Parse("2026-01-05T19:20:00+09:00", CultureInfo.InvariantCulture);
        var snapshots = new[] {
            new SettingsSnapshot(TrayState.Focus, "集中の時間 · 19:30 まで", TrayPresentation.Synchronization(true, now.AddMinutes(-1), false), "user@example.com", true, "接続設定を読み込みました（合成データ）", "", TodayTimeline.Build(json, now), NextChange: now.AddMinutes(10), LastSync: now.AddMinutes(-1), Now: now),
            new SettingsSnapshot(TrayState.Free, "自由時間 · 次は 19:10 から", TrayPresentation.Synchronization(true, now.AddMinutes(-20), false), null, true, "接続設定を読み込みました（合成データ）", "", TodayTimeline.Build(json, now.AddMinutes(-15)), NextChange: now.AddMinutes(-10), LastSync: now.AddMinutes(-20), Now: now.AddMinutes(-15)),
            new SettingsSnapshot(TrayState.Alert, "時間割を読み込めません", TrayPresentation.Synchronization(false, null, true), null, false, "接続設定がありません（合成データ）", "サインインが必要です", null, false)
        };
        foreach (var snapshot in snapshots)
        {
            using var form = SettingsForm.Preview(snapshot);
            form.SelectPage(0);
            form.Show();
            Application.DoEvents();
            using var bitmap = new Bitmap(form.Width, form.Height);
            form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
            bitmap.Save(Path.Combine(directory, "settings-" + snapshot.State.ToString().ToLowerInvariant() + ".png"), ImageFormat.Png);
            form.Close();
        }
        foreach (var page in new[] { 1, 2, 3 })
        {
            using var form = SettingsForm.Preview(page == 2 ? snapshots[1] : page == 3 ? snapshots[2] : snapshots[0]);
            form.SelectPage(page);
            form.Show();
            Application.DoEvents();
            using var bitmap = new Bitmap(form.Width, form.Height);
            form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
            bitmap.Save(Path.Combine(directory, "settings-page-" + page + ".png"), ImageFormat.Png);
            form.Close();
        }
        using (var form = SettingsForm.Preview(snapshots[0]))
        {
            form.SelectPage(1);
            form.Size = form.MinimumSize;
            form.Show();
            Application.DoEvents();
            form.ScrollPreviewToRightEdge();
            Application.DoEvents();
            using var bitmap = new Bitmap(form.Width, form.Height);
            form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
            bitmap.Save(Path.Combine(directory, "settings-minimum-right.png"), ImageFormat.Png);
            form.Close();
        }
    }
}
