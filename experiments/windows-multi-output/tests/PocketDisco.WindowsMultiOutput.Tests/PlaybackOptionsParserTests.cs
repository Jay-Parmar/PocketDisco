namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class PlaybackOptionsParserTests
{
    [TestMethod]
    public void ParsesSelectedDevicesAndTiming()
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,2",
                "--duration-seconds", "20",
                "--start-delay-ms", "5000",
                "--telemetry-file", "run.ndjson",
            ]);

        Assert.IsTrue(result.IsValid);
        Assert.HasCount(2, result.Options!.DeviceIndexes);
        Assert.AreEqual(0, result.Options.DeviceIndexes[0]);
        Assert.AreEqual(2, result.Options.DeviceIndexes[1]);
        Assert.AreEqual(TimeSpan.FromSeconds(20), result.Options.Duration);
        Assert.AreEqual(TimeSpan.FromSeconds(5), result.Options.StartDelay);
        Assert.IsNull(result.Options.TargetUnixMilliseconds);
        Assert.AreEqual("run.ndjson", result.Options.TelemetryPath);
    }

    [TestMethod]
    public void ParsesAbsoluteStartTime()
    {
        var result = PlaybackOptionsParser.Parse(
            ["--devices", "0,1", "--start-at-unix-ms", "1780000000000"]);

        Assert.IsTrue(result.IsValid);
        Assert.AreEqual(1_780_000_000_000, result.Options!.TargetUnixMilliseconds);
    }

    [TestMethod]
    public void RejectsConflictingStartOptions()
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--start-at-unix-ms", "1780000000000",
                "--start-delay-ms", "3000",
            ]);

        Assert.IsFalse(result.IsValid);
        Assert.IsNotNull(result.Error);
        Assert.Contains("Choose either", result.Error);
    }

    [TestMethod]
    public void RejectsUnknownOption()
    {
        var result = PlaybackOptionsParser.Parse(["--devices", "0,1", "--loud"]);

        Assert.IsFalse(result.IsValid);
        Assert.IsNotNull(result.Error);
        Assert.Contains("Unknown option", result.Error);
    }
}
