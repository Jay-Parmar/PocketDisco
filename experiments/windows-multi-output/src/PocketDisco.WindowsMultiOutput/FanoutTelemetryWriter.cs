using System.Text.Json;
using System.Text.Json.Serialization;

namespace PocketDisco.WindowsMultiOutput;

public enum FanoutFailureReason
{
    PlaybackFailed,
    Cancelled,
}

public static class FanoutTelemetryWriter
{
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
    };

    public static string ToNdjson(
        FanoutRunResult result,
        int outputCount,
        Guid runId)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(outputCount);

        var records = new List<FanoutTelemetryRecord>(outputCount * 3);
        for (var outputIndex = 0; outputIndex < outputCount; outputIndex++)
        {
            records.Add(CreateRecord(
                runId,
                outputIndex,
                "output_ready",
                result.ReadyUnixMilliseconds));
        }

        for (var outputIndex = 0; outputIndex < outputCount; outputIndex++)
        {
            records.Add(CreateRecord(
                runId,
                outputIndex,
                "playback_start_command",
                result.CommandUnixMilliseconds,
                result));
        }

        for (var outputIndex = 0; outputIndex < outputCount; outputIndex++)
        {
            records.Add(CreateRecord(
                runId,
                outputIndex,
                "playback_completed",
                result.CompletedUnixMilliseconds));
        }

        return string.Join(
            '\n',
            records.Select(record => JsonSerializer.Serialize(record, SerializerOptions))) + '\n';
    }

    public static string ToFailureNdjson(
        int outputCount,
        Guid runId,
        long timestampMilliseconds,
        FanoutFailureReason failureReason)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(outputCount);
        var reason = failureReason switch
        {
            FanoutFailureReason.PlaybackFailed => "playback_failed",
            FanoutFailureReason.Cancelled => "cancelled",
            _ => throw new ArgumentOutOfRangeException(nameof(failureReason)),
        };

        var records = Enumerable.Range(0, outputCount)
            .Select(outputIndex => new FanoutTelemetryRecord(
                1,
                "windows_multi_output_probe",
                runId.ToString("D"),
                "probe_failed",
                $"output-{outputIndex + 1}",
                "windows",
                "app_fanout",
                "generated_click",
                "failed",
                timestampMilliseconds,
                null,
                null,
                null,
                null,
                reason));

        return string.Join(
            '\n',
            records.Select(record => JsonSerializer.Serialize(record, SerializerOptions))) + '\n';
    }

    private static FanoutTelemetryRecord CreateRecord(
        Guid runId,
        int outputIndex,
        string eventType,
        long timestampMilliseconds,
        FanoutRunResult? result = null) =>
        new(
            1,
            "windows_multi_output_probe",
            runId.ToString("D"),
            eventType,
            $"output-{outputIndex + 1}",
            "windows",
            "app_fanout",
            "generated_click",
            "ok",
            timestampMilliseconds,
            result?.TargetUnixMilliseconds,
            result?.CommandErrorMilliseconds,
            result is null ? null : (long)Math.Round(result.InitialPosition.TotalMilliseconds),
            result?.WasLate,
            null);

    private sealed record FanoutTelemetryRecord(
        int SchemaVersion,
        string RecordType,
        string RunId,
        string EventType,
        string OutputId,
        string Platform,
        string RouteMode,
        string Source,
        string Outcome,
        long TimestampMs,
        long? TargetTimestampMs,
        long? CommandErrorMs,
        long? InitialPositionMs,
        bool? WasLate,
        string? FailureReason);
}
