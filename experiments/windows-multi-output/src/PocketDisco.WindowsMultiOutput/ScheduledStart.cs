namespace PocketDisco.WindowsMultiOutput;

public sealed record ScheduledStart(
    long DeadlineTimestamp,
    TimeSpan InitialPosition,
    bool WasLate)
{
    public void ValidateMediaPosition(TimeSpan mediaDuration)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(mediaDuration, TimeSpan.Zero);
        if (InitialPosition >= mediaDuration)
        {
            throw new InvalidOperationException("The scheduled start is past the end of the signal.");
        }
    }

    public long GetCommandErrorMilliseconds(
        long commandTimestamp,
        long stopwatchFrequency)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(stopwatchFrequency);
        var errorTicks = checked(commandTimestamp - DeadlineTimestamp);
        return (long)decimal.Round(
            (decimal)errorTicks * 1_000 / stopwatchFrequency,
            MidpointRounding.AwayFromZero);
    }

    public static ScheduledStart Create(
        long targetUnixMilliseconds,
        long sampledUnixMilliseconds,
        long sampledTimestamp,
        long currentTimestamp,
        long stopwatchFrequency)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(stopwatchFrequency);

        var wallDeltaMilliseconds = checked(targetUnixMilliseconds - sampledUnixMilliseconds);
        var monotonicDelta = decimal.Round(
            (decimal)wallDeltaMilliseconds * stopwatchFrequency / 1_000,
            MidpointRounding.AwayFromZero);
        var targetTimestamp = checked(sampledTimestamp + (long)monotonicDelta);

        if (targetTimestamp >= currentTimestamp)
        {
            return new ScheduledStart(targetTimestamp, TimeSpan.Zero, false);
        }

        var lateTicks = currentTimestamp - targetTimestamp;
        var initialPosition = TimeSpan.FromSeconds((double)lateTicks / stopwatchFrequency);
        return new ScheduledStart(currentTimestamp, initialPosition, true);
    }
}
