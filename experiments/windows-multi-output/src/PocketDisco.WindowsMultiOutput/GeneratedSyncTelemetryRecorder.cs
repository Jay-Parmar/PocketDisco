using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PocketDisco.WindowsMultiOutput;

public enum GeneratedSyncFailureReason
{
    PlayerFailed,
    Cancelled,
}

public sealed class GeneratedSyncTelemetryRecorder
{
    private const string CommandIssued = "command_issued";
    private const string PlaybackObserved = "playback_observed";
    private static readonly string[] OutputCategories =
        ["built_in", "wired", "bluetooth", "usb", "virtual", "mixed"];
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
    };

    private readonly string scenarioId;
    private readonly string clientId;
    private readonly string outputCategory;
    private readonly long stopwatchFrequency;
    private readonly Lock stateLock = new();
    private FrozenContext? context;
    private GeneratedSyncTelemetryRecord? commandObservation;
    private GeneratedSyncTelemetryRecord? playbackObservation;

    public GeneratedSyncTelemetryRecorder(
        string scenarioId,
        string clientId,
        string outputCategory,
        long stopwatchFrequency)
    {
        this.scenarioId = NormalizeIdentity(scenarioId, nameof(scenarioId));
        this.clientId = NormalizeIdentity(clientId, nameof(clientId));
        if (!OutputCategories.Contains(outputCategory, StringComparer.Ordinal))
        {
            throw new ArgumentException("Sync output category is invalid.", nameof(outputCategory));
        }

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(stopwatchFrequency);
        this.outputCategory = outputCategory;
        this.stopwatchFrequency = stopwatchFrequency;
    }

    public void Begin(CoordinatorRunContext runContext)
    {
        ArgumentNullException.ThrowIfNull(runContext);
        ArgumentOutOfRangeException.ThrowIfNegative(runContext.EffectiveAtUnixMilliseconds);
        ArgumentOutOfRangeException.ThrowIfNegative(runContext.ClockUncertaintyMilliseconds);
        ArgumentOutOfRangeException.ThrowIfNegative(runContext.CommandTargetTimestamp);

        lock (stateLock)
        {
            if (context is not null)
            {
                throw new InvalidOperationException("Sync telemetry context is already set.");
            }

            context = new FrozenContext(
                runContext.TrialId.ToString("D"),
                runContext.EffectiveAtUnixMilliseconds,
                runContext.ClockUncertaintyMilliseconds,
                runContext.CommandTargetTimestamp);
        }
    }

    public void RecordCommandIssued(long stopwatchTimestamp) =>
        RecordSuccess(CommandIssued, stopwatchTimestamp);

    public void RecordPlaybackObserved(long stopwatchTimestamp) =>
        RecordSuccess(PlaybackObserved, stopwatchTimestamp);

    public void RecordFailure(GeneratedSyncFailureReason reason)
    {
        var wireReason = reason switch
        {
            GeneratedSyncFailureReason.PlayerFailed => "player_failed",
            GeneratedSyncFailureReason.Cancelled => "cancelled",
            _ => throw new ArgumentOutOfRangeException(nameof(reason)),
        };

        lock (stateLock)
        {
            if (context is null)
            {
                return;
            }

            commandObservation ??= CreateFailure(context, CommandIssued, wireReason);
            playbackObservation ??= CreateFailure(context, PlaybackObserved, wireReason);
        }
    }

    public string ToNdjson()
    {
        lock (stateLock)
        {
            var records = new[] { commandObservation, playbackObservation }
                .Where(record => record is not null)
                .Select(record => JsonSerializer.Serialize(record, SerializerOptions))
                .ToArray();
            return records.Length == 0
                ? string.Empty
                : string.Join('\n', records) + '\n';
        }
    }

    private void RecordSuccess(string eventType, long stopwatchTimestamp)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(stopwatchTimestamp);
        lock (stateLock)
        {
            if (context is null)
            {
                return;
            }

            if (eventType == CommandIssued)
            {
                commandObservation ??= CreateSuccess(context, eventType, stopwatchTimestamp);
            }
            else
            {
                playbackObservation ??= CreateSuccess(context, eventType, stopwatchTimestamp);
            }
        }
    }

    private GeneratedSyncTelemetryRecord CreateSuccess(
        FrozenContext activeContext,
        string eventType,
        long stopwatchTimestamp)
    {
        var deltaMilliseconds = decimal.Round(
            ((decimal)stopwatchTimestamp - activeContext.CommandTargetTimestamp)
                * 1_000 / stopwatchFrequency,
            0,
            MidpointRounding.AwayFromZero);
        var timestampMilliseconds = checked(
            activeContext.EffectiveAtUnixMilliseconds + (long)deltaMilliseconds);
        if (timestampMilliseconds < 0)
        {
            throw new InvalidOperationException("Mapped coordinator timestamp is negative.");
        }

        return CreateRecord(
            activeContext,
            eventType,
            outcome: "ok",
            timestampMilliseconds,
            activeContext.EffectiveAtUnixMilliseconds,
            $"coordinator:{activeContext.TrialId}",
            "coordinator_estimate",
            activeContext.ClockUncertaintyMilliseconds,
            failureReason: null);
    }

    private GeneratedSyncTelemetryRecord CreateFailure(
        FrozenContext activeContext,
        string eventType,
        string failureReason) =>
        CreateRecord(
            activeContext,
            eventType,
            outcome: "failure",
            timestampMilliseconds: null,
            targetTimestampMilliseconds: null,
            clockId: null,
            clockSource: null,
            clockUncertaintyMilliseconds: null,
            failureReason);

    private GeneratedSyncTelemetryRecord CreateRecord(
        FrozenContext activeContext,
        string eventType,
        string outcome,
        long? timestampMilliseconds,
        long? targetTimestampMilliseconds,
        string? clockId,
        string? clockSource,
        long? clockUncertaintyMilliseconds,
        string? failureReason) =>
        new(
            SchemaVersion: 2,
            eventType,
            scenarioId,
            activeContext.TrialId,
            activeContext.EffectiveAtUnixMilliseconds.ToString(CultureInfo.InvariantCulture),
            DeviceId: clientId,
            Platform: "windows",
            Environment: "physical",
            Provider: "generated_audio",
            SignalId: ToneGenerator.SignalId,
            SignalSha256: ToneGenerator.PcmSha256,
            outputCategory,
            RouteMode: "app_fanout",
            outcome,
            TimestampMs: timestampMilliseconds,
            TargetTimestampMs: targetTimestampMilliseconds,
            clockId,
            clockSource,
            ClockUncertaintyMs: clockUncertaintyMilliseconds,
            failureReason);

    private static string NormalizeIdentity(string value, string parameterName)
    {
        ArgumentNullException.ThrowIfNull(value, parameterName);
        var normalized = value.Trim();
        if (normalized.Length is < 1 or > 100 || normalized.Any(char.IsControl))
        {
            throw new ArgumentException("Sync telemetry identity is invalid.", parameterName);
        }

        return normalized;
    }

    private sealed record FrozenContext(
        string TrialId,
        long EffectiveAtUnixMilliseconds,
        long ClockUncertaintyMilliseconds,
        long CommandTargetTimestamp);

    private sealed record GeneratedSyncTelemetryRecord(
        int SchemaVersion,
        string EventType,
        string ScenarioId,
        string TrialId,
        string StartId,
        string DeviceId,
        string Platform,
        string Environment,
        string Provider,
        string SignalId,
        string SignalSha256,
        string OutputCategory,
        string RouteMode,
        string Outcome,
        long? TimestampMs,
        long? TargetTimestampMs,
        string? ClockId,
        string? ClockSource,
        long? ClockUncertaintyMs,
        string? FailureReason);
}
