namespace PocketDisco.WindowsMultiOutput;

public sealed class PlaybackPlayingAggregator
{
    private readonly bool[] playing;
    private readonly Func<long> timestamp;
    private readonly Lock stateLock = new();
    private long? firstBothPlayingTimestamp;

    public PlaybackPlayingAggregator(int playerCount, Func<long> timestamp)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(playerCount);
        ArgumentNullException.ThrowIfNull(timestamp);
        playing = new bool[playerCount];
        this.timestamp = timestamp;
    }

    public long? FirstBothPlayingTimestamp
    {
        get
        {
            lock (stateLock)
            {
                return firstBothPlayingTimestamp;
            }
        }
    }

    public long? Update(int playerIndex, bool isPlaying)
    {
        if (playerIndex < 0 || playerIndex >= playing.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(playerIndex));
        }

        lock (stateLock)
        {
            if (firstBothPlayingTimestamp.HasValue)
            {
                return null;
            }

            playing[playerIndex] = isPlaying;
            if (!playing.All(value => value))
            {
                return null;
            }

            var observedTimestamp = timestamp();
            ArgumentOutOfRangeException.ThrowIfNegative(observedTimestamp);
            firstBothPlayingTimestamp = observedTimestamp;
            return observedTimestamp;
        }
    }
}
