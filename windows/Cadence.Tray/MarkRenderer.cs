using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal static class MarkRenderer
{
    public static Bitmap Draw(int size, TrayState? state)
    {
        var bitmap = new Bitmap(size, size);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
        var geometry = state is { } value ? CadenceMark.Tray(value) : CadenceMark.App;
        graphics.ScaleTransform(size / (float)geometry.ViewBox, size / (float)geometry.ViewBox);
        using var ground = new SolidBrush(DesignTheme.Ground);
        if (state is null) graphics.FillRectangle(ground, 0, 0, 100, 100);
        else
        {
            using var plate = Rounded(new(1, 1, 30, 30), 7);
            graphics.FillPath(ground, plate);
        }
        var center = (float)geometry.Center;
        var radius = (float)geometry.Radius;
        var bounds = new RectangleF(center - radius, center - radius, radius * 2, radius * 2);
        if (state is null)
        {
            using var basePen = new Pen(DesignTheme.Surface2, (float)geometry.Stroke);
            graphics.DrawEllipse(basePen, bounds);
        }
        foreach (var arc in geometry.Arcs)
        {
            var color = state is { } trayState ? DesignTheme.StateColor(trayState) : arc.Focus ? DesignTheme.Focus : DesignTheme.Free;
            using var pen = new Pen(color, (float)geometry.Stroke) { StartCap = LineCap.Flat, EndCap = LineCap.Flat };
            graphics.DrawArc(pen, bounds, (float)arc.Start, (float)arc.Sweep);
        }
        graphics.TranslateTransform(center, center);
        graphics.RotateTransform(45);
        var side = (float)geometry.DiamondSize;
        using var diamond = Rounded(new(-side / 2, -side / 2, side, side), (float)geometry.DiamondRadius);
        using var core = new SolidBrush(state is null ? DesignTheme.Text : state == TrayState.Free ? DesignTheme.Ground : DesignTheme.StateColor(state.Value));
        graphics.FillPath(core, diamond);
        if (geometry.DiamondStroke > 0)
        {
            using var outline = new Pen(DesignTheme.Free, (float)geometry.DiamondStroke);
            graphics.DrawPath(outline, diamond);
        }
        return bitmap;
    }

    public static Icon Icon(int size, TrayState? state)
    {
        using var bitmap = Draw(size, state);
        var handle = bitmap.GetHicon();
        try
        {
            using var original = System.Drawing.Icon.FromHandle(handle);
            return (Icon)original.Clone();
        }
        finally { _ = DestroyIcon(handle); }
    }

    private static GraphicsPath Rounded(RectangleF rect, float radius)
    {
        var path = new GraphicsPath();
        var diameter = radius * 2;
        path.AddArc(rect.X, rect.Y, diameter, diameter, 180, 90);
        path.AddArc(rect.Right - diameter, rect.Y, diameter, diameter, 270, 90);
        path.AddArc(rect.Right - diameter, rect.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(rect.X, rect.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }

    // The notification area belongs to the primary taskbar, not the settings window.
    // Recheck on every existing display tick so moving/rescaling the taskbar also
    // replaces the raster, even while the settings window is closed.
    public static int TraySize()
    {
        var dpi = GetDpiForWindow(FindWindow("Shell_TrayWnd", null));
        return dpi == 0 ? SystemInformation.SmallIconSize.Width : GetSystemMetricsForDpi(49, dpi);
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr handle);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr FindWindow(string className, string? title);
    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr window);
    [DllImport("user32.dll")]
    private static extern int GetSystemMetricsForDpi(int index, uint dpi);
}
