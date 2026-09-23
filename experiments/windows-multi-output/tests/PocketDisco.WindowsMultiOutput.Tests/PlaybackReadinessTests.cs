namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class PlaybackReadinessTests
{
    [TestMethod]
    public async Task CompletesAfterEveryPlayerOpens()
    {
        var readiness = new PlaybackReadiness(2);

        readiness.MarkOpened(0);
        Assert.IsFalse(readiness.Completion.IsCompleted);

        readiness.MarkOpened(1);
        await readiness.Completion;
    }

    [TestMethod]
    public async Task FailsWhenAnyPlayerFails()
    {
        var readiness = new PlaybackReadiness(2);

        readiness.MarkOpened(0);
        readiness.MarkFailed(1, "unsupported format");

        var error = await Assert.ThrowsExactlyAsync<InvalidOperationException>(
            async () => await readiness.Completion);
        Assert.Contains("output-2", error.Message);
        Assert.Contains("unsupported format", error.Message);
    }

    [TestMethod]
    public void RejectsInvalidPlayerIndex()
    {
        var readiness = new PlaybackReadiness(2);

        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => readiness.MarkOpened(2));
    }
}
