using StellarSend.Core.Security;
using StellarSend.Core.Shell;

namespace StellarSend.Core.Tests;

public sealed class SingleInstanceQueueTests
{
    [Fact]
    public void OnlyTheFirstLaunchStartsTheApp()
    {
        Assert.Equal(LaunchAction.StartApp, LaunchPlan.Decide(true, null, null));
        Assert.Equal(LaunchAction.StartApp, LaunchPlan.Decide(true, @"C:\docs\a.txt", null));
    }

    [Fact]
    public void ALaterLaunchAlwaysExitsAndNeverStartsASecondWindow()
    {
        Assert.Equal(LaunchAction.Exit, LaunchPlan.Decide(false, null, null));
        Assert.Equal(LaunchAction.Exit, LaunchPlan.Decide(false, @"C:\docs\a.txt", HandoffDecision.Accepted));
        Assert.Equal(LaunchAction.ExitFailed, LaunchPlan.Decide(false, @"C:\docs\a.txt", HandoffDecision.NoAnswer));
        Assert.NotEqual(LaunchAction.StartApp, LaunchPlan.Decide(false, @"C:\docs\a.txt", null));
    }

    [Fact]
    public void AFileLaunchWaitsLongEnoughForACopyThatIsStillStarting()
    {
        Assert.True(SendQueue.HandoffTimeout >= TimeSpan.FromSeconds(30));
        Assert.True(SendQueue.HandoffTimeout > SendQueue.AckTimeout);
    }

    [Fact]
    public void ManyLaunchesHandTheirFilesToOneRunningCopyAndItTakesThemAll()
    {
        var directory = Directory.CreateTempSubdirectory("ss-many");
        var log = new MemoryLog();
        var files = Enumerable.Range(1, 6).Select(i => $@"C:\apks\EARTH-3.10.{110 + i}.apk").ToList();
        var accepted = new List<string>();
        foreach (var file in files)
        {
            var clock = new JumpClock();
            var decision = SendQueue.Offer(directory.FullName, file, SendQueue.HandoffTimeout, log, clock, delay =>
            {
                accepted.AddRange(SendQueue.Drain(directory.FullName, log));
                clock.Advance(delay);
            });
            Assert.Equal(HandoffDecision.Accepted, decision);
            Assert.Equal(LaunchAction.Exit, LaunchPlan.Decide(false, file, decision));
        }

        Assert.Equal(files, accepted);
        Assert.Empty(Directory.GetFiles(directory.FullName));
        Assert.DoesNotContain(log.Lines, line => line.Contains("first FlipSend instance", StringComparison.Ordinal));
    }

    [Fact]
    public void AnUnansweredHandoffLeavesNothingBehindForALaterDoubleSend()
    {
        var directory = Directory.CreateTempSubdirectory("ss-noanswer");
        var log = new MemoryLog();
        var clock = new JumpClock();
        var decision = SendQueue.Offer(directory.FullName, @"C:\docs\a.txt", SendQueue.HandoffTimeout, log, clock, clock.Advance);
        Assert.Equal(HandoffDecision.NoAnswer, decision);
        Assert.Equal(LaunchAction.ExitFailed, LaunchPlan.Decide(false, @"C:\docs\a.txt", decision));
        Assert.Empty(Directory.GetFiles(directory.FullName));
        Assert.Empty(SendQueue.Drain(directory.FullName, log));
    }

    [Fact]
    public void TheRunningCopyKeepsItsClaimWhileLaterLaunchesComeAndGo()
    {
        var name = "StellarSend.Test." + Guid.NewGuid().ToString("N");
        var log = new MemoryLog();
        using var first = SingleInstanceClaim.Acquire(name, log);
        for (var i = 0; i < 5; i++)
        {
            using var later = SingleInstanceClaim.Acquire(name, log);
            Assert.False(later.IsFirst);
        }

        using var again = SingleInstanceClaim.Acquire(name, log);
        Assert.False(again.IsFirst);
        Assert.Single(log.Lines, line => line.Contains("first FlipSend instance", StringComparison.Ordinal));
    }

    private sealed class MemoryLog : ILog
    {
        public List<string> Lines { get; } = [];
        public void Info(string message) => Lines.Add(message);
        public void Warn(string message) => Lines.Add(message);
        public void Error(string message) => Lines.Add(message);
    }

    private sealed class JumpClock : TimeProvider
    {
        private DateTimeOffset _now = DateTimeOffset.UnixEpoch;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }
}
