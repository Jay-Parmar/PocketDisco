namespace PocketDisco.WindowsMultiOutput;

public static class PlaybackRunWaiter
{
    public static async Task WaitForCompletionAsync(
        Task completion,
        Task failure,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(completion);
        ArgumentNullException.ThrowIfNull(failure);
        ArgumentOutOfRangeException.ThrowIfLessThan(timeout, TimeSpan.Zero);

        var timeoutTask = Task.Delay(timeout, cancellationToken);
        var finished = await Task.WhenAny(completion, failure, timeoutTask);
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

            throw new TimeoutException("Players did not report completion before the timeout.");
        }

        await completion;
        if (failure.IsCompleted)
        {
            await failure;
        }
    }
}
