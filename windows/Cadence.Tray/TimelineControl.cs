using System.Drawing.Drawing2D;
using System.Globalization;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal sealed class TimelineControl : Control
{
    private TodayTimeline? timeline;
    public TimelineControl()
    {
        DoubleBuffered = true;
        Height = 76;
        TabStop = false;
        AccessibleName = "今日の時間割";
    }
    public void SetTimeline(TodayTimeline? value)
    {
        timeline = value;
        AccessibleDescription = value is null ? "今日の予定はありません" :
            $"{Time(value.Start)} から {Time(value.End)}。斜線は集中、無地は自由。";
        Invalidate();
    }
    private static string Time(DateTimeOffset at) => at.ToOffset(TimeSpan.FromHours(9)).ToString("HH:mm", CultureInfo.InvariantCulture);
    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var scale = DeviceDpi / 96f;
        var top = (int)(24 * scale);
        TextRenderer.DrawText(e.Graphics, timeline is null ? "今日の予定はありません" : "今日の時間割", Font,
            new Point(0, 0), DesignTheme.Muted);
        if (timeline is not { } value) return;
        var height = 12 * scale;
        var width = ClientSize.Width - 2;
        foreach (var segment in value.Segments)
        {
            var x = (float)((segment.Start - value.Start).TotalSeconds / (value.End - value.Start).TotalSeconds * width);
            var end = (float)((segment.End - value.Start).TotalSeconds / (value.End - value.Start).TotalSeconds * width);
            using Brush brush = segment.Blocked
                ? new HatchBrush(HatchStyle.BackwardDiagonal, DesignTheme.Focus, DesignTheme.HighContrast ? SystemColors.Window : Color.FromArgb(204, DesignTheme.Focus))
                : new SolidBrush(DesignTheme.HighContrast ? SystemColors.WindowText : Color.FromArgb(140, DesignTheme.Free));
            e.Graphics.FillRectangle(brush, x, top, Math.Max(1, end - x - scale), height);
        }
        if (value.NowFraction is { } fraction)
        {
            using var pen = new Pen(DesignTheme.Text, 2 * scale);
            var x = (float)fraction * width;
            e.Graphics.DrawLine(pen, x, top - 4 * scale, x, top + height + 4 * scale);
        }
        TextRenderer.DrawText(e.Graphics, Time(value.Start), Font, new Point(0, (int)(top + height + 6 * scale)), DesignTheme.Muted);
        TextRenderer.DrawText(e.Graphics, Time(value.End), Font,
            new Rectangle(0, (int)(top + height + 6 * scale), ClientSize.Width, (int)(24 * scale)), DesignTheme.Muted, TextFormatFlags.Right);
    }
}
