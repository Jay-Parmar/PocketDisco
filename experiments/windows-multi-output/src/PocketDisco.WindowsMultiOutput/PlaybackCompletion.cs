namespace PocketDisco.WindowsMultiOutput;

public sealed class PlaybackCompletion
{
    private readonly bool[] ended;
    private readonly TaskCompletionSource completion = new(
        TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Lock stateLock = new();
    private int endedCount;

    public PlaybackCompletion(int playerCount)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(playerCount);
        ended = new bool[playerCount];
    }

    public Task Completion => completion.Task;

    public void MarkEnded(int playerIndex)
    {
        if (playerIndex < 0 || playerIndex >= ended.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(playerIndex));
        }

        lock (stateLock)
        {
            if (ended[playerIndex])
            {
                return;
            }

            ended[playerIndex] = true;
            endedCount++;
            if (endedCount == ended.Length)
            {
                completion.TrySetResult();
            }
        }
    }
}
