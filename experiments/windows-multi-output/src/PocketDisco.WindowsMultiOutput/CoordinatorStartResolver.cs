using System.Diagnostics;

namespace PocketDisco.WindowsMultiOutput;

public sealed record CoordinatorRunContext(
    Guid TrialId,
    long EffectiveAtUnixMilliseconds,
    long ClockUncertaintyMilliseconds,
    long CommandTargetTimestamp);

public sealed record CoordinatorStartResolution(
    ScheduledStart Plan,
    CoordinatorRunContext Context);

public sealed class CoordinatorStartResolver
{
    private static readonly TimeSpan MinimumRemainingLead = TimeSpan.FromSeconds(5);

    private readonly CoordinatorClient client;
    private readonly CoordinatorPlaybackOptions options;
    private readonly Func<long> stopwatchTimestamp;
    private readonly long stopwatchFrequency;
    private readonly Func<string> idempotencyKey;

    public CoordinatorStartResolver(
        CoordinatorClient client,
        CoordinatorPlaybackOptions options)
        : this(
            client,
            options,
            Stopwatch.GetTimestamp,
            Stopwatch.Frequency,
            () => Guid.NewGuid().ToString("D"))
    {
    }

    internal CoordinatorStartResolver(
        CoordinatorClient client,
        CoordinatorPlaybackOptions options,
        Func<long> stopwatchTimestamp,
        long stopwatchFrequency,
        Func<string> idempotencyKey)
    {
        ArgumentNullException.ThrowIfNull(client);
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(stopwatchTimestamp);
        ArgumentNullException.ThrowIfNull(idempotencyKey);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(stopwatchFrequency);

        this.client = client;
        this.options = options;
        this.stopwatchTimestamp = stopwatchTimestamp;
        this.stopwatchFrequency = stopwatchFrequency;
        this.idempotencyKey = idempotencyKey;
    }

    public async Task<CoordinatorStartResolution> ResolveAsync(
        CancellationToken cancellationToken = default)
    {
        var samples = new CoordinatorClockSample[CoordinatorClockEstimator.RequiredSampleCount];
        for (var index = 0; index < samples.Length; index++)
        {
            samples[index] = await client.SampleTimeAsync(cancellationToken);
        }

        var estimate = CoordinatorClockEstimator.Estimate(samples, stopwatchFrequency);
        var trial = options.CreateTrial
            ? await client.CreateTrialAsync(estimate, idempotencyKey(), cancellationToken)
            : await client.FetchTrialAsync(options.TrialId!.Value, cancellationToken);
        var deadline = estimate.StopwatchTimestampForServerUnixMilliseconds(
            trial.EffectiveAtUnixMilliseconds);
        var currentTimestamp = stopwatchTimestamp();
        ArgumentOutOfRangeException.ThrowIfNegative(currentTimestamp);
        var requiredLeadTicks = checked((long)decimal.Ceiling(
            (decimal)MinimumRemainingLead.TotalMilliseconds * stopwatchFrequency / 1_000));
        if (checked(deadline - currentTimestamp) < requiredLeadTicks)
        {
            throw new InvalidOperationException(
                "Coordinator trial has less than five seconds of remaining lead.");
        }

        var plan = new ScheduledStart(deadline, TimeSpan.Zero, WasLate: false);
        return new CoordinatorStartResolution(
            plan,
            new CoordinatorRunContext(
                trial.Id,
                trial.EffectiveAtUnixMilliseconds,
                estimate.UncertaintyMilliseconds,
                deadline));
    }
}
