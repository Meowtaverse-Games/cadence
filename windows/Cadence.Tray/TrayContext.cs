using System.Runtime.InteropServices;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal sealed class TrayContext : ApplicationContext
{
    private readonly TimeProvider clock;
    private readonly LocalFiles files;
    private readonly DailyLog log;
    private readonly SyncService service;
    private FirebaseConfigLoadResult configuration;
    private readonly string directlyUnderAppDataPath;
    private readonly bool openSettingsOnStartup;
    private readonly ProcessMonitor monitor;
    private readonly List<string> executables;
    private readonly NotifyIcon tray;
    private readonly ToolStripMenuItem stateMenu = new() { Enabled = false };
    private readonly System.Windows.Forms.Timer blockTimer = new() { Interval = 2000 };
    private readonly System.Windows.Forms.Timer syncTimer = new() { Interval = 60000 };
    private readonly CancellationTokenSource shutdown = new();
    private readonly Icon blockedIcon = MakeIcon(Color.Firebrick);
    private readonly Icon freeIcon = MakeIcon(Color.SeaGreen);
    private readonly Icon problemIcon = MakeIcon(Color.DarkOrange);
    private SettingsForm? settings;
    private bool syncing;
    private bool exiting;
    private bool initialSync = true;

    public TrayContext(TimeProvider clock)
    {
        this.clock = clock;
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Cadence");
        files = new(directory);
        log = new(files.PathFor("logs"), clock);
        log.Write("started");
        executables = files.LoadExecutables().ToList();
        directlyUnderAppDataPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "AppData", "Cadence", "cadence-firebase.json");
        configuration = FirebaseConfig.Load(files.PathFor("cadence-firebase.json"), directlyUnderAppDataPath);
        log.Write(configuration.Code);
        service = new(configuration.Config is { } config ? new FirebaseClient(config, clock, new HttpClientHandler()) : null,
            new DpapiSessionStore(files.PathFor("session.dat")), files.LoadCache(), files.SaveCache, clock, log.Write);
        openSettingsOnStartup = StartupSettings.ShouldOpen(configuration.Usable, service.HasSavedSession);
        var menu = new ContextMenuStrip();
        menu.Items.Add(stateMenu);
        menu.Items.Add("設定...", null, (_, _) => OpenSettings());
        menu.Items.Add("今すぐ同期", null, async (_, _) => await SynchronizeAsync());
        menu.Items.Add("終了", null, (_, _) => ExitThread());
        tray = new() { Visible = true, Icon = problemIcon, ContextMenuStrip = menu, Text = "Cadence" };
        tray.DoubleClick += (_, _) => OpenSettings();
        monitor = new(clock, log, Notify);
        blockTimer.Tick += async (_, _) =>
        {
            try
            {
                monitor.Tick(service.State.Timetable, executables);
                UpdateDisplay();
                if (initialSync)
                {
                    initialSync = false;
                    await SynchronizeAsync();
                }
            }
            catch { log.Write("tray_tick_failed"); }
        };
        syncTimer.Tick += async (_, _) => await SynchronizeAsync();
        UpdateDisplay();
        blockTimer.Start();
        syncTimer.Start();
        Application.Idle += OnFirstIdle;
    }

    private void OnFirstIdle(object? sender, EventArgs e)
    {
        // Run only after construction and inside the first message loop.
        Application.Idle -= OnFirstIdle;
        if (!exiting && openSettingsOnStartup) OpenSettings();
    }

    private async Task ReloadConfigurationAsync()
    {
        configuration = await service.ReloadConfigurationAsync(files.PathFor("cadence-firebase.json"),
            directlyUnderAppDataPath, cancellation: shutdown.Token);
        if (!exiting) UpdateDisplay();
    }

    private async Task SynchronizeAsync()
    {
        if (syncing || exiting) return;
        syncing = true;
        try { await service.SyncAsync(shutdown.Token); }
        catch (OperationCanceledException) { }
        catch { log.Write("sync_failed"); }
        finally
        {
            syncing = false;
            if (!exiting) UpdateDisplay();
        }
    }

    private void Notify(string title, string message, bool warning)
    {
        if (!exiting) tray.ShowBalloonTip(5000, title, message, warning ? ToolTipIcon.Warning : ToolTipIcon.Info);
    }

    private string DisplayState()
    {
        var block = ScheduleStatus.Evaluate(service.State.Timetable, clock);
        var current = service.State.Timetable is null ? "時間割なし" : block.Blocked ? "ブロック中" : "自由時間";
        var problem = !configuration.Usable ? configuration.Message
            : service.AuthProblem ?? service.State.Problem ?? service.StorageProblem;
        return problem is null ? current : current + " / " + problem;
    }

    private void UpdateDisplay()
    {
        var block = ScheduleStatus.Evaluate(service.State.Timetable, clock);
        var state = DisplayState();
        tray.Icon = service.State.Timetable is null || service.AuthProblem is not null || service.State.Problem is not null || service.StorageProblem is not null
            ? problemIcon : block.Blocked ? blockedIcon : freeIcon;
        tray.Text = ScheduleStatus.Tooltip(state, block, service.State.LastSync, clock);
        stateMenu.Text = state;
        settings?.RefreshStatus(state, service, configuration);
        if (service.TakeAuthNotification()) Notify("サインインが必要です", "設定画面でサインインしてください。保存済みの時間割でブロックを続けます。", true);
    }

    private void OpenSettings()
    {
        if (settings is null || settings.IsDisposed)
        {
            settings = new SettingsForm(service, executables, SaveExecutables, UpdateDisplay, log,
                Path.GetDirectoryName(configuration.Path)!, ReloadConfigurationAsync);
            settings.FormClosed += (_, _) => settings = null;
        }
        UpdateDisplay();
        settings.Show();
        settings.Activate();
    }

    private bool SaveExecutables(List<string> proposed)
    {
        try
        {
            files.SaveExecutables(proposed);
            executables.Clear();
            executables.AddRange(proposed);
            return true;
        }
        catch
        {
            log.Write("executables_save_failed");
            Notify("設定を保存できません", "保存先の権限や空き容量を確認してください。", true);
            return false;
        }
    }

    protected override void ExitThreadCore()
    {
        exiting = true;
        Application.Idle -= OnFirstIdle;
        shutdown.Cancel();
        blockTimer.Stop();
        syncTimer.Stop();
        settings?.Close();
        tray.Visible = false;
        base.ExitThreadCore();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Application.Idle -= OnFirstIdle;
            blockTimer.Dispose();
            syncTimer.Dispose();
            tray.ContextMenuStrip?.Dispose();
            tray.Dispose();
            blockedIcon.Dispose();
            freeIcon.Dispose();
            problemIcon.Dispose();
            service.Dispose();
            shutdown.Dispose();
        }
        base.Dispose(disposing);
    }

    private static Icon MakeIcon(Color color)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(color);
            graphics.FillEllipse(brush, 3, 3, 26, 26);
            using var pen = new Pen(Color.White, 2);
            graphics.DrawEllipse(pen, 3, 3, 26, 26);
        }
        var handle = bitmap.GetHicon();
        try
        {
            using var original = Icon.FromHandle(handle);
            return (Icon)original.Clone();
        }
        finally { DestroyIcon(handle); }
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr handle);
}
