namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorStartWaiterTests
{
    [TestMethod]
    public async Task ReturnsResolvedStart()
    {
        var expected = Resolution();

        var actual = await CoordinatorStartWaiter.ResolveAsync(
            _ => Task.FromResult(expected),
            Task.Delay(Timeout.InfiniteTimeSpan),
            CancellationToken.None);

        Assert.AreEqual(expected, actual);
    }

    [TestMethod]
    public async Task PlayerFailureInterruptsTargetResolution()
    {
        var resolutionStarted = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var resolution = new TaskCompletionSource<CoordinatorStartResolution>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var failure = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var waiting = CoordinatorStartWaiter.ResolveAsync(
            _ =>
            {
                resolutionStarted.SetResult();
                return resolution.Task;
            },
            failure.Task,
            CancellationToken.None);
        await resolutionStarted.Task;

        failure.SetException(new InvalidOperationException("output failed"));

        var error = await Assert.ThrowsExactlyAsync<InvalidOperationException>(async () =>
            await waiting);
        Assert.AreEqual("output failed", error.Message);
    }

    [TestMethod]
    public async Task CancellationInterruptsAResolverThatIgnoresItsToken()
    {
        using var cancellation = new CancellationTokenSource();
        var resolutionStarted = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var resolution = new TaskCompletionSource<CoordinatorStartResolution>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var waiting = CoordinatorStartWaiter.ResolveAsync(
            _ =>
            {
                resolutionStarted.SetResult();
                return resolution.Task;
            },
            Task.Delay(Timeout.InfiniteTimeSpan),
            cancellation.Token);
        await resolutionStarted.Task;

        cancellation.Cancel();

        await Assert.ThrowsExactlyAsync<OperationCanceledException>(async () =>
            await waiting);
    }

    [TestMethod]
    public async Task DoesNotStartResolutionAfterAnEarlyFailure()
    {
        var called = false;
        var failure = Task.FromException(new InvalidOperationException("already failed"));

        await Assert.ThrowsExactlyAsync<InvalidOperationException>(async () =>
            await CoordinatorStartWaiter.ResolveAsync(
                _ =>
                {
                    called = true;
                    return Task.FromResult(Resolution());
                },
                failure,
                CancellationToken.None));

        Assert.IsFalse(called);
    }

    [TestMethod]
    public async Task FailureWinsWhenResolutionIsAlsoReady()
    {
        var failure = Task.FromException(new InvalidOperationException("failed"));

        var error = await Assert.ThrowsExactlyAsync<InvalidOperationException>(async () =>
            await CoordinatorStartWaiter.ResolveAsync(
                _ => Task.FromResult(Resolution()),
                failure,
                CancellationToken.None));

        Assert.AreEqual("failed", error.Message);
    }

    private static CoordinatorStartResolution Resolution() => new(
        new ScheduledStart(10_000, TimeSpan.Zero, WasLate: false),
        new CoordinatorRunContext(
            Guid.Parse("11111111-2222-3333-4444-555555555555"),
            110_000,
            2,
            10_000));
}
