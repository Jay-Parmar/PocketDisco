using System.Text.Json;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class FanoutTelemetryWriterTests
{
    [TestMethod]
    public void WritesSanitizedRecordsForEachOutput()
    {
        var result = new FanoutRunResult(
            TargetUnixMilliseconds: 10_000,
            ReadyUnixMilliseconds: 8_000,
            CommandUnixMilliseconds: 10_007,
            CommandTimestamp: 123_000,
            CompletedUnixMilliseconds: 15_010,
            InitialPosition: TimeSpan.Zero,
            WasLate: false);

        var ndjson = FanoutTelemetryWriter.ToNdjson(
            result,
            outputCount: 2,
            runId: Guid.Parse("11111111-1111-1111-1111-111111111111"));
        var lines = ndjson.Split('\n', StringSplitOptions.RemoveEmptyEntries);

        Assert.HasCount(6, lines);
        Assert.DoesNotContain("internal-a", ndjson);
        Assert.DoesNotContain("Headphones", ndjson);

        using var startRecord = JsonDocument.Parse(lines[2]);
        var root = startRecord.RootElement;
        Assert.AreEqual(1, root.GetProperty("schema_version").GetInt32());
        Assert.AreEqual("windows_multi_output_probe", root.GetProperty("record_type").GetString());
        Assert.AreEqual("playback_start_command", root.GetProperty("event_type").GetString());
        Assert.AreEqual("output-1", root.GetProperty("output_id").GetString());
        Assert.AreEqual("windows", root.GetProperty("platform").GetString());
        Assert.AreEqual("app_fanout", root.GetProperty("route_mode").GetString());
        Assert.AreEqual(7, root.GetProperty("command_error_ms").GetInt64());
    }

    [TestMethod]
    public void WritesSanitizedFailureRecords()
    {
        var ndjson = FanoutTelemetryWriter.ToFailureNdjson(
            outputCount: 2,
            runId: Guid.Parse("11111111-1111-1111-1111-111111111111"),
            timestampMilliseconds: 10_000,
            FanoutFailureReason.PlaybackFailed);
        var lines = ndjson.Split('\n', StringSplitOptions.RemoveEmptyEntries);

        Assert.HasCount(2, lines);
        using var record = JsonDocument.Parse(lines[0]);
        var root = record.RootElement;
        Assert.AreEqual("failed", root.GetProperty("outcome").GetString());
        Assert.AreEqual("playback_failed", root.GetProperty("failure_reason").GetString());
    }
}
