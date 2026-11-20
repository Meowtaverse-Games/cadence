using Cadence.Tray.Logic;

namespace Cadence.Tray;

internal sealed class SettingsForm : Form
{
    private readonly SyncService service;
    private readonly List<string> executables;
    private readonly Func<List<string>, bool> saveExecutables;
    private readonly Action changed;
    private readonly DailyLog log;
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(640, 0) };
    private readonly Label account = new() { AutoSize = true };
    private readonly Label sync = new() { AutoSize = true };
    private readonly Label error = new() { AutoSize = true, ForeColor = Color.Firebrick, MaximumSize = new Size(640, 0) };
    private readonly TextBox email = new() { Width = 390, PlaceholderText = "メールアドレス" };
    private readonly TextBox password = new() { Width = 390, PlaceholderText = "パスワード", UseSystemPasswordChar = true };
    private readonly Button signIn = new() { Text = "サインイン", AutoSize = true };
    private readonly Button signOut = new() { Text = "サインアウト", AutoSize = true };
    private readonly ListBox list = new() { Width = 630, Height = 135 };
    private readonly TextBox executable = new() { Width = 350, PlaceholderText = "例: sample.exe" };
    private readonly CheckBox startup = new() { Text = "Windows のログオン時に起動する", AutoSize = true };
    private bool busy;

    public SettingsForm(SyncService service, List<string> executables, Func<List<string>, bool> saveExecutables,
        Action changed, DailyLog log)
    {
        this.service = service;
        this.executables = executables;
        this.saveExecutables = saveExecutables;
        this.changed = changed;
        this.log = log;
        Text = "Cadence の設定";
        ClientSize = new Size(690, 690);
        MinimumSize = new Size(600, 500);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, Padding = new Padding(16) };
        Controls.Add(layout);
        layout.Controls.AddRange([status, sync, account, email, password, Row(signIn, signOut), error,
            new Label { Text = "この PC でブロックするアプリ", AutoSize = true }, list]);
        var add = new Button { Text = "追加", AutoSize = true };
        var remove = new Button { Text = "削除", AutoSize = true };
        var running = new Button { Text = "実行中のアプリから追加...", AutoSize = true };
        layout.Controls.Add(Row(executable, add, remove));
        layout.Controls.Add(running);
        layout.Controls.Add(startup);
        layout.Controls.Add(new Label { Text = "サインアウトしても保存済みの時間割によるブロックは続きます。\n時刻はすべて日本時間です。", AutoSize = true });
        ReloadExecutables();
        signIn.Click += async (_, _) => await AuthenticateAsync(signingIn: true);
        signOut.Click += async (_, _) => await AuthenticateAsync(signingIn: false);
        add.Click += (_, _) => AddExecutable(executable.Text);
        remove.Click += (_, _) =>
        {
            if (list.SelectedItem is not string selected) return;
            var proposed = executables.ToList();
            proposed.Remove(selected);
            if (saveExecutables(proposed)) ReloadExecutables();
        };
        running.Click += (_, _) => SelectRunning();
        try { startup.Checked = StartupRegistration.Enabled(); }
        catch { error.Text = "自動起動の設定を読み込めません。"; log.Write("startup_read_failed"); }
        startup.CheckedChanged += (_, _) =>
        {
            try { StartupRegistration.Set(startup.Checked); }
            catch
            {
                error.Text = "自動起動の設定を保存できません。";
                log.Write("startup_write_failed");
                // Disable after a failed change rather than imply it was applied.
                startup.Enabled = false;
            }
        };
    }

    public void RefreshStatus(string text, SyncService syncService)
    {
        if (IsDisposed) return;
        status.Text = text;
        sync.Text = "最終同期: " + (syncService.State.LastSync is { } at ? ScheduleStatus.JapanTime(at) : "未取得");
        account.Text = syncService.Email is { } signedIn ? "サインイン中: " + signedIn : "サインインしていません";
        signIn.Enabled = !busy && syncService.Configured;
        signOut.Enabled = !busy && syncService.Email is not null;
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
                var operation = service.SignInAsync(email.Text.Trim(), password.Text);
                password.Clear();
                var message = await operation;
                if (!IsDisposed) error.Text = message ?? service.StorageProblem ?? "";
            }
            else await service.SignOutAsync();
        }
        catch
        {
            if (!IsDisposed) error.Text = "操作を完了できません。もう一度お試しください。";
            log.Write("account_operation_failed");
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
        list.Items.Clear();
        list.Items.AddRange(executables.Cast<object>().ToArray());
    }

    private void SelectRunning()
    {
        try
        {
            using var picker = new Form { Text = "実行中のアプリ", ClientSize = new Size(430, 430), StartPosition = FormStartPosition.CenterParent };
            var choices = new ListBox { Dock = DockStyle.Fill };
            choices.Items.AddRange(ProcessMonitor.RunningNames());
            var select = new Button { Text = "選択して追加", Dock = DockStyle.Bottom, Height = 40 };
            select.Click += (_, _) =>
            {
                if (choices.SelectedItem is string selected) { AddExecutable(selected); picker.Close(); }
            };
            picker.Controls.Add(choices);
            picker.Controls.Add(select);
            picker.ShowDialog(this);
        }
        catch { error.Text = "実行中のアプリを読み込めません。"; log.Write("process_list_failed"); }
    }

    private static FlowLayoutPanel Row(params Control[] controls)
    {
        var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
        row.Controls.AddRange(controls);
        return row;
    }
}
