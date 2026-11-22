using System.Runtime.InteropServices;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal sealed class DesignTheme : IDisposable
{
    public static bool HighContrast => SystemInformation.HighContrast;
    public static Color Ground => HighContrast ? SystemColors.Window : ColorTranslator.FromHtml("#0B1216");
    public static Color Surface => HighContrast ? SystemColors.Window : ColorTranslator.FromHtml("#131D22");
    public static Color Surface2 => HighContrast ? SystemColors.Control : ColorTranslator.FromHtml("#1A272D");
    public static Color Line => HighContrast ? SystemColors.WindowText : ColorTranslator.FromHtml("#2A3A41");
    public static Color Text => HighContrast ? SystemColors.WindowText : ColorTranslator.FromHtml("#E9F1EF");
    public static Color Muted => HighContrast ? SystemColors.WindowText : ColorTranslator.FromHtml("#9DB0AE");
    public static Color Focus => HighContrast ? SystemColors.Highlight : ColorTranslator.FromHtml("#FFB648");
    public static Color Free => HighContrast ? SystemColors.WindowText : ColorTranslator.FromHtml("#2FB9A5");
    public static Color Alert => HighContrast ? SystemColors.WindowText : ColorTranslator.FromHtml("#FF8A7A");
    public static Color StateColor(TrayState state) => state switch { TrayState.Focus => Focus, TrayState.Free => Free, _ => Alert };

    public Font Body { get; } = InstalledFont("Yu Gothic UI", "Segoe UI", 10);
    public Font Caption { get; } = InstalledFont("Yu Gothic UI", "Segoe UI", 9);
    public Font Mono { get; } = InstalledFont("Cascadia Mono", "Consolas", 9);
    public Font Tag { get; } = InstalledFont("Segoe UI Semibold", "Segoe UI", 9);
    public Font Heading { get; } = InstalledFont("Yu Gothic UI", "Segoe UI", 22, FontStyle.Bold);

    private static Font InstalledFont(string preferred, string fallback, float size, FontStyle style = FontStyle.Regular)
    {
        var font = new Font(preferred, size, style);
        if (string.Equals(font.Name, preferred, StringComparison.OrdinalIgnoreCase)) return font;
        font.Dispose();
        return new Font(fallback, size, style);
    }

    public void Apply(Control control)
    {
        control.BackColor = control is Button or CheckBox ? Surface2 : control is TextBoxBase or ListBox ? Surface : Ground;
        control.ForeColor = Text;
        if (control is Button button)
        {
            button.FlatStyle = HighContrast ? FlatStyle.System : FlatStyle.Flat;
            button.FlatAppearance.BorderColor = Line;
            button.FlatAppearance.MouseOverBackColor = Surface;
            button.FlatAppearance.MouseDownBackColor = Ground;
        }
        if (control is CheckBox check) check.FlatStyle = HighContrast ? FlatStyle.System : FlatStyle.Flat;
        if (control is TextBox text) text.BorderStyle = BorderStyle.FixedSingle;
        foreach (Control child in control.Controls) Apply(child);
        control.HandleCreated -= OnHandleCreated;
        control.HandleCreated += OnHandleCreated;
        if (control.IsHandleCreated) ApplyNative(control);
    }

    private static void OnHandleCreated(object? sender, EventArgs e) { if (sender is Control control) ApplyNative(control); }

    public static void ApplyNative(Control control)
    {
        if (control is Form)
        {
            var dark = HighContrast ? 0 : 1;
            try { _ = DwmSetWindowAttribute(control.Handle, 20, ref dark, sizeof(int)); }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }
        }
        // Use the Windows dark scrollbar theme; in high contrast let Windows choose.
        _ = SetWindowTheme(control.Handle, HighContrast ? null : "DarkMode_Explorer", null);
    }

    public void Dispose() { Body.Dispose(); Caption.Dispose(); Mono.Dispose(); Tag.Dispose(); Heading.Dispose(); }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    [DllImport("uxtheme.dll", CharSet = CharSet.Unicode)]
    private static extern int SetWindowTheme(IntPtr window, string? subAppName, string? subIdList);
}
