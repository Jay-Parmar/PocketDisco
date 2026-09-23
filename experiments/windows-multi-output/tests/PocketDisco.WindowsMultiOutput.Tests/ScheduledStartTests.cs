namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class ScheduledStartTests
{
    private const long Frequency = 10_000_000;

    [TestMethod]
    public void MapsFutureWallTimeToMonotonicDeadline()
    {
        var plan = ScheduledStart.Create(
            targetUnixMilliseconds: 105_000,
            sampledUnixMilliseconds: 100_000,
            sampledTimestamp: 20_000_000,
            currentTimestamp: 21_000_000,
            Frequency);

        Assert.AreEqual(70_000_000, plan.DeadlineTimestamp);
        Assert.AreEqual(TimeSpan.Zero, plan.InitialPosition);
        Assert.IsFalse(plan.WasLate);
    }

    [TestMethod]
    public void AdvancesMediaPositionForLateStart()
    {
        var plan = ScheduledStart.Create(
            targetUnixMilliseconds: 99_500,
            sampledUnixMilliseconds: 100_000,
            sampledTimestamp: 20_000_000,
            currentTimestamp: 30_000_000,
            Frequency);

        Assert.AreEqual(30_000_000, plan.DeadlineTimestamp);
        Assert.AreEqual(TimeSpan.FromMilliseconds(1_500), plan.InitialPosition);
        Assert.IsTrue(plan.WasLate);
    }

    [TestMethod]
    public void RejectsInvalidStopwatchFrequency()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() =>
            ScheduledStart.Create(105_000, 100_000, 20_000_000, 21_000_000, 0));
    }

    [TestMethod]
    public void RejectsStartWhenInitialPositionReachesTrackDuration()
    {
        var plan = ScheduledStart.Create(
            targetUnixMilliseconds: 98_000,
            sampledUnixMilliseconds: 100_000,
            sampledTimestamp: 20_000_000,
            currentTimestamp: 30_000_000,
            Frequency);

        Assert.ThrowsExactly<InvalidOperationException>(
            () => plan.ValidateMediaPosition(TimeSpan.FromSeconds(2)));
    }
}
