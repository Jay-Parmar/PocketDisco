namespace PocketDisco.WindowsMultiOutput;

public static class PlaybackRunWaiter
{
    public static async Task WaitForCompletionAsync(
        Task completion,
        Task failure,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        await WaitForTerminalAsync(
            completion,
            failure,
            timeout,
            "Players did not report completion before the timeout.",
            cancellationToken);
    }

    public static async Task WaitForReadinessAsync(
        Task readiness,
        Task failure,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        await WaitForTerminalAsync(
            readiness,
            failure,
            timeout,
            "Players did not become ready before the timeout.",
            cancellationToken);
    }

    private static async Task WaitForTerminalAsync(
        Task success,
        Task failure,
        TimeSpan timeout,
        string timeoutMessage,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(success);
        ArgumentNullException.ThrowIfNull(failure);
        ArgumentOutOfRangeException.ThrowIfLessThan(timeout, TimeSpan.Zero);

        var timeoutTask = Task.Delay(timeout, cancellationToken);
        var finished = await Task.WhenAny(success, failure, timeoutTask);
        if (failure.IsCompleted)
        {
            await failure;
        }

        if (finished == timeoutTask)
        {
            await timeoutTask;
            if (failure.IsCompleted)
            {
                await failure;
            }

            throw new TimeoutException(timeoutMessage);
        }

        await success;
        if (failure.IsCompleted)
        {
            await failure;
        }
    }
}
