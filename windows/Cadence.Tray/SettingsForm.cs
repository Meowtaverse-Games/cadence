using System.Diagnostics;
using System.Globalization;
using Cadence.Tray.Logic;
using Cadence.Core;

namespace Cadence.Tray;

internal sealed record SettingsSnapshot(TrayState State, string Status, SyncSummary Sync, string? Email,
    bool Configured, string Connection, string Problem, TodayTimeline? Timeline, bool ConfigurationUsable = true,
    DateTimeOffset? NextChange = null, DateTimeOffset? LastSync = null, DateTimeOffset? Now = null, string? PreviewTimetableJson = null);

internal sealed class SettingsForm : ChromeForm
{
    private readonly SyncService? service;
    private readonly List<string> executables;
    private readonly Func<List<string>, bool> saveExecutables;
    private readonly Action changed;
    private readonly DailyLog? log;
    private readonly Func<Task> reloadConfiguration;
    private readonly bool preview;
    private readonly DesignTheme theme = new();
    private readonly System.Windows.Forms.Timer displayTimer = new() { Interval = 1000 };
    private readonly Panel scroll = new() { AutoScroll = true, TabIndex = 1 };
    private readonly SidebarPanel sidebar = new() { TabIndex = 0, AccessibleName = "メニュー" };
    private readonly TableLayoutPanel root = Column();
    private readonly TableLayoutPanel[] pages = [Column(), Column(), Column(), Column()];
    private readonly RoundButton[] navigation = [new(), new(), new(), new()];
    private readonly Label heading = Label("");
    private readonly Label subtitle = Label("");
    private readonly Label problem = Label("");
    private readonly Label error = Label("");
    private readonly Label connection = Label("");
    private readonly Label lastSync = Label("");
    private readonly Label account = Label("");
    private readonly Label emailHeading = Label("メールアドレス");
    private readonly Label passwordHeading = Label("パスワード");
    private readonly Label readOnlyNotice = Label("時間割は iPhone で編集します（この PC は読み取り専用）");
    private readonly TextBox email = new() { AccessibleName = "メールアドレス" };
    private readonly TextBox password = new() { AccessibleName = "パスワード", UseSystemPasswordChar = true };
    private readonly TextBox executable = new() { PlaceholderText = "example.exe", AccessibleName = "追加する exe 名" };
    private readonly RoundButton signIn = Button("サインイン", true);
    private readonly RoundButton signOut = Button("サインアウト");
    private readonly RoundButton reload = Button("接続設定を読み直す");
    private readonly ToggleSwitch startup = new() { Text = "Windows のログオン時に起動する", Dock = DockStyle.Top, AccessibleName = "Windows のログオン時に起動する" };
    private readonly TableLayoutPanel list = Column();
    private readonly TableLayoutPanel activity = Column();
    private readonly RoundPanel card = new() { Dock = DockStyle.Top, Height = 180, Margin = new Padding(0, 22, 0, 24) };
    private readonly TimelineControl timeline = new();
    private readonly Label pattern = Label("");
    private readonly Label windowSummary = Label("");
    private readonly Label nextChange = Label("");
    private readonly Label pill = Label("");
    private readonly RoundPanel pillSurface = new() { Height = 30, Width = 100, Radius = 15 };
    private readonly Panel statusRow = new() { Height = 36, Dock = DockStyle.Top, Margin = new Padding(0, 8, 0, 8) };
    private readonly Panel emailInput;
    private readonly Panel passwordInput;
    private SettingsSnapshot? snapshot;
    private IReadOnlyList<ActivityEntry> logged = [];
    private DateTimeOffset readAt = DateTimeOffset.MinValue;
    private bool highContrast = DesignTheme.HighContrast;
    private bool busy;
    private bool arranging;
    private int page;

    public SettingsForm(SyncService service, List<string> executables, Func<List<string>, bool> saveExecutables,
        Action changed, DailyLog log, string configurationDirectory, Func<Task> reloadConfiguration)
        : this(service, executables, saveExecutables, changed, log, configurationDirectory, reloadConfiguration, false) { }

    private SettingsForm(SyncService? service, List<string> executables, Func<List<string>, bool> saveExecutables,
        Action changed, DailyLog? log, string configurationDirectory, Func<Task> reloadConfiguration, bool preview)
    {
        this.service = service; this.executables = executables; this.saveExecutables = saveExecutables;
        this.changed = changed; this.log = log; this.reloadConfiguration = reloadConfiguration; this.preview = preview;
        SuspendLayout();
        Text = "Cadence — 設定"; Font = theme.Body;
        AutoScaleMode = AutoScaleMode.Dpi; AutoScaleDimensions = new SizeF(96, 96);
        Size = new Size(960, 640); MinimumSize = new Size(760, 520);
        StartPosition = FormStartPosition.CenterScreen; Icon = MarkRenderer.Icon(48, null);
        Controls.AddRange([scroll, sidebar]);
        root.AutoSize = false; root.Dock = DockStyle.None;
        scroll.Controls.Add(root);
        heading.Font = theme.Heading;
        root.Controls.AddRange([heading, statusRow, problem, error]);
        foreach (var content in pages) root.Controls.Add(content);
        pill.Font = theme.Tag; pill.Dock = DockStyle.None; pill.AutoSize = false; pill.TextAlign = ContentAlignment.MiddleCenter; pill.Margin = Padding.Empty;
        pillSurface.Controls.Add(pill);
        pillSurface.Layout += (_, _) => pill.SetBounds(ScalePixel(12), ScalePixel(4), pillSurface.Width - ScalePixel(24), pillSurface.Height - ScalePixel(8));
        subtitle.Dock = DockStyle.None; subtitle.AutoSize = false;
        statusRow.Controls.AddRange([pillSurface, subtitle]);
        statusRow.Layout += (_, _) => { pillSurface.SetBounds(0, 0, ScalePixel(100), ScalePixel(30)); subtitle.SetBounds(ScalePixel(112), 0, statusRow.Width - ScalePixel(112), statusRow.Height); };
        sidebar.Paint += PaintSidebar;
        string[] names = ["ホーム", "ブロックするアプリ", "アカウント", "接続と詳細"];
        for (var i = 0; i < navigation.Length; i++)
        {
            var index = i; var button = navigation[i];
            button.Text = names[i]; button.AccessibleName = names[i]; button.IconKind = i; button.Navigation = true; button.Border = false;
            button.Click += (_, _) => SelectPage(index);
            button.PreviewKeyDown += (_, e) => { if (e.KeyCode is Keys.Up or Keys.Down) e.IsInputKey = true; };
            button.KeyDown += (_, e) =>
            {
                if (e.KeyCode is Keys.Up or Keys.Down) { navigation[(index + (e.KeyCode == Keys.Down ? 1 : 3)) % 4].Focus(); e.Handled = true; }
                if (e.KeyCode == Keys.Enter) { SelectPage(index); e.Handled = true; }
            };
            sidebar.Controls.Add(button);
        }
        pattern.Font = theme.Body; windowSummary.Font = theme.Mono; nextChange.Font = theme.Mono;
        card.Controls.AddRange([pattern, windowSummary, timeline, nextChange]);
        foreach (var label in new[] { pattern, windowSummary, nextChange }) { label.Dock = DockStyle.None; label.AutoSize = false; label.AutoEllipsis = true; }
        card.Layout += (_, _) =>
        {
            var inset = ScalePixel(28); var width = card.Width - inset * 2;
            pattern.SetBounds(inset, ScalePixel(22), width, ScalePixel(26));
            windowSummary.SetBounds(inset, ScalePixel(52), width, ScalePixel(24));
            timeline.SetBounds(inset, ScalePixel(86), width, ScalePixel(34));
            nextChange.SetBounds(inset, ScalePixel(132), width, ScalePixel(26));
        };
        pages[0].Controls.AddRange([card, Label("今日の記録", theme.Caption), activity]);
        var add = Button("追加", true); add.Dock = DockStyle.None;
        var input = Input(executable); input.Dock = DockStyle.Fill; input.Margin = Padding.Empty;
        var plus = Label("＋"); plus.Dock = DockStyle.None; plus.AutoSize = false; input.Controls.AddRange([plus, add]);
        input.Layout += (_, _) => { plus.SetBounds(ScalePixel(12), ScalePixel(12), ScalePixel(22), ScalePixel(26)); executable.SetBounds(ScalePixel(40), ScalePixel(13), Math.Max(ScalePixel(80), input.Width - ScalePixel(132)), ScalePixel(24)); add.SetBounds(input.Width - ScalePixel(80), ScalePixel(6), ScalePixel(72), ScalePixel(36)); };
        var running = Button("実行中のアプリから選ぶ"); running.Dock = DockStyle.Fill; running.Margin = new Padding(10, 0, 0, 0);
        var inputRow = new TableLayoutPanel { Dock = DockStyle.Top, Height = 48, ColumnCount = 2, Margin = new Padding(0, 0, 0, 20) };
        inputRow.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220));
        inputRow.Controls.Add(input, 0, 0); inputRow.Controls.Add(running, 1, 0);
        pages[1].Controls.AddRange([Label("追加する exe 名", theme.Caption), inputRow, list]);
        emailInput = Input(email); passwordInput = Input(password);
        pages[2].Controls.AddRange([account, readOnlyNotice, emailHeading, emailInput, passwordHeading, passwordInput, signIn, signOut,
            Label("サインアウトしても保存済みの時間割によるブロックは続きます。"), startup]);
        var openFolder = Button("設定フォルダを開く");
        pages[3].Controls.AddRange([Label("Cadence はタスクバー右の通知領域（「^」の中）に常駐します。この画面を閉じても動き続けます。開き直すにはアイコンをダブルクリックするか、右クリック →「設定...」を選びます。"), connection, openFolder, reload, lastSync, Label("時刻はすべて日本時間です。")]);
        error.Visible = false;
        error.TextChanged += (_, _) => { error.Visible = error.Text.Length > 0; LayoutContent(); };
        scroll.Layout += (_, _) => LayoutContent(); root.Layout += (_, _) => LayoutContent();
        ReloadExecutables(); SelectPage(0); ApplyTheme(); ResumeLayout(true);
        displayTimer.Tick += (_, _) => { if (Visible) UpdateClock(); };
        if (preview) return;
        displayTimer.Start();
        openFolder.Click += (_, _) =>
        {
            try { Directory.CreateDirectory(configurationDirectory); Process.Start(new ProcessStartInfo { FileName = configurationDirectory, UseShellExecute = true }); }
            catch { error.Text = "設定フォルダを開けません。フォルダの権限などを確認してください。"; log?.Write("config_folder_open_failed"); }
        };
        reload.Click += async (_, _) => await ReloadConfigurationAsync();
        signIn.Click += async (_, _) => await AuthenticateAsync(true); signOut.Click += async (_, _) => await AuthenticateAsync(false);
        add.Click += (_, _) => AddExecutable(executable.Text);
        executable.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { AddExecutable(executable.Text); e.SuppressKeyPress = true; } };
        running.Click += (_, _) => SelectRunning();
        try { startup.Checked = StartupRegistration.Enabled(); }
        catch { error.Text = "自動起動の設定を読み込めません。"; log?.Write("startup_read_failed"); }
        startup.CheckedChanged += (_, _) =>
        {
            try { StartupRegistration.Set(startup.Checked); }
            catch { error.Text = "自動起動の設定を保存できません。"; log?.Write("startup_write_failed"); startup.Enabled = false; }
        };
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        navigation[page].Focus();
    }

    private Panel Input(TextBox text)
    {
        var panel = new RoundPanel { Radius = 24, Dock = DockStyle.Top, Height = 48, Margin = new Padding(0, 0, 0, 14) };
        text.Font = theme.Mono; text.BorderStyle = BorderStyle.None; panel.Controls.Add(text);
        panel.Layout += (_, _) => text.SetBounds(ScalePixel(20), ScalePixel(13), panel.Width - ScalePixel(40), ScalePixel(24));
        return panel;
    }

    protected override void OnLayout(LayoutEventArgs e)
    {
        base.OnLayout(e);
        if (sidebar is null) return;
        sidebar.SetBounds(0, 0, ScalePixel(232), ClientSize.Height);
        scroll.SetBounds(ScalePixel(232), ScalePixel(48), ClientSize.Width - ScalePixel(232), ClientSize.Height - ScalePixel(48));
        for (var i = 0; i < navigation.Length; i++) navigation[i].SetBounds(ScalePixel(14), ScalePixel(88 + i * 50), ScalePixel(204), ScalePixel(44));
        LayoutContent();
    }

    private void LayoutContent()
    {
        if (arranging || root is null) return;
        arranging = true;
        try
        {
            var padding = ScalePixel(48);
            var width = Math.Max(ScalePixel(600), scroll.ClientSize.Width - padding * 2 - (scroll.VerticalScroll.Visible ? SystemInformation.VerticalScrollBarWidth : 0));
            root.Width = width;
            root.Height = root.GetPreferredSize(new Size(width, 0)).Height;
            root.Location = new Point(padding + scroll.AutoScrollPosition.X, ScalePixel(8) + scroll.AutoScrollPosition.Y);
            scroll.AutoScrollMinSize = new Size(width + padding * 2, root.Height + ScalePixel(48));
        }
        finally { arranging = false; }
    }

    internal void SelectPage(int index)
    {
        page = index;
        for (var i = 0; i < pages.Length; i++) { pages[i].Visible = i == page; navigation[i].Selected = i == page; navigation[i].Invalidate(); navigation[i].AccessibleDescription = i == page ? "選択中" : ""; }
        scroll.AutoScrollPosition = Point.Empty;
        UpdateClock(); LayoutContent();
    }

    public void RefreshStatus(SettingsSnapshot value)
    {
        if (IsDisposed) return;
        var first = snapshot is null;
        snapshot = value;
        if (first && !value.ConfigurationUsable) SelectPage(3);
        connection.Text = value.Connection;
        lastSync.Text = "最終同期  " + (value.LastSync is { } at ? ScheduleStatus.JapanTime(at) : "未取得");
        problem.Text = value.Problem; problem.Visible = problem.Text.Length > 0;
        account.Text = value.Email ?? "サインインしていません";
        emailHeading.Visible = passwordHeading.Visible = emailInput.Visible = passwordInput.Visible = signIn.Visible = value.Email is null;
        readOnlyNotice.Visible = signOut.Visible = value.Email is not null;
        signIn.Enabled = !busy && value.Configured; signOut.Enabled = !busy && value.Email is not null; reload.Enabled = !busy;
        if (highContrast != DesignTheme.HighContrast) { highContrast = DesignTheme.HighContrast; ApplyTheme(); }
        sidebar.AccessibleDescription = value.Sync.Text + "。" + (value.Email ?? "サインインしていません");
        sidebar.Invalidate(); UpdateClock();
    }

    private void UpdateClock()
    {
        if (snapshot is not { } value) return;
        var now = preview ? value.Now ?? DateTimeOffset.Parse("2026-01-05T19:20:00+09:00", CultureInfo.InvariantCulture) : DateTimeOffset.UtcNow;
        if (!preview && (now - readAt >= TimeSpan.FromSeconds(10) || now < readAt || now.ToOffset(TimeSpan.FromHours(9)).Date != readAt.ToOffset(TimeSpan.FromHours(9)).Date))
        { logged = log?.Read(now) ?? []; readAt = now; UpdateCounts(now); }
        heading.Text = page switch { 1 => "ブロックするアプリ", 2 => "アカウント", 3 => "接続と詳細", _ => value.State == TrayState.Focus ? "いまは集中の時間" : value.State == TrayState.Free ? "いまは自由時間" : value.Status };
        pill.Text = page == 1 ? executables.Count + " APPS" : value.State.ToString().ToUpperInvariant();
        statusRow.Visible = page is 0 or 1;
        pillSurface.Fill = page == 1 ? DesignTheme.Surface2 : DesignTheme.StateColor(value.State);
        pill.BackColor = pillSurface.Fill; pill.ForeColor = page == 1 ? DesignTheme.Text : DesignTheme.HighContrast ? SystemColors.HighlightText : DesignTheme.Ground;
        subtitle.Text = page == 1 ? "集中の時間になると、この PC で自動的に終了します" : "次の切り替えはありません";
        if (page == 0 && value.NextChange is { } next)
        {
            var remaining = Math.Max(0, (int)(next - now).TotalSeconds);
            subtitle.Text = Time(next) + (value.State == TrayState.Focus ? " まで · 残り " : " から · あと ") + $"{remaining / 60:00}:{remaining % 60:00}";
        }
        var today = preview ? value.Timeline : TodayTimeline.Build(service?.State.TimetableJson, now);
        pattern.Text = today?.Name ?? "今日の予定はありません";
        windowSummary.Text = today is null ? "" : $"{Time(today.Start)} – {Time(today.End)} · {today.Summary}";
        timeline.SetTimeline(today);
        nextChange.Text = value.NextChange is { } change ? "次の切り替え  " + Time(change) + " → " + (service is not null ? ScheduleStatus.Evaluate(service.State.Timetable, TimeProvider.System).Blocked ? "FREE" : "FOCUS" : value.State == TrayState.Focus ? "FREE" : "FOCUS") : "次の切り替えはありません（14日以内）";
        card.Outline = value.State == TrayState.Focus ? DesignTheme.HighContrast ? DesignTheme.Focus : Color.FromArgb(96, 74, 40) : null;
        card.Invalidate();
        var events = TodayActivity.Build(logged, preview ? TimetableParser.Parse(value.PreviewTimetableJson).Timetable : service?.State.Timetable, now);
        var key = string.Join("|", events.Select(e => e.At + e.Message));
        if (!Equals(activity.Tag, key))
        {
            Clear(activity); activity.Tag = key;
            foreach (var entry in events)
            {
                var row = new TableLayoutPanel { Dock = DockStyle.Top, Height = ScalePixel(42), ColumnCount = 3, Margin = Padding.Empty };
                row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, ScalePixel(64))); row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, ScalePixel(26))); row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
                var time = Label(Time(entry.At), theme.Mono); var diamond = Label("◆"); var text = Label(entry.Message);
                row.Controls.Add(time, 0, 0); row.Controls.Add(diamond, 1, 0); row.Controls.Add(text, 2, 0);
                theme.Apply(row); time.ForeColor = DesignTheme.Muted; diamond.ForeColor = DesignTheme.StateColor(entry.State);
                activity.Controls.Add(row);
            }
            if (events.Count == 0) { var empty = Label("今日の記録はまだありません"); theme.Apply(empty); activity.Controls.Add(empty); }
        }
        LayoutContent();
    }

    private void PaintSidebar(object? sender, PaintEventArgs e)
    {
        var s = DeviceDpi / 96f;
        using var mark = MarkRenderer.Draw(ScalePixel(40), null);
        e.Graphics.DrawImageUnscaled(mark, ScalePixel(18), ScalePixel(21));
        TextRenderer.DrawText(e.Graphics, "C A D E N C E", theme.Tag, new Point(ScalePixel(68), ScalePixel(24)), DesignTheme.Text);
        TextRenderer.DrawText(e.Graphics, "v" + typeof(SettingsForm).Assembly.GetName().Version?.ToString(3), theme.Mono, new Point(ScalePixel(68), ScalePixel(46)), DesignTheme.Muted);
        using var line = new Pen(DesignTheme.HighContrast ? SystemColors.WindowText : ColorTranslator.FromHtml("#16232A"));
        e.Graphics.DrawLine(line, sidebar.Width - 1, 0, sidebar.Width - 1, sidebar.Height);
        if (snapshot is not { } value) return;
        var y = sidebar.Height - ScalePixel(120);
        using var path = Shapes.Round(new RectangleF(14 * s, y, 204 * s, 36 * s), 18 * s);
        using var outline = new Pen(DesignTheme.Line); e.Graphics.DrawPath(outline, path);
        using var dot = new SolidBrush(value.Sync.IsProblem ? DesignTheme.Alert : DesignTheme.Free);
        e.Graphics.FillEllipse(dot, 26 * s, y + 14 * s, 8 * s, 8 * s);
        TextRenderer.DrawText(e.Graphics, value.Sync.Text, theme.Mono, new Rectangle(ScalePixel(40), y, ScalePixel(170), ScalePixel(36)), DesignTheme.Muted, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        if (value.Email is { } address)
        {
            using var fill = new SolidBrush(DesignTheme.Surface2); e.Graphics.FillEllipse(fill, 20 * s, y + 54 * s, 36 * s, 36 * s);
            TextRenderer.DrawText(e.Graphics, address[..1].ToUpperInvariant(), theme.Body, new Rectangle(ScalePixel(20), y + ScalePixel(54), ScalePixel(36), ScalePixel(36)), DesignTheme.Free, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            TextRenderer.DrawText(e.Graphics, address, theme.Caption, new Rectangle(ScalePixel(66), y + ScalePixel(54), ScalePixel(150), ScalePixel(22)), DesignTheme.Text, TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine);
            TextRenderer.DrawText(e.Graphics, "読み取り専用", theme.Caption, new Point(ScalePixel(66), y + ScalePixel(78)), DesignTheme.Muted);
        }
        else TextRenderer.DrawText(e.Graphics, "サインインしていません", theme.Caption, new Point(ScalePixel(20), y + ScalePixel(62)), DesignTheme.Muted);
    }

    private void ApplyTheme()
    {
        theme.Apply(this);
        sidebar.BackColor = DesignTheme.HighContrast ? SystemColors.Window : ColorTranslator.FromHtml("#0E171B");
        foreach (var nav in navigation) nav.BackColor = sidebar.BackColor;
        foreach (var input in new[] { emailInput, passwordInput, executable.Parent! })
        { input.BackColor = DesignTheme.Ground; if (input is RoundPanel round) round.Fill = DesignTheme.Surface; foreach (Control child in input.Controls) { child.BackColor = DesignTheme.Surface; if (child is TextBox text) text.BorderStyle = BorderStyle.None; } }
        foreach (Control row in list.Controls)
        {
            if (row is RoundPanel rounded) rounded.Fill = DesignTheme.Ground;
            foreach (Control child in row.Controls) child.BackColor = DesignTheme.Ground;
        }
        card.Fill = DesignTheme.Surface; foreach (Control child in card.Controls) child.BackColor = DesignTheme.Surface;
        subtitle.ForeColor = windowSummary.ForeColor = connection.ForeColor = DesignTheme.Muted;
        error.ForeColor = problem.ForeColor = DesignTheme.Alert;
        Invalidate(true);
    }

    private static string Time(DateTimeOffset at) => at.ToOffset(TimeSpan.FromHours(9)).ToString("HH:mm", CultureInfo.InvariantCulture);
    internal void ScrollPreviewToRightEdge()
    {
        LayoutContent(); scroll.AutoScrollPosition = new Point(scroll.HorizontalScroll.Maximum, 0);
        if (!scroll.HorizontalScroll.Visible || scroll.HorizontalScroll.Value == 0 || root.Right > scroll.ClientSize.Width)
            throw new InvalidOperationException("The right edge of the settings content is unreachable.");
    }
    internal static SettingsForm Preview(SettingsSnapshot value)
    {
        var form = new SettingsForm(null, ["game.exe", "launcher.exe", "video.exe", "chat.exe"], _ => false, () => { }, null, "", () => Task.CompletedTask, true);
        form.logged = TodayActivity.Parse(["19:14:00 process_terminated exe=game.exe", "19:16:00 process_terminated exe=game.exe", "19:15:00 process_terminated exe=launcher.exe"], new DateOnly(2026, 1, 5));
        form.RefreshStatus(value); form.UpdateCounts(value.Now ?? DateTimeOffset.Parse("2026-01-05T19:20:00+09:00", CultureInfo.InvariantCulture)); return form;
    }
    protected override void Dispose(bool disposing)
    {
        if (disposing) { displayTimer.Dispose(); Icon?.Dispose(); }
        base.Dispose(disposing);
        if (disposing) theme.Dispose();
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

    private static void Clear(TableLayoutPanel panel)
    {
        while (panel.Controls.Count > 0) { var old = panel.Controls[0]; panel.Controls.Remove(old); old.Dispose(); }
        panel.RowStyles.Clear(); panel.RowCount = 0;
    }
    private void UpdateCounts(DateTimeOffset now)
    {
        foreach (Control row in list.Controls)
            if (row.Tag is string name && row.Controls["count"] is Label count)
            { var n = TodayActivity.Count(logged, name, now); count.Text = n == 0 ? "—" : $"今日 {n} 回終了"; }
    }
    private void ReloadExecutables()
    {
        list.SuspendLayout(); Clear(list);
        foreach (var name in executables)
        {
            var row = new RoundPanel { Dock = DockStyle.Top, Height = ScalePixel(60), Radius = 16, Fill = DesignTheme.Ground, Margin = new Padding(0, 0, 0, ScalePixel(6)), Tag = name };
            var initial = new InitialBadge { Text = name[..1].ToUpperInvariant(), Font = theme.Tag }; var label = Label(name, theme.Mono);
            var count = Label("—", theme.Caption); count.Name = "count";
            var remove = Button("✕"); remove.Border = false; remove.AccessibleName = name + " を外す";
            foreach (var control in new Control[] { initial, label, count, remove }) { control.Dock = DockStyle.None; control.AutoSize = false; row.Controls.Add(control); }
            row.Layout += (_, _) => { initial.SetBounds(ScalePixel(16), ScalePixel(12), ScalePixel(36), ScalePixel(36)); label.SetBounds(ScalePixel(66), ScalePixel(18), row.Width - ScalePixel(250), ScalePixel(28)); count.SetBounds(row.Width - ScalePixel(176), ScalePixel(19), ScalePixel(120), ScalePixel(26)); remove.SetBounds(row.Width - ScalePixel(48), ScalePixel(10), ScalePixel(40), ScalePixel(40)); };
            label.AutoEllipsis = true;
            void Hover(bool hot) { row.Fill = hot ? DesignTheme.Surface : DesignTheme.Ground; foreach (Control child in row.Controls) child.BackColor = row.Fill; row.Invalidate(); }
            row.MouseEnter += (_, _) => Hover(true); row.MouseLeave += (_, _) => { if (!row.ClientRectangle.Contains(row.PointToClient(Cursor.Position))) Hover(false); };
            foreach (Control child in row.Controls) { child.MouseEnter += (_, _) => Hover(true); child.MouseLeave += (_, _) => { if (!row.ClientRectangle.Contains(row.PointToClient(Cursor.Position))) Hover(false); }; }
            remove.Click += (_, _) => { var proposed = executables.ToList(); proposed.Remove(name); if (saveExecutables(proposed)) { ReloadExecutables(); UpdateClock(); executable.Focus(); } };
            theme.Apply(row); count.ForeColor = DesignTheme.Muted; Hover(false); list.Controls.Add(row);
        }
        if (executables.Count == 0) { var empty = Label("exe は登録されていません"); theme.Apply(empty); list.Controls.Add(empty); }
        list.ResumeLayout(true); UpdateCounts(snapshot?.Now ?? DateTimeOffset.UtcNow);
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
        Dock = DockStyle.Top, ColumnCount = 1, Margin = Padding.Empty, ColumnStyles = { new ColumnStyle(SizeType.Percent, 100) } };
    private static Label Label(string text, Font? font = null) => new() { Text = text, AutoSize = true,
        Dock = DockStyle.Top, Margin = new Padding(0, 4, 0, 8), Font = font, UseMnemonic = false };
    private static RoundButton Button(string text, bool primary = false) => new() { Text = text, Dock = DockStyle.Top,
        Height = 44, Primary = primary, Margin = new Padding(0, 4, 0, 8) };
}
