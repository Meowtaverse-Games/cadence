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
    private bool suppressMaxRelease;
    protected int ScalePixel(int value) => (int)Math.Round(value * DeviceDpi / 96d);

    public ChromeForm()
    {
        FormBorderStyle = FormBorderStyle.Sizable;
        minimize.TabIndex = 10; maximize.TabIndex = 11; close.TabIndex = 12;
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

    // Keep Alt+Space on the native SC_KEYMENU path: Windows maintains the
    // enabled/default commands for normal, maximized and minimized states.

    private static Point ScreenPoint(IntPtr value) => new(unchecked((short)(value.ToInt64() & 0xffff)), unchecked((short)((value.ToInt64() >> 16) & 0xffff)));

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == 0x317) // WM_PRINT: DefWindowProc otherwise paints an obsolete standard caption.
        {
            m.LParam = (IntPtr)(m.LParam.ToInt64() & ~2L); // PRF_NONCLIENT; client includes our caption buttons.
            base.WndProc(ref m);
            return;
        }
        if (m.Msg == 0x83) // WM_NCCALCSIZE: first RECT is present in both payload variants.
        {
            var original = Marshal.PtrToStructure<NativeRect>(m.LParam);
            base.WndProc(ref m); // Retain native left/right/bottom resize insets.
            var client = Marshal.PtrToStructure<NativeRect>(m.LParam);
            client.Top = original.Top;
            if (IsZoomed(Handle))
            {
                // rgrc[0] is the proposed screen rectangle. The HWND can still
                // belong to the source monitor during Win+Shift+Arrow / DPI moves.
                var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
                if (GetMonitorInfo(MonitorFromRect(ref original, 2), ref info))
                {
                    client.Left = Math.Max(client.Left, info.Work.Left);
                    client.Top = Math.Max(client.Top, info.Work.Top);
                    client.Right = Math.Min(client.Right, info.Work.Right);
                    client.Bottom = Math.Min(client.Bottom, info.Work.Bottom);
                    ReserveAutoHideEdge(ref client, info);
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
        if (m.Msg == 0xA3 && (int)m.WParam == 9) // WM_NCLBUTTONDBLCLK
        {
            // The first click already toggled. Consume the second down/up pair
            // instead of invoking the default caption-button tracking/painting.
            maxPressed = false;
            suppressMaxRelease = true;
            Capture = true;
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == 0x202 && suppressMaxRelease)
        {
            suppressMaxRelease = false;
            Capture = false;
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == 0xA2 && (int)m.WParam == 9) // Uncaptured WM_NCLBUTTONUP
        {
            m.Result = IntPtr.Zero;
            return;
        }
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
        if (m.Msg == 0x215) { maxPressed = false; suppressMaxRelease = false; } // WM_CAPTURECHANGED
        base.WndProc(ref m);
    }

    private static void ReserveAutoHideEdge(ref NativeRect client, MonitorInfo info)
    {
        var data = new AppBarData { Size = Marshal.SizeOf<AppBarData>() };
        if ((SHAppBarMessage(4, ref data).ToUInt64() & 1) == 0) return; // ABM_GETSTATE / ABS_AUTOHIDE
        for (uint edge = 0; edge < 4; edge++)
        {
            data = new AppBarData { Size = Marshal.SizeOf<AppBarData>(), Edge = edge, Rect = info.Monitor };
            if (SHAppBarMessage(11, ref data) == UIntPtr.Zero) continue; // ABM_GETAUTOHIDEBAREX
            // Physical pixels, independent of DPI. Do not double-inset an edge
            // for which the work area already reserves enough space.
            switch (edge)
            {
                case 0: client.Left = Math.Max(client.Left, info.Monitor.Left + 2); break;
                case 1: client.Top = Math.Max(client.Top, info.Monitor.Top + 2); break;
                case 2: client.Right = Math.Min(client.Right, info.Monitor.Right - 2); break;
                case 3: client.Bottom = Math.Min(client.Bottom, info.Monitor.Bottom - 2); break;
            }
        }
    }

    [StructLayout(LayoutKind.Sequential)] private struct AppBarData
    {
        public int Size;
        public IntPtr Window;
        public uint Callback, Edge;
        public NativeRect Rect;
        public IntPtr Parameter;
    }
    [StructLayout(LayoutKind.Sequential)] private struct NativeRect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct Margins { public int Left, Right, Top, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct MonitorInfo { public int Size; public NativeRect Monitor, Work; public uint Flags; }
    [StructLayout(LayoutKind.Sequential)] private struct TrackMouse { public int Size, Flags; public IntPtr Window; public uint HoverTime; }
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll")] private static extern int DwmExtendFrameIntoClientArea(IntPtr window, ref Margins margins);
    [DllImport("dwmapi.dll")] private static extern bool DwmDefWindowProc(IntPtr window, int message, IntPtr wParam, IntPtr lParam, out IntPtr result);
    [DllImport("user32.dll")] private static extern bool IsZoomed(IntPtr window);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromRect(ref NativeRect rect, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
    [DllImport("user32.dll")] private static extern bool TrackMouseEvent(ref TrackMouse track);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, uint flags);
    [DllImport("shell32.dll")] private static extern UIntPtr SHAppBarMessage(uint message, ref AppBarData data);
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
        e.Graphics.Clear(Hot ? DesignTheme.HighContrast ? SystemColors.Highlight : kind == 2 ? Color.FromArgb(196, 43, 28) : DesignTheme.Surface2 : DesignTheme.HighContrast ? SystemColors.ButtonFace : DesignTheme.Ground);
        var scale = DeviceDpi / 96f;
        e.Graphics.TranslateTransform(Width / 2f - 6 * scale, Height / 2f - 6 * scale);
        e.Graphics.ScaleTransform(scale, scale);
        using var pen = new Pen(Hot && DesignTheme.HighContrast ? SystemColors.HighlightText : Hot && kind == 2 ? Color.White : DesignTheme.HighContrast ? SystemColors.ControlText : DesignTheme.Muted, 1);
        if (kind == 0) e.Graphics.DrawLine(pen, 1, 6, 11, 6);
        else if (kind == 2) { e.Graphics.DrawLine(pen, 1.5f, 1.5f, 10.5f, 10.5f); e.Graphics.DrawLine(pen, 10.5f, 1.5f, 1.5f, 10.5f); }
        else if (Restore) { e.Graphics.DrawLines(pen, new PointF[] { new(4, 3), new(4, 1), new(11, 1), new(11, 8), new(9, 8) }); e.Graphics.DrawRectangle(pen, 1, 4, 7, 7); }
        else e.Graphics.DrawRectangle(pen, 1.5f, 1.5f, 9, 9);
        e.Graphics.ResetTransform();
        if (Focused) ControlPaint.DrawFocusRectangle(e.Graphics, Rectangle.Inflate(ClientRectangle, -4, -4));
    }
}
