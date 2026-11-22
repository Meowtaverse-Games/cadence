using System.Diagnostics;
using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal sealed record SettingsSnapshot(TrayState State, string Status, SyncSummary Sync, string? Email,
    bool Configured, string Connection, string Problem, TodayTimeline? Timeline, bool ConfigurationUsable = true);

internal sealed class SettingsForm : Form
{
    private readonly SyncService? service;
    private readonly List<string> executables;
    private readonly Func<List<string>, bool> saveExecutables;
    private readonly Action changed;
    private readonly DailyLog? log;
    private readonly Func<Task> reloadConfiguration;
    private readonly DesignTheme theme = new();
    private readonly Label tag = Label("");
    private readonly Label status = Label("");
    private readonly Label connection = Label("");
    private readonly Button reload = Button("接続設定を読み直す");
    private readonly Label account = Label("");
    private string syncSummary = "";
    private bool syncProblem;
    private bool? configurationUsable;
    private readonly Label emailHeading = Label("メールアドレス");
    private readonly Label passwordHeading = Label("パスワード");
    private readonly Label executableHeading = Label("追加する exe 名");
    private readonly Label readOnlyNotice = Label("時間割は iPhone で編集します（この PC は読み取り専用）");
    private readonly Button expand = Button("接続と詳細 ▸");
    private readonly Panel scroll = new() { Dock = DockStyle.Fill, AutoScroll = true, Padding = new Padding(24) };
    private readonly Label problem = Label("");
    private readonly Label error = Label("");
    private readonly TextBox email = new() { Dock = DockStyle.Top, PlaceholderText = "メールアドレス", AccessibleName = "メールアドレス" };
    private readonly TextBox password = new() { Dock = DockStyle.Top, PlaceholderText = "パスワード", UseSystemPasswordChar = true, AccessibleName = "パスワード" };
    private readonly Button signIn = Button("サインイン");
    private readonly Button signOut = Button("サインアウト");
    private readonly TableLayoutPanel list = Column();
    private readonly TextBox executable = new() { PlaceholderText = "example.exe", AccessibleName = "追加する exe 名" };
    private readonly CheckBox startup = new() { Text = "Windows のログオン時に起動する", AutoSize = true };
    private readonly TimelineControl timeline = new() { Dock = DockStyle.Top };
    private readonly TableLayoutPanel root = Column();
    private readonly TableLayoutPanel statusCard = new() { AutoSize = true, Dock = DockStyle.Top, ColumnCount = 2, Padding = new Padding(16), Margin = new Padding(0, 0, 0, 12) };
    private readonly TableLayoutPanel details = Column();
    private TrayState displayState;
    private bool highContrast = DesignTheme.HighContrast;
    private bool busy;

    public SettingsForm(SyncService service, List<string> executables, Func<List<string>, bool> saveExecutables,
        Action changed, DailyLog log, string configurationDirectory, Func<Task> reloadConfiguration)
        : this(service, executables, saveExecutables, changed, log, configurationDirectory, reloadConfiguration, false) { }

    private SettingsForm(SyncService? service, List<string> executables, Func<List<string>, bool> saveExecutables,
        Action changed, DailyLog? log, string configurationDirectory, Func<Task> reloadConfiguration, bool preview)
    {
        this.service = service;
        this.executables = executables;
        this.saveExecutables = saveExecutables;
        this.changed = changed;
        this.log = log;
        this.reloadConfiguration = reloadConfiguration;
        SuspendLayout();
        Text = "Cadence — 設定";
        Font = theme.Body;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96, 96);
        ClientSize = new Size(720, 600);
        MinimumSize = new Size(560, 420);
        StartPosition = FormStartPosition.CenterScreen;
        Icon = MarkRenderer.Icon(48, null);
        Controls.Add(scroll);
        root.Dock = DockStyle.None;
        root.AutoSize = false;
        root.Location = new Point(24, 24);
        root.MinimumSize = new Size(640, 0);
        scroll.Controls.Add(root);
        var arranging = false;
        void LayoutContent()
        {
            if (arranging) return;
            arranging = true;
            try
            {
                var width = Math.Max(root.MinimumSize.Width,
                    scroll.ClientSize.Width - scroll.Padding.Horizontal - SystemInformation.VerticalScrollBarWidth);
                root.Width = width;
                root.Height = root.GetPreferredSize(new Size(width, 0)).Height;
                scroll.AutoScrollMinSize = new Size(width + scroll.Padding.Horizontal, root.Height + scroll.Padding.Vertical);
            }
            finally { arranging = false; }
        }
        scroll.Layout += (_, _) => LayoutContent();
        root.Layout += (_, _) => LayoutContent();
        statusCard.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 65));
        statusCard.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 35));
        tag.Font = theme.Tag;
        status.Font = theme.Heading;
        statusCard.Controls.Add(tag, 0, 0);
        statusCard.Controls.Add(status, 0, 1);
        statusCard.SetColumnSpan(status, 2);
        statusCard.Paint += (_, e) =>
        {
            using var pen = new Pen(displayState == TrayState.Focus ? DesignTheme.Focus : DesignTheme.Line);
            e.Graphics.DrawRectangle(pen, 0, 0, statusCard.Width - 1, statusCard.Height - 1);
            // Keep the summary in the top row and reserve the full next row for Japanese.
            var scale = DeviceDpi / 96f;
            using var font = new Font(theme.Mono.FontFamily, theme.Mono.SizeInPoints * DeviceDpi / 72f, GraphicsUnit.Pixel);
            using var brush = new SolidBrush(syncProblem ? DesignTheme.Alert : DesignTheme.Muted);
            using var format = new StringFormat { Alignment = StringAlignment.Far };
            e.Graphics.DrawString(syncSummary, font, brush,
                new RectangleF(statusCard.Width / 2f, 20 * scale,
                    statusCard.Width / 2f - 16 * scale, 24 * scale), format);
        };
        root.Controls.Add(statusCard);
        root.Controls.Add(problem);
        var columns = new TableLayoutPanel { AutoSize = true, ColumnCount = 2, Dock = DockStyle.Top, Margin = new Padding(0, 12, 0, 12) };
        columns.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        columns.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        var left = Column();
        left.Margin = new Padding(0, 0, 12, 0);
        var right = Column();
        right.Margin = new Padding(12, 0, 0, 0);
        columns.Controls.Add(left, 0, 0);
        columns.Controls.Add(right, 1, 0);
        root.Controls.Add(columns);
        left.Controls.Add(Label("ブロックする exe", theme.Tag));
        left.Controls.Add(list);
        var add = Button("追加");
        var inputRow = new TableLayoutPanel { AutoSize = true, ColumnCount = 2, Dock = DockStyle.Top };
        inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        inputRow.Controls.Add(executable, 0, 0);
        inputRow.Controls.Add(add, 1, 0);
        executable.Dock = DockStyle.Top;
        executable.Font = theme.Mono;
        left.Controls.Add(executableHeading);
        left.Controls.Add(inputRow);
        var running = Button("実行中のプロセスから選ぶ…");
        left.Controls.Add(running);
        right.Controls.Add(Label("アカウントと起動", theme.Tag));
        account.Font = theme.Mono;
        right.Controls.AddRange([account, readOnlyNotice, emailHeading, email, passwordHeading, password, signIn, signOut, startup, timeline]);
        emailHeading.Font = passwordHeading.Font = executableHeading.Font = theme.Caption;
        email.Font = theme.Mono;
        timeline.Font = theme.Mono;
        expand.Click += (_, _) => SetDetailsVisible(!details.Visible);
        error.Visible = false;
        error.TextChanged += (_, _) => error.Visible = error.Text.Length > 0;
        root.Controls.AddRange([error, expand, details]);
        details.Controls.Add(Label("Cadence はタスクバー右の通知領域（「^」の中）に常駐します。この画面を閉じても動き続けます。開き直すにはアイコンをダブルクリックするか、右クリック →「設定...」を選びます。"));
        details.Controls.Add(connection);
        var openFolder = Button("設定フォルダを開く");
        details.Controls.AddRange([openFolder, reload]);
        details.Visible = false;
        root.Controls.Add(Label("サインアウトしても保存済みの時間割によるブロックは続きます。時刻はすべて日本時間です。"));
        ReloadExecutables();
        ApplyTheme();
        ResumeLayout(true);
        if (preview) return; // No AppData, registry, process enumeration or network in preview mode.
        openFolder.Click += (_, _) =>
        {
            try
            {
                Directory.CreateDirectory(configurationDirectory);
                Process.Start(new ProcessStartInfo { FileName = configurationDirectory, UseShellExecute = true });
            }
            catch { error.Text = "設定フォルダを開けません。フォルダの権限などを確認してください。"; log?.Write("config_folder_open_failed"); }
        };
        reload.Click += async (_, _) => await ReloadConfigurationAsync();
        signIn.Click += async (_, _) => await AuthenticateAsync(true);
        signOut.Click += async (_, _) => await AuthenticateAsync(false);
        add.Click += (_, _) => AddExecutable(executable.Text);
        running.Click += (_, _) => SelectRunning();
        try { startup.Checked = StartupRegistration.Enabled(); }
        catch { error.Text = "自動起動の設定を読み込めません。"; log?.Write("startup_read_failed"); }
        startup.CheckedChanged += (_, _) =>
        {
            try { StartupRegistration.Set(startup.Checked); }
            catch { error.Text = "自動起動の設定を保存できません。"; log?.Write("startup_write_failed"); startup.Enabled = false; }
        };
    }

    public void RefreshStatus(SettingsSnapshot snapshot)
    {
        if (IsDisposed) return;
        displayState = snapshot.State;
        tag.Text = string.Join(" ", snapshot.State.ToString().ToUpperInvariant().ToCharArray());
        status.Text = snapshot.Status;
        syncSummary = snapshot.Sync.Text;
        syncProblem = snapshot.Sync.IsProblem;
        statusCard.AccessibleName = "今の状態";
        statusCard.AccessibleDescription = snapshot.Status + "。" + snapshot.Sync.Text;
        connection.Text = snapshot.Connection;
        if (!snapshot.ConfigurationUsable && configurationUsable != false) SetDetailsVisible(true);
        configurationUsable = snapshot.ConfigurationUsable;
        problem.Text = snapshot.Problem;
        problem.Visible = problem.Text.Length > 0;
        account.Text = snapshot.Email is { } signedIn ? "サインイン中\n" + signedIn : "サインインしていません";
        emailHeading.Visible = passwordHeading.Visible = email.Visible = password.Visible = signIn.Visible = snapshot.Email is null;
        readOnlyNotice.Visible = snapshot.Email is not null;
        signOut.Visible = snapshot.Email is not null;
        signIn.Enabled = !busy && snapshot.Configured;
        signOut.Enabled = !busy && snapshot.Email is not null;
        reload.Enabled = !busy;
        timeline.SetTimeline(snapshot.Timeline);
        if (highContrast != DesignTheme.HighContrast) { highContrast = DesignTheme.HighContrast; ApplyTheme(); }
        tag.ForeColor = DesignTheme.StateColor(snapshot.State);
        statusCard.Invalidate();
    }

    private void SetDetailsVisible(bool visible)
    {
        details.Visible = visible;
        expand.Text = visible ? "接続と詳細 ▾" : "接続と詳細 ▸";
    }

    internal void ScrollPreviewToRightEdge()
    {
        PerformLayout();
        scroll.PerformLayout();
        if (!scroll.HorizontalScroll.Visible) throw new InvalidOperationException("Minimum-size preview requires horizontal scrolling.");
        scroll.AutoScrollPosition = new Point(scroll.HorizontalScroll.Maximum, 0);
        if (scroll.HorizontalScroll.Value == 0 || root.Right > scroll.ClientSize.Width - scroll.Padding.Right)
            throw new InvalidOperationException("The right edge of the settings content is unreachable.");
    }

    internal static SettingsForm Preview(SettingsSnapshot snapshot)
    {
        var form = new SettingsForm(null, ["game.exe", "launcher.exe", "video.exe", "chat.exe"], _ => false,
            () => { }, null, "", () => Task.CompletedTask, true);
        form.RefreshStatus(snapshot);
        return form;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        DesignTheme.ApplyNative(this);
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        base.OnDpiChanged(e);
        var oldIcon = Icon;
        Icon = MarkRenderer.Icon(Math.Max(32, 32 * DeviceDpi / 96), null);
        oldIcon?.Dispose();
    }

    protected override void Dispose(bool disposing)
    {
        try
        {
            if (disposing) Icon?.Dispose();
            base.Dispose(disposing);
        }
        finally { if (disposing) theme.Dispose(); }
    }

    private void ApplyTheme()
    {
        theme.Apply(this);
        if (IsHandleCreated)
        {
            DesignTheme.ApplyNative(this);
            var oldIcon = Icon;
            Icon = MarkRenderer.Icon(Math.Max(32, 32 * DeviceDpi / 96), null);
            oldIcon?.Dispose();
        }
        statusCard.BackColor = DesignTheme.Surface;
        foreach (Control child in statusCard.Controls)
        {
            child.BackColor = DesignTheme.Surface;
            foreach (Control nested in child.Controls) nested.BackColor = DesignTheme.Surface;
        }
        foreach (Control row in list.Controls)
        {
            row.BackColor = DesignTheme.Surface;
            foreach (Control child in row.Controls) child.BackColor = DesignTheme.Surface;
        }
        tag.ForeColor = DesignTheme.StateColor(displayState);
        connection.ForeColor = DesignTheme.Muted;
        emailHeading.ForeColor = passwordHeading.ForeColor = executableHeading.ForeColor = DesignTheme.Muted;
        error.ForeColor = problem.ForeColor = DesignTheme.Alert;
    }

    private async Task ReloadConfigurationAsync()
    {
        if (busy) return;
        busy = true;
        changed();
        error.Text = "";
        try { await reloadConfiguration(); }
        catch
        {
            if (!IsDisposed) error.Text = "接続設定を読み直せません。もう一度お試しください。";
            log?.Write("config_reload_failed");
        }
        finally { busy = false; if (!IsDisposed) changed(); }
    }

    private async Task AuthenticateAsync(bool signingIn)
    {
        if (busy) return;
        busy = true;
        changed();
        error.Text = "";
        try
        {
            if (signingIn)
            {
                var operation = service!.SignInAsync(email.Text.Trim(), password.Text);
                password.Clear();
                var message = await operation;
                if (!IsDisposed) error.Text = message ?? service!.StorageProblem ?? "";
            }
            else
            {
                await service!.SignOutAsync();
                if (!IsDisposed) error.Text = service!.StorageProblem ?? "";
            }
        }
        catch
        {
            if (!IsDisposed) error.Text = "操作を完了できません。もう一度お試しください。";
            log?.Write("account_operation_failed");
        }
        finally { busy = false; if (!IsDisposed) changed(); }
    }

    private void AddExecutable(string name)
    {
        var proposed = executables.ToList();
        if (!ExecutableNames.Add(proposed, name))
        {
            error.Text = "exe 名を確認してください。空欄、パス、重複は登録できません。";
            return;
        }
        if (saveExecutables(proposed))
        {
            error.Text = "";
            executable.Clear();
            ReloadExecutables();
        }
    }

    private void ReloadExecutables()
    {
        list.SuspendLayout();
        while (list.Controls.Count > 0) { var old = list.Controls[0]; list.Controls.Remove(old); old.Dispose(); }
        list.RowStyles.Clear();
        list.RowCount = 0;
        foreach (var name in executables)
        {
            var row = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Top, Padding = new Padding(10, 4, 4, 4), Margin = new Padding(0) };
            row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            var label = Label(name, theme.Mono);
            label.Anchor = AnchorStyles.Left | AnchorStyles.Right;
            label.Margin = new Padding(0);
            var remove = Button("✕");
            remove.AccessibleName = name + " を外す";
            remove.Padding = new Padding(0);
            remove.Margin = new Padding(0);
            remove.MinimumSize = new Size(32, 32);
            remove.Click += (_, _) =>
            {
                var proposed = executables.ToList();
                proposed.Remove(name);
                if (saveExecutables(proposed)) { ReloadExecutables(); executable.Focus(); }
            };
            row.Controls.Add(label, 0, 0);
            row.Controls.Add(remove, 1, 0);
            row.Paint += (_, e) => { using var pen = new Pen(DesignTheme.Line); e.Graphics.DrawLine(pen, 0, row.Height - 1, row.Width, row.Height - 1); };
            theme.Apply(row);
            row.BackColor = label.BackColor = remove.BackColor = DesignTheme.Surface;
            list.Controls.Add(row);
        }
        if (executables.Count == 0) list.Controls.Add(Label("exe は登録されていません"));
        list.ResumeLayout(true);
    }

    private void SelectRunning()
    {
        try
        {
            using var picker = new Form { Text = "実行中のプロセス", Font = theme.Body, ClientSize = new Size(430, 430),
                AutoScaleMode = AutoScaleMode.Dpi, StartPosition = FormStartPosition.CenterParent };
            var choices = new ListBox { Dock = DockStyle.Fill, Font = theme.Mono, AccessibleName = "実行中の exe", BorderStyle = BorderStyle.FixedSingle };
            choices.Items.AddRange(ProcessMonitor.RunningNames());
            var select = Button("選択して追加");
            select.Dock = DockStyle.Bottom;
            select.Height = 44;
            select.Click += (_, _) =>
            {
                if (choices.SelectedItem is string selected) { AddExecutable(selected); picker.Close(); }
            };
            picker.Controls.Add(choices);
            picker.Controls.Add(select);
            theme.Apply(picker);
            picker.SystemColorsChanged += (_, _) => theme.Apply(picker);
            picker.HandleCreated += (_, _) => DesignTheme.ApplyNative(picker);
            choices.HandleCreated += (_, _) => DesignTheme.ApplyNative(choices);
            picker.ShowDialog(this);
        }
        catch { error.Text = "実行中のアプリを読み込めません。"; log?.Write("process_list_failed"); }
    }

    private static TableLayoutPanel Column() => new() { AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
        Dock = DockStyle.Top, ColumnCount = 1, Margin = new Padding(0) , ColumnStyles = { new ColumnStyle(SizeType.Percent, 100) } };

    private static Label Label(string text, Font? font = null) => new() { Text = text, AutoSize = true,
        Dock = DockStyle.Top, Margin = new Padding(0, 4, 0, 8), Font = font, UseMnemonic = false };

    private static Button Button(string text) => new() { Text = text, AutoSize = true, Dock = DockStyle.Top,
        Padding = new Padding(8, 4, 8, 4), Margin = new Padding(0, 4, 0, 8) };
}
