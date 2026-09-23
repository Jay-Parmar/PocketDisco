namespace PocketDisco.WindowsMultiOutput;

public static class CoordinatorStartWaiter
{
    public static async Task<CoordinatorStartResolution> ResolveAsync(
        Func<CancellationToken, Task<CoordinatorStartResolution>> resolve,
        Task playerFailure,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resolve);
        ArgumentNullException.ThrowIfNull(playerFailure);

        if (playerFailure.IsCompleted)
        {
            await ThrowPlayerFailureAsync(playerFailure).ConfigureAwait(false);
        }

        cancellationToken.ThrowIfCancellationRequested();
        var resolution = resolve(cancellationToken)
            ?? throw new InvalidOperationException("Coordinator resolution returned no task.");
        var cancellation = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        using var registration = cancellationToken.Register(
            static state => ((TaskCompletionSource)state!).TrySetResult(),
            cancellation);

        var finished = await Task.WhenAny(
            resolution,
            playerFailure,
            cancellation.Task).ConfigureAwait(false);
        if (playerFailure.IsCompleted)
        {
            ObserveFault(resolution);
            await ThrowPlayerFailureAsync(playerFailure).ConfigureAwait(false);
        }

        if (finished == cancellation.Task)
        {
            ObserveFault(resolution);
            cancellationToken.ThrowIfCancellationRequested();
        }

        var result = await resolution.ConfigureAwait(false);
        if (playerFailure.IsCompleted)
        {
            await ThrowPlayerFailureAsync(playerFailure).ConfigureAwait(false);
        }

        cancellationToken.ThrowIfCancellationRequested();
        return result;
    }

    private static async Task ThrowPlayerFailureAsync(Task playerFailure)
    {
        await playerFailure.ConfigureAwait(false);
        throw new InvalidOperationException("Player failure signal completed without an error.");
    }

    private static void ObserveFault(Task task)
    {
        _ = task.ContinueWith(
            static completed => _ = completed.Exception,
            CancellationToken.None,
            TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
    }
}
