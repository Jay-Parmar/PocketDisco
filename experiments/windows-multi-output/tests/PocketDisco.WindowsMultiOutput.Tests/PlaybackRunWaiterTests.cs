namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class PlaybackRunWaiterTests
{
    [TestMethod]
    public async Task CancellationIsNotReportedAsTimeout()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();

        await Assert.ThrowsExactlyAsync<TaskCanceledException>(async () =>
            await PlaybackRunWaiter.WaitForCompletionAsync(
                Task.Delay(Timeout.InfiniteTimeSpan),
                Task.Delay(Timeout.InfiniteTimeSpan),
                TimeSpan.FromMinutes(1),
                cancellation.Token));
    }

    [TestMethod]
    public async Task ReportsCompletionTimeout()
    {
        await Assert.ThrowsExactlyAsync<TimeoutException>(async () =>
            await PlaybackRunWaiter.WaitForCompletionAsync(
                Task.Delay(Timeout.InfiniteTimeSpan),
                Task.Delay(Timeout.InfiniteTimeSpan),
                TimeSpan.Zero,
                CancellationToken.None));
    }

    [TestMethod]
    public async Task FailureWinsWhenCompletionIsAlsoReady()
    {
        var failure = Task.FromException(new InvalidOperationException("failed"));

        var error = await Assert.ThrowsExactlyAsync<InvalidOperationException>(async () =>
            await PlaybackRunWaiter.WaitForCompletionAsync(
                Task.CompletedTask,
                failure,
                TimeSpan.FromMinutes(1),
                CancellationToken.None));
        Assert.AreEqual("failed", error.Message);
    }
}
