using System.Text.Json;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class GeneratedSyncTelemetryRecorderTests
{
    private static readonly Guid TrialId = Guid.Parse(
        "11111111-2222-3333-4444-555555555555");

    [TestMethod]
    public void ExportsOneCommandAndOnePlaybackObservation()
    {
        var recorder = Recorder(stopwatchFrequency: 1_000);
        recorder.Begin(Context());

        recorder.RecordPlaybackObserved(4_012);
        recorder.RecordCommandIssued(4_004);
        recorder.RecordPlaybackObserved(4_020);
        recorder.RecordCommandIssued(4_021);

        var records = Parse(recorder.ToNdjson());

        Assert.HasCount(2, records);
        AssertSuccess(records[0], "command_issued", 10_004);
        AssertSuccess(records[1], "playback_observed", 10_012);
    }

    [TestMethod]
    public void ConvertsStopwatchTicksAroundTheCoordinatorTarget()
    {
        var recorder = Recorder(stopwatchFrequency: 10_000);
        recorder.Begin(Context(commandTargetTimestamp: 50_000));

        recorder.RecordCommandIssued(49_985);
        recorder.RecordPlaybackObserved(50_016);

        var records = Parse(recorder.ToNdjson());
        Assert.AreEqual(9_998, records[0].GetProperty("timestamp_ms").GetInt64());
        Assert.AreEqual(10_002, records[1].GetProperty("timestamp_ms").GetInt64());
    }

    [TestMethod]
    public void CompletesOnlyMissingMeasurementsOnFailure()
    {
        var recorder = Recorder(stopwatchFrequency: 1_000);
        recorder.Begin(Context());
        recorder.RecordCommandIssued(4_004);

        recorder.RecordFailure(GeneratedSyncFailureReason.PlayerFailed);
        recorder.RecordFailure(GeneratedSyncFailureReason.Cancelled);

        var records = Parse(recorder.ToNdjson());
        Assert.HasCount(2, records);
        Assert.AreEqual("ok", records[0].GetProperty("outcome").GetString());
        Assert.AreEqual("failure", records[1].GetProperty("outcome").GetString());
        Assert.AreEqual("player_failed", records[1].GetProperty("failure_reason").GetString());
        Assert.IsFalse(records[1].TryGetProperty("timestamp_ms", out _));
        Assert.IsFalse(records[1].TryGetProperty("target_timestamp_ms", out _));
        Assert.IsFalse(records[1].TryGetProperty("clock_id", out _));
        Assert.IsFalse(records[1].TryGetProperty("clock_uncertainty_ms", out _));
    }

    [TestMethod]
    public void PreservesACancelledAttemptAfterContextResolution()
    {
        var recorder = Recorder(stopwatchFrequency: 1_000);
        recorder.Begin(Context());

        recorder.RecordFailure(GeneratedSyncFailureReason.Cancelled);

        var records = Parse(recorder.ToNdjson());
        Assert.HasCount(2, records);
        Assert.IsTrue(records.All(record =>
            record.GetProperty("outcome").GetString() == "failure"));
        Assert.IsTrue(records.All(record =>
            record.GetProperty("failure_reason").GetString() == "cancelled"));
    }

    [TestMethod]
    public void DoesNotCreateFailureRecordsBeforeContextResolution()
    {
        var recorder = Recorder(stopwatchFrequency: 1_000);

        recorder.RecordFailure(GeneratedSyncFailureReason.PlayerFailed);

        Assert.AreEqual(string.Empty, recorder.ToNdjson());
    }

    [TestMethod]
    public void ExcludesCredentialsEndpointsAndAcousticClaims()
    {
        var recorder = Recorder(stopwatchFrequency: 1_000);
        recorder.Begin(Context());
        recorder.RecordCommandIssued(4_004);
        recorder.RecordPlaybackObserved(4_012);

        var json = recorder.ToNdjson();

        Assert.DoesNotContain("output_id", json);
        Assert.DoesNotContain("endpoint", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("token", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("acoustic", json, StringComparison.OrdinalIgnoreCase);
    }

    private static GeneratedSyncTelemetryRecorder Recorder(long stopwatchFrequency) => new(
        scenarioId: "mixed-01",
        clientId: "windows-laptop",
        outputCategory: "mixed",
        stopwatchFrequency);

    private static CoordinatorRunContext Context(long commandTargetTimestamp = 4_000) => new(
        TrialId,
        EffectiveAtUnixMilliseconds: 10_000,
        ClockUncertaintyMilliseconds: 3,
        commandTargetTimestamp);

    private static JsonElement[] Parse(string ndjson) => ndjson
        .Split('\n', StringSplitOptions.RemoveEmptyEntries)
        .Select(line => JsonDocument.Parse(line).RootElement.Clone())
        .ToArray();

    private static void AssertSuccess(
        JsonElement record,
        string eventType,
        long timestampMilliseconds)
    {
        Assert.AreEqual(2, record.GetProperty("schema_version").GetInt32());
        Assert.AreEqual(eventType, record.GetProperty("event_type").GetString());
        Assert.AreEqual("mixed-01", record.GetProperty("scenario_id").GetString());
        Assert.AreEqual(TrialId.ToString("D"), record.GetProperty("trial_id").GetString());
        Assert.AreEqual("10000", record.GetProperty("start_id").GetString());
        Assert.AreEqual("windows-laptop", record.GetProperty("device_id").GetString());
        Assert.AreEqual("windows", record.GetProperty("platform").GetString());
        Assert.AreEqual("physical", record.GetProperty("environment").GetString());
        Assert.AreEqual("generated_audio", record.GetProperty("provider").GetString());
        Assert.AreEqual(ToneGenerator.SignalId, record.GetProperty("signal_id").GetString());
        Assert.AreEqual(ToneGenerator.PcmSha256, record.GetProperty("signal_sha256").GetString());
        Assert.AreEqual("mixed", record.GetProperty("output_category").GetString());
        Assert.AreEqual("app_fanout", record.GetProperty("route_mode").GetString());
        Assert.AreEqual("ok", record.GetProperty("outcome").GetString());
        Assert.AreEqual(timestampMilliseconds, record.GetProperty("timestamp_ms").GetInt64());
        Assert.AreEqual(10_000, record.GetProperty("target_timestamp_ms").GetInt64());
        Assert.AreEqual(
            $"coordinator:{TrialId:D}",
            record.GetProperty("clock_id").GetString());
        Assert.AreEqual("coordinator_estimate", record.GetProperty("clock_source").GetString());
        Assert.AreEqual(3, record.GetProperty("clock_uncertainty_ms").GetInt64());
        Assert.IsFalse(record.TryGetProperty("failure_reason", out _));
    }
}
