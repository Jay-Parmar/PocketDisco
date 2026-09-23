namespace PocketDisco.WindowsMultiOutput;

public sealed class CoordinatorClockSample
{
    public CoordinatorClockSample(
        long clientSendTimestamp,
        long clientReceiveTimestamp,
        long serverReceiveUnixMilliseconds,
        long serverSendUnixMilliseconds)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(clientSendTimestamp);
        ArgumentOutOfRangeException.ThrowIfLessThan(
            clientReceiveTimestamp,
            clientSendTimestamp);
        ArgumentOutOfRangeException.ThrowIfNegative(serverReceiveUnixMilliseconds);
        ArgumentOutOfRangeException.ThrowIfLessThan(
            serverSendUnixMilliseconds,
            serverReceiveUnixMilliseconds);

        ClientSendTimestamp = clientSendTimestamp;
        ClientReceiveTimestamp = clientReceiveTimestamp;
        ServerReceiveUnixMilliseconds = serverReceiveUnixMilliseconds;
        ServerSendUnixMilliseconds = serverSendUnixMilliseconds;
    }

    public long ClientSendTimestamp { get; }

    public long ClientReceiveTimestamp { get; }

    public long ServerReceiveUnixMilliseconds { get; }

    public long ServerSendUnixMilliseconds { get; }

    internal CoordinatorClockMeasurement Measure(long stopwatchFrequency)
    {
        var clientSendMilliseconds = CoordinatorClockMath.TimestampToMilliseconds(
            ClientSendTimestamp,
            stopwatchFrequency);
        var clientReceiveMilliseconds = CoordinatorClockMath.TimestampToMilliseconds(
            ClientReceiveTimestamp,
            stopwatchFrequency);
        var roundTripTimeMilliseconds = checked(
            clientReceiveMilliseconds - clientSendMilliseconds);
        var serverProcessingTimeMilliseconds = checked(
            ServerSendUnixMilliseconds - ServerReceiveUnixMilliseconds);
        var networkRoundTripTimeMilliseconds = Math.Max(
            0,
            checked(roundTripTimeMilliseconds - serverProcessingTimeMilliseconds));
        var clientMidpoint = checked(
            clientSendMilliseconds + (roundTripTimeMilliseconds / 2));
        var serverMidpoint = checked(
            ServerReceiveUnixMilliseconds + (serverProcessingTimeMilliseconds / 2));

        return new CoordinatorClockMeasurement(
            networkRoundTripTimeMilliseconds,
            checked(serverMidpoint - clientMidpoint));
    }
}

public sealed record CoordinatorClockEstimate(
    long ServerToStopwatchOffsetMilliseconds,
    long UncertaintyMilliseconds,
    long BestNetworkRoundTripTimeMilliseconds,
    int SampleCount,
    long StopwatchFrequency)
{
    public long StopwatchTimestampForServerUnixMilliseconds(
        long serverUnixMilliseconds)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(serverUnixMilliseconds);
        var stopwatchMilliseconds = checked(
            serverUnixMilliseconds - ServerToStopwatchOffsetMilliseconds);
        return CoordinatorClockMath.MillisecondsToTimestamp(
            stopwatchMilliseconds,
            StopwatchFrequency);
    }
}

public static class CoordinatorClockEstimator
{
    public const int RequiredSampleCount = 7;

    private const int PreferredSampleCount = 3;

    public static CoordinatorClockEstimate Estimate(
        IReadOnlyList<CoordinatorClockSample> samples,
        long stopwatchFrequency)
    {
        ArgumentNullException.ThrowIfNull(samples);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(stopwatchFrequency);
        if (samples.Count != RequiredSampleCount)
        {
            throw new ArgumentException(
                $"Exactly {RequiredSampleCount} clock samples are required.",
                nameof(samples));
        }

        var preferred = samples
            .Select(sample => sample.Measure(stopwatchFrequency))
            .OrderBy(sample => sample.NetworkRoundTripTimeMilliseconds)
            .Take(PreferredSampleCount)
            .ToArray();
        var offsets = preferred
            .Select(sample => sample.ServerToStopwatchOffsetMilliseconds)
            .Order()
            .ToArray();
        var medianOffset = offsets[offsets.Length / 2];
        var offsetSpread = offsets.Max(offset => CheckedDistance(offset, medianOffset));
        var bestNetworkRoundTrip = preferred.Min(
            sample => sample.NetworkRoundTripTimeMilliseconds);

        return new CoordinatorClockEstimate(
            medianOffset,
            checked((bestNetworkRoundTrip / 2) + offsetSpread),
            bestNetworkRoundTrip,
            samples.Count,
            stopwatchFrequency);
    }

    private static long CheckedDistance(long value, long reference)
    {
        var distance = Math.Abs((decimal)value - reference);
        return checked((long)distance);
    }
}

internal sealed record CoordinatorClockMeasurement(
    long NetworkRoundTripTimeMilliseconds,
    long ServerToStopwatchOffsetMilliseconds);

internal static class CoordinatorClockMath
{
    public static long TimestampToMilliseconds(long timestamp, long frequency)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(timestamp);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(frequency);
        var milliseconds = decimal.Truncate((decimal)timestamp * 1_000 / frequency);
        return checked((long)milliseconds);
    }

    public static long MillisecondsToTimestamp(long milliseconds, long frequency)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(frequency);
        var timestamp = decimal.Round(
            (decimal)milliseconds * frequency / 1_000,
            MidpointRounding.AwayFromZero);
        return checked((long)timestamp);
    }
}
