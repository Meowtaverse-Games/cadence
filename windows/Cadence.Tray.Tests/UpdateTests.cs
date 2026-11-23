using Cadence.Core;
using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class UpdateTests
{
    [Theory]
    [InlineData("2026-01-05T19:04:59+09:00", true)]
    [InlineData("2026-01-05T19:05:00+09:00", true)]
    [InlineData("2026-01-05T19:05:00.001+09:00", false)]
    [InlineData("2026-01-05T19:10:00+09:00", false)]
    [InlineData("2026-01-05T19:29:59+09:00", false)]
    [InlineData("2026-01-05T19:30:00+09:00", true)]
    public void UsesExistingBlockBoundariesAndFiveMinuteMargin(string now, bool expected)
    {
        var timetable = TimetableParser.Parse(Samples.Timetable("basic")).Timetable;
        Assert.NotNull(timetable);
        var clock = new FakeClock { Now = DateTimeOffset.Parse(now) };
        Assert.Equal(expected, UpdatePolicy.CanRestart(timetable, clock, false));
        Assert.False(UpdatePolicy.CanRestart(timetable, clock, true));
    }

    [Theory]
    [InlineData("2026-01-04T23:55:00+09:00", true)] // Sunday to Monday, exact margin
    [InlineData("2026-01-04T23:55:01+09:00", false)]
    [InlineData("2026-01-05T00:00:00+09:00", false)]
    [InlineData("2026-01-05T00:20:00+09:00", true)] // Next week
    [InlineData("2026-01-11T23:56:00+09:00", false)]
    public void LooksAcrossMidnightAndWeekBoundary(string now, bool expected)
    {
        const string json = """{"schemaVersion":1,"timeZone":"Asia/Tokyo","patterns":[{"id":"synthetic","name":"合成","enabled":true,"weekdays":[1],"start":"00:00","end":"00:20","segments":[{"kind":"block","minutes":20}]}]}""";
        var timetable = TimetableParser.Parse(json).Timetable;
        Assert.NotNull(timetable);
        Assert.Equal(expected, UpdatePolicy.CanRestart(timetable, new FakeClock { Now = DateTimeOffset.Parse(now) }, false));
    }

    [Fact]
    public void NoScheduleOrEmptyScheduleIsSafeUnlessSettingsAreOpen()
    {
        foreach (var timetable in new[] { null, TimetableParser.Parse(Samples.Empty).Timetable })
        {
            Assert.True(UpdatePolicy.CanRestart(timetable, new FakeClock(), false));
            Assert.False(UpdatePolicy.CanRestart(timetable, new FakeClock(), true));
        }
    }

    [Fact]
    public async Task ManualBuildNeverContactsServer()
    {
        var backend = new FakeUpdater { Installed = false };
        var clock = new FakeClock();
        var updates = Create(backend, clock);
        clock.Now = clock.Now.AddDays(1);
        await updates.TickAsync(true, default);
        Assert.Equal(0, backend.Checks);
        Assert.False(updates.Snapshot.CanCheck);
        Assert.Contains("手動", updates.Snapshot.Status);
    }

    [Fact]
    public async Task DelaysFirstCheckThenChecksEverySixHours()
    {
        var backend = new FakeUpdater { Available = false };
        var clock = new FakeClock();
        var updates = Create(backend, clock);
        clock.Now = clock.Now.AddSeconds(59);
        await updates.TickAsync(false, default);
        Assert.Equal(0, backend.Checks);
        clock.Now = clock.Now.AddSeconds(1);
        await updates.TickAsync(false, default);
        Assert.Equal(1, backend.Checks);
        Assert.Equal(clock.Now, updates.Snapshot.LastChecked);
        clock.Now = clock.Now.AddHours(6).AddTicks(-1);
        await updates.TickAsync(false, default);
        Assert.Equal(1, backend.Checks);
        clock.Now = clock.Now.AddTicks(1);
        await updates.TickAsync(false, default);
        Assert.Equal(2, backend.Checks);
    }

    [Fact]
    public async Task ManualCheckDownloadsButWaitsForSafeTimeAndClosedSettings()
    {
        var backend = new FakeUpdater();
        var clock = new FakeClock { Now = DateTimeOffset.Parse("2026-01-05T19:20:00+09:00") };
        var timetable = TimetableParser.Parse(Samples.Timetable("basic")).Timetable;
        var open = true;
        var updates = Create(backend, clock, () => UpdatePolicy.CanRestart(timetable, clock, open));
        await updates.TickAsync(true, default);
        Assert.True(backend.HasPendingUpdate);
        Assert.Equal(0, backend.Applies);
        open = false;
        await updates.TickAsync(false, default);
        Assert.Equal(0, backend.Applies);
        clock.Now = clock.Now.AddMinutes(10);
        open = true;
        await updates.TickAsync(false, default);
        Assert.Equal(0, backend.Applies);
        open = false;
        await updates.TickAsync(false, default);
        Assert.Equal(1, backend.Applies);
        Assert.Equal(1, backend.Checks);
    }

    [Theory]
    [InlineData("check")]
    [InlineData("download")]
    [InlineData("apply")]
    public async Task FailuresAreContainedRedactedAndRetriedAtNextCheck(string stage)
    {
        var backend = new FakeUpdater { FailureStage = stage };
        var clock = new FakeClock();
        var logs = new List<string>();
        var updates = new UpdateCoordinator(() => backend, "0.2.0", clock, () => true, logs.Add, () => { });
        await updates.TickAsync(true, default);
        Assert.Contains("確認に失敗", updates.Snapshot.Status);
        Assert.Contains("IOException", Assert.Single(logs));
        Assert.DoesNotContain("secret", logs[0]);
        clock.Now = clock.Now.AddMinutes(1);
        await updates.TickAsync(false, default);
        Assert.Single(logs);
        backend.FailureStage = null;
        clock.Now = clock.Now.AddHours(6);
        await updates.TickAsync(false, default);
        Assert.Equal(1, backend.Applies);
    }

    [Fact]
    public async Task RecoversPendingDownloadAndDoesNotApplyAfterStop()
    {
        var backend = new FakeUpdater { Available = false, HasPendingUpdate = true };
        var clock = new FakeClock();
        var safe = false;
        var updates = Create(backend, clock, () => safe);
        await updates.TickAsync(true, default);
        Assert.Contains("ダウンロード済み", updates.Snapshot.Status);
        updates.Stop();
        safe = true;
        await updates.TickAsync(false, default);
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task InFlightCheckCannotOverlapOrRestartAfterShutdown()
    {
        var backend = new FakeUpdater { CheckCompletion = new TaskCompletionSource<bool>() };
        var updates = Create(backend, new FakeClock());
        var first = updates.TickAsync(true, default);
        await updates.TickAsync(true, default);
        Assert.Equal(1, backend.Checks);
        updates.Stop();
        backend.CheckCompletion.SetResult(true);
        await first;
        Assert.False(backend.HasPendingUpdate);
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task RechecksScheduleAfterDownloadRatherThanUsingCheckTime()
    {
        var backend = new FakeUpdater { DownloadCompletion = new TaskCompletionSource<bool>() };
        var clock = new FakeClock { Now = DateTimeOffset.Parse("2026-01-05T19:00:00+09:00") };
        var timetable = TimetableParser.Parse(Samples.Timetable("basic")).Timetable;
        var updates = Create(backend, clock, () => UpdatePolicy.CanRestart(timetable, clock, false));
        var downloading = updates.TickAsync(true, default);
        Assert.Equal("ダウンロード中", updates.Snapshot.Status);
        Assert.False(updates.Snapshot.CanCheck);
        clock.Now = clock.Now.AddMinutes(10);
        backend.DownloadCompletion.SetResult(true);
        await downloading;
        Assert.True(backend.HasPendingUpdate);
        Assert.Equal(0, backend.Applies);
        clock.Now = clock.Now.AddMinutes(20);
        await updates.TickAsync(false, default);
        Assert.Equal(1, backend.Applies);
    }

    [Fact]
    public async Task InitializationFailureDoesNotEscapeAndRecoversOnScheduledCheck()
    {
        var clock = new FakeClock();
        var backend = new FakeUpdater { Available = false };
        var calls = 0;
        var updates = new UpdateCoordinator(() => ++calls == 1 ? throw new IOException("private path") : backend,
            "0.2.0", clock, () => true, _ => { }, () => { });
        Assert.Contains("確認に失敗", updates.Snapshot.Status);
        clock.Now = clock.Now.AddMinutes(1);
        await updates.TickAsync(false, default);
        Assert.Equal("最新", updates.Snapshot.Status);
    }

    private static UpdateCoordinator Create(FakeUpdater backend, FakeClock clock, Func<bool>? safe = null) =>
        new(() => backend, "0.2.0", clock, safe ?? (() => true), _ => { }, () => { });

    private sealed class FakeUpdater : IAppUpdater
    {
        public bool Installed { get; set; } = true;
        public string Version => "0.2.0";
        public bool HasPendingUpdate { get; set; }
        public bool Available { get; set; } = true;
        public int Checks { get; private set; }
        public int Applies { get; private set; }
        public string? FailureStage { get; set; }
        public TaskCompletionSource<bool>? CheckCompletion { get; init; }
        public TaskCompletionSource<bool>? DownloadCompletion { get; init; }
        public Task<bool> CheckAsync() { Checks++; Fail("check"); return CheckCompletion?.Task ?? Task.FromResult(Available); }
        public async Task DownloadAsync(CancellationToken cancellation)
        {
            Fail("download");
            if (DownloadCompletion is not null) await DownloadCompletion.Task;
            HasPendingUpdate = true;
        }
        public void ApplyAndRestart() { Fail("apply"); Applies++; }
        private void Fail(string stage) { if (stage == FailureStage) throw new IOException("https://example.invalid/?token=secret C:\\Users\\synthetic"); }
    }
}
