namespace PocketDisco.WindowsMultiOutput;

public sealed class PlaybackReadiness
{
    private readonly bool[] opened;
    private readonly TaskCompletionSource completion = new(
        TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Lock stateLock = new();
    private int openedCount;

    public PlaybackReadiness(int playerCount)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(playerCount);
        opened = new bool[playerCount];
    }

    public Task Completion => completion.Task;

    public void MarkOpened(int playerIndex)
    {
        ValidateIndex(playerIndex);

        lock (stateLock)
        {
            if (opened[playerIndex] || completion.Task.IsCompleted)
            {
                return;
            }

            opened[playerIndex] = true;
            openedCount++;
            if (openedCount == opened.Length)
            {
                completion.TrySetResult();
            }
        }
    }

    public void MarkFailed(int playerIndex, string reason)
    {
        ValidateIndex(playerIndex);
        completion.TrySetException(
            new InvalidOperationException($"output-{playerIndex + 1} failed: {reason}"));
    }

    private void ValidateIndex(int playerIndex)
    {
        if (playerIndex < 0 || playerIndex >= opened.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(playerIndex));
        }
    }
}
