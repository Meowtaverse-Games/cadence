using System.Runtime.InteropServices;

namespace Cadence.Tray;

// Keep WS_THICKFRAME/WS_SYSMENU and the native sizing/moving loop. Only replace
// caption layout. Coordinates from messages are signed physical screen pixels.
internal class ChromeForm : Form
{
    private readonly CaptionButton minimize = new(0);
    private readonly CaptionButton maximize = new(1);
    private readonly CaptionButton close = new(2);
    private bool maxPressed;
    protected int ScalePixel(int value) => (int)Math.Round(value * DeviceDpi / 96d);

    public ChromeForm()
    {
        FormBorderStyle = FormBorderStyle.Sizable;
        Controls.AddRange([minimize, maximize, close]);
        minimize.Click += (_, _) => WindowState = FormWindowState.Minimized;
        maximize.Click += (_, _) => ToggleMaximize();
        close.Click += (_, _) => Close();
        maximize.PassHitTest = true;
    }

    private void ToggleMaximize() => WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;

    protected override void OnLayout(LayoutEventArgs levent)
    {
        base.OnLayout(levent);
        if (close is null) return;
        var size = ScalePixel(48);
        minimize.SetBounds(ClientSize.Width - size * 3, 0, size, size);
        maximize.SetBounds(ClientSize.Width - size * 2, 0, size, size);
        close.SetBounds(ClientSize.Width - size, 0, size, size);
        maximize.Restore = WindowState == FormWindowState.Maximized;
        maximize.AccessibleName = maximize.Restore ? "元に戻す" : "最大化";
        foreach (var button in new[] { minimize, maximize, close }) { button.BringToFront(); button.Invalidate(); }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        var round = 2; // DWMWCP_ROUND; unsupported attributes simply return HRESULT.
        _ = DwmSetWindowAttribute(Handle, 33, ref round, sizeof(int));
        var margins = new Margins { Bottom = 1 };
        _ = DwmExtendFrameIntoClientArea(Handle, ref margins);
        _ = SetWindowPos(Handle, IntPtr.Zero, 0, 0, 0, 0, 0x37); // FRAMECHANGED, no move/size/z-order/activate
    }

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        if (keyData == (Keys.Alt | Keys.Space))
        {
            var point = PointToScreen(new Point(0, ScalePixel(48)));
            var command = TrackPopupMenu(GetSystemMenu(Handle, false), 0x100, point.X, point.Y, 0, Handle, IntPtr.Zero);
            if (command != 0) _ = SendMessage(Handle, 0x112, (IntPtr)command, IntPtr.Zero);
            return true;
        }
        return base.ProcessCmdKey(ref msg, keyData);
    }

    private static Point ScreenPoint(IntPtr value) => new(unchecked((short)(value.ToInt64() & 0xffff)), unchecked((short)((value.ToInt64() >> 16) & 0xffff)));

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == 0x83) // WM_NCCALCSIZE: first RECT is present in both payload variants.
        {
            var original = Marshal.PtrToStructure<NativeRect>(m.LParam);
            base.WndProc(ref m); // Retain native left/right/bottom resize insets.
            var client = Marshal.PtrToStructure<NativeRect>(m.LParam);
            client.Top = original.Top;
            if (IsZoomed(Handle))
            {
                // Maximize bounds include invisible resize borders. Clip against
                // this monitor's work area, including top/left taskbars.
                var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
                if (GetMonitorInfo(MonitorFromWindow(Handle, 2), ref info))
                {
                    client.Left = Math.Max(client.Left, info.Work.Left);
                    client.Top = Math.Max(client.Top, info.Work.Top);
                    client.Right = Math.Min(client.Right, info.Work.Right);
                    client.Bottom = Math.Min(client.Bottom, info.Work.Bottom);
                }
            }
            Marshal.StructureToPtr(client, m.LParam, false);
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == 0x84) // WM_NCHITTEST
        {
            var point = PointToClient(ScreenPoint(m.LParam));
            base.WndProc(ref m);
            if (WindowState != FormWindowState.Maximized)
            {
                // Preserve all native side/bottom hits. Top is now client space.
                if ((int)m.Result is >= 10 and <= 17) return;
                if (point.Y >= 0 && point.Y < ScalePixel(5))
                {
                    m.Result = (IntPtr)(point.X < ScalePixel(12) ? 13 : point.X >= ClientSize.Width - ScalePixel(12) ? 14 : 12);
                    return;
                }
            }
            m.Result = (IntPtr)(maximize.Bounds.Contains(point) ? 9 :
                minimize.Bounds.Contains(point) || close.Bounds.Contains(point) ? 1 :
                point.Y >= 0 && point.Y < ScalePixel(48) ? 2 : 1);
            return;
        }
        if (m.Msg == 0xA0) // WM_NCMOUSEMOVE; DWM receives HTMAXBUTTON for Win11 snap flyout.
        {
            maximize.Hot = (int)m.WParam == 9;
            var track = new TrackMouse { Size = Marshal.SizeOf<TrackMouse>(), Flags = 0x12, Window = Handle };
            _ = TrackMouseEvent(ref track);
            _ = DwmDefWindowProc(Handle, m.Msg, m.WParam, m.LParam, out _);
        }
        if (m.Msg == 0x2A2) maximize.Hot = false; // WM_NCMOUSELEAVE
        if (m.Msg == 0xA1 && (int)m.WParam == 9)
        {
            maxPressed = true;
            Capture = true;
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == 0x202 && maxPressed)
        {
            maxPressed = false;
            Capture = false;
            if (maximize.Bounds.Contains(PointToClient(Cursor.Position))) ToggleMaximize();
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == 0x215) maxPressed = false; // WM_CAPTURECHANGED
        base.WndProc(ref m);
    }

    [StructLayout(LayoutKind.Sequential)] private struct NativeRect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct Margins { public int Left, Right, Top, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct MonitorInfo { public int Size; public NativeRect Monitor, Work; public uint Flags; }
    [StructLayout(LayoutKind.Sequential)] private struct TrackMouse { public int Size, Flags; public IntPtr Window; public uint HoverTime; }
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll")] private static extern int DwmExtendFrameIntoClientArea(IntPtr window, ref Margins margins);
    [DllImport("dwmapi.dll")] private static extern bool DwmDefWindowProc(IntPtr window, int message, IntPtr wParam, IntPtr lParam, out IntPtr result);
    [DllImport("user32.dll")] private static extern bool IsZoomed(IntPtr window);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr window, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
    [DllImport("user32.dll")] private static extern bool TrackMouseEvent(ref TrackMouse track);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll")] private static extern IntPtr GetSystemMenu(IntPtr window, bool revert);
    [DllImport("user32.dll")] private static extern int TrackPopupMenu(IntPtr menu, uint flags, int x, int y, int reserved, IntPtr window, IntPtr rect);
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);
}

internal sealed class CaptionButton : Button
{
    private readonly int kind;
    private bool hot;
    public bool Restore { get; set; }
    public bool PassHitTest { get; set; }
    public bool Hot { get => hot; set { hot = value; Invalidate(); } }
    public CaptionButton(int kind)
    {
        this.kind = kind;
        AccessibleName = kind == 0 ? "最小化" : kind == 1 ? "最大化" : "閉じる（トレイに残る）";
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
    }
    protected override void WndProc(ref Message m)
    {
        if (m.Msg == 0x84 && PassHitTest) { m.Result = (IntPtr)(-1); return; }
        base.WndProc(ref m);
    }
    protected override void OnMouseEnter(EventArgs e) { Hot = true; base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { Hot = false; base.OnMouseLeave(e); }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Hot ? DesignTheme.HighContrast ? SystemColors.Highlight : kind == 2 ? Color.FromArgb(196, 43, 28) : DesignTheme.Surface2 : DesignTheme.Ground);
        var scale = DeviceDpi / 96f;
        e.Graphics.TranslateTransform(Width / 2f - 6 * scale, Height / 2f - 6 * scale);
        e.Graphics.ScaleTransform(scale, scale);
        using var pen = new Pen(Hot && (kind == 2 || DesignTheme.HighContrast) ? Color.White : DesignTheme.Muted, 1);
        if (kind == 0) e.Graphics.DrawLine(pen, 1, 6, 11, 6);
        else if (kind == 2) { e.Graphics.DrawLine(pen, 1.5f, 1.5f, 10.5f, 10.5f); e.Graphics.DrawLine(pen, 10.5f, 1.5f, 1.5f, 10.5f); }
        else if (Restore) { e.Graphics.DrawLines(pen, [new(4, 3), new(4, 1), new(11, 1), new(11, 8), new(9, 8)]); e.Graphics.DrawRectangle(pen, 1, 4, 7, 7); }
        else e.Graphics.DrawRectangle(pen, 1.5f, 1.5f, 9, 9);
        e.Graphics.ResetTransform();
        if (Focused) ControlPaint.DrawFocusRectangle(e.Graphics, Rectangle.Inflate(ClientRectangle, -4, -4));
    }
}
