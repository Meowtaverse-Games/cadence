using System.Drawing.Drawing2D;
using System.Globalization;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal sealed class TimelineControl : Control
{
    private TodayTimeline? timeline;
    private double? nowFraction;
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
        nowFraction = value?.NowFraction;
        AccessibleDescription = value is null ? "今日の予定はありません" :
            $"{Time(value.Start)} から {Time(value.End)}。斜線は集中、無地は自由。";
        Invalidate();
    }
    public void SetNow(DateTimeOffset now)
    {
        var fraction = timeline is { } value && now >= value.Start && now < value.End
            ? (double?)((now - value.Start).TotalSeconds / (value.End - value.Start).TotalSeconds) : null;
        if (fraction == nowFraction) return;
        nowFraction = fraction;
        Invalidate();
    }
    private static string Time(DateTimeOffset at) => at.ToOffset(TimeSpan.FromHours(9)).ToString("HH:mm", CultureInfo.InvariantCulture);
    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var scale = DeviceDpi / 96f;
        var top = (int)(8 * scale);
        if (timeline is not { } value) return;
        var height = 18 * scale;
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var width = ClientSize.Width - 2;
        foreach (var segment in value.Segments)
        {
            var x = (float)((segment.Start - value.Start).TotalSeconds / (value.End - value.Start).TotalSeconds * width);
            var end = (float)((segment.End - value.Start).TotalSeconds / (value.End - value.Start).TotalSeconds * width);
            using Brush brush = segment.Blocked
                ? new HatchBrush(HatchStyle.BackwardDiagonal, DesignTheme.Focus, DesignTheme.HighContrast ? SystemColors.Window : Color.FromArgb(204, DesignTheme.Focus))
                : new SolidBrush(DesignTheme.HighContrast ? SystemColors.WindowText : Color.FromArgb(140, DesignTheme.Free));
            using var path = Shapes.Round(new RectangleF(x, top, Math.Max(1, end - x - 3 * scale), height), 6 * scale);
            e.Graphics.FillPath(brush, path);
        }
        if (nowFraction is { } fraction)
        {
            using var pen = new Pen(DesignTheme.Text, 2 * scale);
            var x = (float)fraction * width;
            e.Graphics.DrawLine(pen, x, top - 4 * scale, x, top + height + 4 * scale);
        }
    }
}
