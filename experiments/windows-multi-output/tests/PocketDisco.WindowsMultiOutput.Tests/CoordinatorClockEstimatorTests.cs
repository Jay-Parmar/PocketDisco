namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorClockEstimatorTests
{
    [TestMethod]
    public void MatchesAndroidLowestThreeSampleEstimate()
    {
        var samples = new[]
        {
            Sample(1_000, 100, 11_050, 0),
            Sample(2_000, 20, 12_011, 2),
            Sample(3_000, 30, 13_014, 4),
            Sample(4_000, 10, 14_005, 0),
            Sample(5_000, 200, 14_900, 0),
            Sample(6_000, 300, 16_300, 0),
            Sample(7_000, 400, 16_500, 0),
        };

        var estimate = CoordinatorClockEstimator.Estimate(samples, stopwatchFrequency: 1_000);

        Assert.AreEqual(10_000, estimate.ServerToStopwatchOffsetMilliseconds);
        Assert.AreEqual(6, estimate.UncertaintyMilliseconds);
        Assert.AreEqual(10, estimate.BestNetworkRoundTripTimeMilliseconds);
        Assert.AreEqual(7, estimate.SampleCount);
        Assert.AreEqual(
            20_000,
            estimate.StopwatchTimestampForServerUnixMilliseconds(30_000));
    }

    [TestMethod]
    public void MapsServerUnixDirectlyToStopwatchTicks()
    {
        var samples = Enumerable.Range(0, CoordinatorClockEstimator.RequiredSampleCount)
            .Select(_ => new CoordinatorClockSample(
                clientSendTimestamp: 20_000_000,
                clientReceiveTimestamp: 20_100_000,
                serverReceiveUnixMilliseconds: 102_000,
                serverSendUnixMilliseconds: 102_010))
            .ToArray();

        var estimate = CoordinatorClockEstimator.Estimate(
            samples,
            stopwatchFrequency: 10_000_000);

        Assert.AreEqual(
            50_000_000,
            estimate.StopwatchTimestampForServerUnixMilliseconds(105_000));
    }

    [TestMethod]
    public void ClampsNegativeNetworkRoundTripToZero()
    {
        var samples = Enumerable.Range(0, CoordinatorClockEstimator.RequiredSampleCount)
            .Select(_ => new CoordinatorClockSample(
                clientSendTimestamp: 1_000,
                clientReceiveTimestamp: 1_010,
                serverReceiveUnixMilliseconds: 10_000,
                serverSendUnixMilliseconds: 10_020))
            .ToArray();

        var estimate = CoordinatorClockEstimator.Estimate(samples, stopwatchFrequency: 1_000);

        Assert.AreEqual(0, estimate.BestNetworkRoundTripTimeMilliseconds);
    }

    [TestMethod]
    [DataRow(6)]
    [DataRow(8)]
    public void RequiresExactlySevenSamples(int sampleCount)
    {
        var samples = Enumerable.Range(0, sampleCount)
            .Select(_ => Sample(1_000, 10, 11_005, 0))
            .ToArray();

        Assert.ThrowsExactly<ArgumentException>(() =>
            CoordinatorClockEstimator.Estimate(samples, stopwatchFrequency: 1_000));
    }

    [TestMethod]
    public void RejectsReversedClientAndServerTimes()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() =>
            new CoordinatorClockSample(11, 10, 20, 21));
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() =>
            new CoordinatorClockSample(10, 11, 21, 20));
    }

    [TestMethod]
    public void RejectsNonPositiveStopwatchFrequency()
    {
        var samples = Enumerable.Range(0, CoordinatorClockEstimator.RequiredSampleCount)
            .Select(_ => Sample(1_000, 10, 11_005, 0))
            .ToArray();

        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() =>
            CoordinatorClockEstimator.Estimate(samples, stopwatchFrequency: 0));
    }

    [TestMethod]
    public void ThrowsWhenServerToStopwatchSubtractionOverflows()
    {
        var samples = Enumerable.Range(0, CoordinatorClockEstimator.RequiredSampleCount)
            .Select(_ => new CoordinatorClockSample(
                clientSendTimestamp: long.MaxValue,
                clientReceiveTimestamp: long.MaxValue,
                serverReceiveUnixMilliseconds: 0,
                serverSendUnixMilliseconds: 0))
            .ToArray();
        var estimate = CoordinatorClockEstimator.Estimate(samples, stopwatchFrequency: 1_000);

        Assert.ThrowsExactly<OverflowException>(() =>
            estimate.StopwatchTimestampForServerUnixMilliseconds(long.MaxValue));
    }

    [TestMethod]
    public void ThrowsWhenMappedStopwatchTimestampOverflows()
    {
        var samples = Enumerable.Range(0, CoordinatorClockEstimator.RequiredSampleCount)
            .Select(_ => new CoordinatorClockSample(0, 0, 0, 0))
            .ToArray();
        var estimate = CoordinatorClockEstimator.Estimate(
            samples,
            stopwatchFrequency: long.MaxValue);

        Assert.ThrowsExactly<OverflowException>(() =>
            estimate.StopwatchTimestampForServerUnixMilliseconds(1_001));
    }

    private static CoordinatorClockSample Sample(
        long clientStart,
        long localRoundTrip,
        long serverMidpoint,
        long serverWork)
    {
        var serverReceive = serverMidpoint - (serverWork / 2);
        return new CoordinatorClockSample(
            clientStart,
            clientStart + localRoundTrip,
            serverReceive,
            serverReceive + serverWork);
    }
}
