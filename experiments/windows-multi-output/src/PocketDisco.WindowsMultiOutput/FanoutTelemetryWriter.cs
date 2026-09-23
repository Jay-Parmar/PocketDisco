using System.Text.Json;
using System.Text.Json.Serialization;

namespace PocketDisco.WindowsMultiOutput;

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
            result?.CommandUnixMilliseconds - result?.TargetUnixMilliseconds,
            result is null ? null : (long)Math.Round(result.InitialPosition.TotalMilliseconds),
            result?.WasLate);

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
        bool? WasLate);
}
