namespace PocketDisco.WindowsMultiOutput;

public sealed class PlaybackStartGate
{
    private readonly TaskCompletionSource failure = new(
        TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Lock stateLock = new();
    private InvalidOperationException? failureException;

    public Task Failure => failure.Task;

    public void IssueStart(Action startCommand)
    {
        ArgumentNullException.ThrowIfNull(startCommand);

        lock (stateLock)
        {
            if (failureException is not null)
            {
                throw failureException;
            }

            startCommand();
        }
    }

    public void MarkFailed(int playerIndex, string reason)
    {
        MarkFailure($"output-{playerIndex + 1} failed: {reason}");
    }

    public void MarkControllerFailed(string reason)
    {
        MarkFailure($"timeline controller failed: {reason}");
    }

    private void MarkFailure(string message)
    {
        lock (stateLock)
        {
            if (failureException is not null)
            {
                return;
            }

            failureException = new InvalidOperationException(message);
            failure.TrySetException(failureException);
        }
    }
}
