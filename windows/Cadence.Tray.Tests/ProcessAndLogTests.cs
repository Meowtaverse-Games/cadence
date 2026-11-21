using Cadence.Tray.Logic;
using Xunit;

namespace Cadence.Tray.Tests;

public sealed class ProcessAndLogTests
{
    [Theory]
    [InlineData(true, TerminationResult.AlreadyExited)]
    [InlineData(false, TerminationResult.Failed)]
    public void InvalidOperationRequiresConfirmedExit(bool exited, TerminationResult expected)
    {
        Assert.Equal(expected, ProcessTermination.TryKill(() => throw new InvalidOperationException(), () => exited));
    }

    [Fact]
    public void UnknownExitAndAccessDeniedAreFailures()
    {
        Assert.Equal(TerminationResult.Failed, ProcessTermination.TryKill(
            () => throw new InvalidOperationException(), () => throw new InvalidOperationException()));
        Assert.Equal(TerminationResult.Failed, ProcessTermination.TryKill(
            () => throw new UnauthorizedAccessException(), () => false));
        Assert.Equal(TerminationResult.Killed, ProcessTermination.TryKill(() => { }, () => throw new InvalidOperationException()));
    }

    [Fact]
    public void FailureLogsAndNotificationsAreLimitedPerExecutableNotPerProcess()
    {
        var clock = new FakeClock();
        var logs = new List<string>();
        var notices = new List<bool>();
        var reporter = new ProcessNotifications(clock, logs.Add, (_, _, warning) => notices.Add(warning));
        reporter.Failure("Synthetic.exe", 100);
        reporter.Failure("SYNTHETIC.EXE", 101);
        Assert.Single(logs);
        Assert.Single(notices);
        clock.Now = clock.Now.AddSeconds(59);
        reporter.Failure("Synthetic.exe", 102);
        Assert.Single(logs);
        Assert.Single(notices);
        reporter.Failure("Other.exe", 103);
        Assert.Equal(2, logs.Count);
        Assert.Equal(2, notices.Count);
        clock.Now = clock.Now.AddSeconds(1);
        reporter.Failure("Synthetic.exe", 104);
        Assert.Equal(3, logs.Count);
        Assert.Equal(3, notices.Count);
        Assert.All(notices, warning => Assert.True(warning));
        Assert.Contains("pid=104 exe=Synthetic.exe", logs[2]);
    }

    [Fact]
    public void RecentSuccessDoesNotSuppressFirstFailureLog()
    {
        var logs = new List<string>();
        var notices = new List<bool>();
        var reporter = new ProcessNotifications(new FakeClock(), logs.Add, (_, _, warning) => notices.Add(warning));
        reporter.Success("Synthetic.exe");
        reporter.Failure("Synthetic.exe", 100);
        Assert.Single(logs);
        Assert.False(Assert.Single(notices));
    }

    [Fact]
    public void DailyLogAppendsWithoutReplacingOpenFileAndKeepsFourteenDays()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var clock = new FakeClock();
            var log = new DailyLog(directory, clock);
            var oldestRetained = Path.Combine(directory, "2025-12-23.txt");
            var expired = Path.Combine(directory, "2025-12-22.txt");
            File.WriteAllText(oldestRetained, "retained");
            File.WriteAllText(expired, "expired");
            log.Write("first");
            var path = Path.Combine(directory, "2026-01-05.txt");
            using (var reader = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                // No delete sharing: replacing this file on Windows would fail.
                log.Write("second");
                using var text = new StreamReader(reader);
                Assert.Equal("19:00:00 first" + Environment.NewLine + "19:00:00 second" + Environment.NewLine, text.ReadToEnd());
            }
            Assert.True(File.Exists(oldestRetained));
            Assert.False(File.Exists(expired));
            clock.Now = clock.Now.AddDays(1);
            log.Write("next_day");
            Assert.False(File.Exists(oldestRetained));
            Assert.Contains("next_day", File.ReadAllText(Path.Combine(directory, "2026-01-06.txt")));
        }
        finally { Directory.Delete(directory, recursive: true); }
    }
}
