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
        Assert.IsNull(result.Options.Coordinator);
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
                "--start-delay-ms", "5000",
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

    [TestMethod]
    public void RejectsStartDelayBelowProtocolMinimum()
    {
        var result = PlaybackOptionsParser.Parse(
            ["--devices", "0,1", "--start-delay-ms", "4999"]);

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    [DataRow("0,,1")]
    [DataRow("0,1,")]
    [DataRow(",0,1")]
    public void RejectsMissingDeviceTokens(string value)
    {
        var result = PlaybackOptionsParser.Parse(["--devices", value]);

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    public void ParsesCoordinatorTrialCreation()
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://192.168.1.20:8765/",
                "--coordinator-trial", "create",
            ]);

        Assert.IsTrue(result.IsValid);
        Assert.AreEqual(
            "http://192.168.1.20:8765",
            result.Options!.Coordinator!.BaseUrl);
        Assert.IsTrue(result.Options.Coordinator.CreateTrial);
        Assert.IsNull(result.Options.Coordinator.TrialId);
    }

    [TestMethod]
    public void ParsesCoordinatorTrialUuid()
    {
        var trialId = Guid.Parse("11111111-2222-3333-4444-555555555555");

        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "https://coordinator.example.test",
                "--coordinator-trial", trialId.ToString("D"),
            ]);

        Assert.IsTrue(result.IsValid);
        Assert.IsFalse(result.Options!.Coordinator!.CreateTrial);
        Assert.AreEqual(trialId, result.Options.Coordinator.TrialId);
    }

    [TestMethod]
    [DataRow("--coordinator-url", "http://127.0.0.1:8765")]
    [DataRow("--coordinator-trial", "create")]
    public void RejectsIncompleteCoordinatorOptions(string option, string value)
    {
        var result = PlaybackOptionsParser.Parse(["--devices", "0,1", option, value]);

        Assert.IsFalse(result.IsValid);
        Assert.Contains("together", result.Error!);
    }

    [TestMethod]
    [DataRow("not-a-uuid")]
    [DataRow("CREATE")]
    public void RejectsInvalidCoordinatorTrialSelection(string value)
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://127.0.0.1:8765",
                "--coordinator-trial", value,
            ]);

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    public void RejectsPublicCleartextCoordinatorUrl()
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://public.example.test",
                "--coordinator-trial", "create",
            ]);

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    [DataRow("--start-delay-ms", "5000")]
    [DataRow("--start-at-unix-ms", "1780000000000")]
    public void RejectsLocalTimingInCoordinatorMode(string option, string value)
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://127.0.0.1:8765",
                "--coordinator-trial", "create",
                option, value,
            ]);

        Assert.IsFalse(result.IsValid);
        Assert.Contains("coordinator", result.Error!, StringComparison.OrdinalIgnoreCase);
    }

    [TestMethod]
    public void DoesNotAcceptBearerTokensAsArguments()
    {
        var result = PlaybackOptionsParser.Parse(
            ["--devices", "0,1", "--coordinator-token", "secret"]);

        Assert.IsFalse(result.IsValid);
        Assert.Contains("Unknown option", result.Error!);
    }

    [TestMethod]
    public void ParsesCompleteCoordinatorSyncTelemetryOptions()
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://127.0.0.1:8765",
                "--coordinator-trial", "create",
                "--scenario-id", "  mixed-01  ",
                "--client-id", " windows-laptop ",
                "--output-category", "mixed",
                "--sync-telemetry-file", "mixed-01-windows.ndjson",
            ]);

        Assert.IsTrue(result.IsValid);
        var telemetry = result.Options!.SyncTelemetry!;
        Assert.AreEqual("mixed-01", telemetry.ScenarioId);
        Assert.AreEqual("windows-laptop", telemetry.ClientId);
        Assert.AreEqual("mixed", telemetry.OutputCategory);
        Assert.AreEqual("mixed-01-windows.ndjson", telemetry.Path);
    }

    [TestMethod]
    [DataRow("built_in")]
    [DataRow("wired")]
    [DataRow("bluetooth")]
    [DataRow("usb")]
    [DataRow("virtual")]
    [DataRow("mixed")]
    public void AcceptsEverySyncOutputCategory(string outputCategory)
    {
        var result = ParseSyncTelemetry(outputCategory: outputCategory);

        Assert.IsTrue(result.IsValid);
        Assert.AreEqual(outputCategory, result.Options!.SyncTelemetry!.OutputCategory);
    }

    [TestMethod]
    [DataRow("--scenario-id", "mixed-01")]
    [DataRow("--client-id", "windows-laptop")]
    [DataRow("--output-category", "mixed")]
    [DataRow("--sync-telemetry-file", "run.ndjson")]
    public void RejectsIncompleteSyncTelemetryOptions(string option, string value)
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://127.0.0.1:8765",
                "--coordinator-trial", "create",
                option, value,
            ]);

        Assert.IsFalse(result.IsValid);
        Assert.Contains("together", result.Error!);
    }

    [TestMethod]
    public void RejectsSyncTelemetryOutsideCoordinatorMode()
    {
        var result = PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--scenario-id", "mixed-01",
                "--client-id", "windows-laptop",
                "--output-category", "mixed",
                "--sync-telemetry-file", "run.ndjson",
            ]);

        Assert.IsFalse(result.IsValid);
        Assert.Contains("coordinator", result.Error!, StringComparison.OrdinalIgnoreCase);
    }

    [TestMethod]
    [DataRow("--scenario-id", " ")]
    [DataRow("--scenario-id", "mixed\n01")]
    [DataRow("--client-id", " ")]
    [DataRow("--client-id", "windows\nlaptop")]
    public void RejectsUnsafeSyncTelemetryIdentity(string option, string value)
    {
        var scenarioId = option == "--scenario-id" ? value : "mixed-01";
        var clientId = option == "--client-id" ? value : "windows-laptop";

        var result = ParseSyncTelemetry(scenarioId, clientId);

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    public void RejectsOversizedSyncTelemetryIdentity()
    {
        var result = ParseSyncTelemetry(scenarioId: new string('s', 101));

        Assert.IsFalse(result.IsValid);
    }

    [TestMethod]
    public void RejectsInvalidSyncOutputCategory()
    {
        var result = ParseSyncTelemetry(outputCategory: "headphones");

        Assert.IsFalse(result.IsValid);
    }

    private static PlaybackOptionsParseResult ParseSyncTelemetry(
        string scenarioId = "mixed-01",
        string clientId = "windows-laptop",
        string outputCategory = "mixed") =>
        PlaybackOptionsParser.Parse(
            [
                "--devices", "0,1",
                "--coordinator-url", "http://127.0.0.1:8765",
                "--coordinator-trial", "create",
                "--scenario-id", scenarioId,
                "--client-id", clientId,
                "--output-category", outputCategory,
                "--sync-telemetry-file", "run.ndjson",
            ]);
}
