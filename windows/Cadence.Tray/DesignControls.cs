using System.Drawing.Drawing2D;

namespace Cadence.Tray;

internal static class Shapes
{
    public static GraphicsPath Round(RectangleF bounds, float radius)
    {
        var path = new GraphicsPath();
        var d = Math.Min(radius * 2, Math.Min(bounds.Width, bounds.Height));
        if (d <= 0) return path;
        path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
        path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
        path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
        path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }
}

internal class RoundPanel : Panel
{
    public int Radius { get; set; } = 28;
    public Color Fill { get; set; } = DesignTheme.Surface;
    public Color? Outline { get; set; }
    public RoundPanel() { DoubleBuffered = true; }
    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var path = Shapes.Round(new RectangleF(1, 1, Width - 2, Height - 2), Radius * DeviceDpi / 96f);
        using var brush = new SolidBrush(Fill);
        e.Graphics.FillPath(brush, path);
        if (Outline is { } color) { using var pen = new Pen(color); e.Graphics.DrawPath(pen, path); }
    }
}

internal class RoundButton : Button
{
    public bool Primary { get; set; }
    public bool Selected { get; set; }
    public bool Border { get; set; } = true;
    public bool Navigation { get; set; }
    private bool hot;
    public RoundButton() { SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true); }
    protected override void OnMouseEnter(EventArgs e) { hot = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hot = false; Invalidate(); base.OnMouseLeave(e); }
    protected override AccessibleObject CreateAccessibilityInstance() => new SelectionAccessibleObject(this);
    private sealed class SelectionAccessibleObject(RoundButton owner) : ButtonAccessibleObject(owner)
    {
        public override AccessibleStates State => base.State | (owner.Selected ? AccessibleStates.Selected : 0);
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(BackColor);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var fill = Primary ? DesignTheme.Focus : Selected || hot ? DesignTheme.Surface2 : BackColor;
        var text = !Enabled ? SystemColors.GrayText : Primary ? DesignTheme.HighContrast ? SystemColors.HighlightText : DesignTheme.Ground : Navigation && !Selected ? DesignTheme.Muted : DesignTheme.Text;
        using var path = Shapes.Round(new RectangleF(1, 1, Width - 2, Height - 2), Navigation ? 12 * DeviceDpi / 96f : Height / 2f);
        using var brush = new SolidBrush(fill);
        e.Graphics.FillPath(brush, path);
        if (Border && !Primary) { using var pen = new Pen(DesignTheme.Line); e.Graphics.DrawPath(pen, path); }
        TextRenderer.DrawText(e.Graphics, Text, Font, Rectangle.Inflate(ClientRectangle, -10, -2), text,
            TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | (Navigation ? TextFormatFlags.Left : TextFormatFlags.HorizontalCenter));
        if (Focused) ControlPaint.DrawFocusRectangle(e.Graphics, Rectangle.Inflate(ClientRectangle, -5, -5), text, fill);
    }
}

internal sealed class ToggleSwitch : CheckBox
{
    public ToggleSwitch() { AutoSize = false; Height = 48; SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true); }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(BackColor);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var s = DeviceDpi / 96f;
        using var path = Shapes.Round(new RectangleF(2 * s, 12 * s, 42 * s, 24 * s), 12 * s);
        using var brush = new SolidBrush(Checked ? DesignTheme.Focus : DesignTheme.Surface2);
        using var pen = new Pen(DesignTheme.Muted);
        e.Graphics.FillPath(brush, path);
        e.Graphics.DrawPath(pen, path);
        using var knob = new SolidBrush(Checked && !DesignTheme.HighContrast ? DesignTheme.Ground : DesignTheme.Text);
        e.Graphics.FillEllipse(knob, (Checked ? 25 : 7) * s, 17 * s, 14 * s, 14 * s);
        TextRenderer.DrawText(e.Graphics, Text, Font, new Rectangle((int)(58 * s), 0, Width - (int)(58 * s), Height), DesignTheme.Text, TextFormatFlags.VerticalCenter);
        if (Focused) ControlPaint.DrawFocusRectangle(e.Graphics, ClientRectangle);
    }
}

internal sealed class SidebarPanel : Panel
{
    protected override void WndProc(ref Message m)
    {
        if (m.Msg == 0x84)
        {
            var p = PointToClient(new Point(unchecked((short)(m.LParam.ToInt64() & 0xffff)), unchecked((short)((m.LParam.ToInt64() >> 16) & 0xffff))));
            if (p.Y < 48 * DeviceDpi / 96) { m.Result = (IntPtr)(-1); return; }
        }
        base.WndProc(ref m);
    }
}
