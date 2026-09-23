namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class PlaybackPlayingAggregatorTests
{
    [TestMethod]
    public void CapturesWhenEveryPlayerIsPlaying()
    {
        var aggregator = new PlaybackPlayingAggregator(2, () => 12_345);

        Assert.IsNull(aggregator.Update(0, isPlaying: true));
        Assert.AreEqual(12_345, aggregator.Update(1, isPlaying: true));
        Assert.AreEqual(12_345, aggregator.FirstBothPlayingTimestamp);
    }

    [TestMethod]
    public void RequiresPlayersToBePlayingAtTheSameTime()
    {
        var aggregator = new PlaybackPlayingAggregator(2, () => 12_345);

        aggregator.Update(0, isPlaying: true);
        aggregator.Update(0, isPlaying: false);
        Assert.IsNull(aggregator.Update(1, isPlaying: true));

        Assert.AreEqual(12_345, aggregator.Update(0, isPlaying: true));
    }

    [TestMethod]
    public void CapturesOnlyTheFirstJointPlayingTimestamp()
    {
        var timestampCalls = 0;
        var aggregator = new PlaybackPlayingAggregator(
            2,
            () => Interlocked.Increment(ref timestampCalls) * 100L);

        aggregator.Update(0, isPlaying: true);
        aggregator.Update(1, isPlaying: true);
        aggregator.Update(1, isPlaying: false);
        aggregator.Update(1, isPlaying: true);

        Assert.AreEqual(100, aggregator.FirstBothPlayingTimestamp);
        Assert.AreEqual(1, timestampCalls);
    }

    [TestMethod]
    public void ConcurrentPlayingEventsCaptureOneTimestamp()
    {
        var timestampCalls = 0;
        var aggregator = new PlaybackPlayingAggregator(
            2,
            () => Interlocked.Increment(ref timestampCalls));
        long? firstResult = null;
        long? secondResult = null;

        Parallel.Invoke(
            () => firstResult = aggregator.Update(0, isPlaying: true),
            () => secondResult = aggregator.Update(1, isPlaying: true));

        Assert.AreEqual(1, timestampCalls);
        Assert.AreEqual(1, aggregator.FirstBothPlayingTimestamp);
        Assert.AreEqual(1, new[] { firstResult, secondResult }.Count(value => value.HasValue));
    }

    [TestMethod]
    public void RejectsInvalidInputs()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() =>
            new PlaybackPlayingAggregator(0, () => 0));
        Assert.ThrowsExactly<ArgumentNullException>(() =>
            new PlaybackPlayingAggregator(2, null!));

        var aggregator = new PlaybackPlayingAggregator(2, () => 0);
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() =>
            aggregator.Update(2, isPlaying: true));
    }
}
