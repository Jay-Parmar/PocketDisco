using PocketDisco.WindowsMultiOutput;

if (args.SequenceEqual(["--list-devices"]))
{
    var endpoints = await AudioEndpointCatalog.GetActiveAsync();
    foreach (var line in EndpointListFormatter.Format(endpoints))
    {
        Console.WriteLine(line);
    }

    return;
}

if (args.Length == 0 || args.SequenceEqual(["--help"]))
{
    Console.WriteLine(ProbeInfo.Usage);
    return;
}

var parsed = PlaybackOptionsParser.Parse(args);
if (!parsed.IsValid)
{
    Console.Error.WriteLine(parsed.Error);
    Console.Error.WriteLine("Use --help for usage.");
    Environment.ExitCode = 2;
    return;
}

var options = parsed.Options!;
var available = await AudioEndpointCatalog.GetActiveAsync();
var selection = EndpointSelector.Select(available, options.DeviceIndexes);
if (!selection.IsValid)
{
    Console.Error.WriteLine($"Invalid endpoint selection: {selection.Failure}.");
    Environment.ExitCode = 2;
    return;
}

CoordinatorClient? coordinatorClient = null;
CoordinatorStartResolver? coordinatorStartResolver = null;
if (options.Coordinator is not null)
{
    try
    {
        var bearerToken = CoordinatorTokenSource.Read(Environment.GetEnvironmentVariable);
        coordinatorClient = new CoordinatorClient(options.Coordinator.BaseUrl, bearerToken);
        coordinatorStartResolver = new CoordinatorStartResolver(
            coordinatorClient,
            options.Coordinator);
    }
    catch (Exception exception) when (exception is ArgumentException or InvalidOperationException)
    {
        Console.Error.WriteLine($"Coordinator setup failed: {exception.Message}");
        Environment.ExitCode = 2;
        return;
    }
}

using var coordinatorClientLifetime = coordinatorClient;
Console.WriteLine("Selected outputs:");
foreach (var endpoint in selection.Endpoints)
{
    Console.WriteLine($"  [{endpoint.Index}] {endpoint.DisplayName}");
}

Console.WriteLine("The click track uses 12% application volume.");
var runId = Guid.NewGuid();
using var cancellation = new CancellationTokenSource();
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    cancellation.Cancel();
};
FanoutRunResult result;
try
{
    if (coordinatorStartResolver is null)
    {
        result = await FanoutSession.RunAsync(
            selection.Endpoints,
            options.Duration,
            options.StartDelay,
            options.TargetUnixMilliseconds,
            cancellation.Token);
    }
    else
    {
        result = await FanoutSession.RunCoordinatorAsync(
            selection.Endpoints,
            options.Duration,
            coordinatorStartResolver,
            context =>
            {
                Console.WriteLine($"Coordinator trial: {context.TrialId:D}");
                Console.WriteLine(
                    $"Coordinator effective time: {context.EffectiveAtUnixMilliseconds}");
                Console.WriteLine(
                    $"Coordinator clock uncertainty: {context.ClockUncertaintyMilliseconds} ms");
            },
            cancellation.Token);
    }
}
catch (OperationCanceledException)
{
    if (options.TelemetryPath is not null)
    {
        try
        {
            var telemetryPath = await TelemetryFileWriter.WriteNewAsync(
                options.TelemetryPath,
                FanoutTelemetryWriter.ToFailureNdjson(
                    selection.Endpoints.Count,
                    runId,
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    FanoutFailureReason.Cancelled),
                CancellationToken.None);
            Console.WriteLine($"Telemetry: {telemetryPath}");
        }
        catch (Exception telemetryException)
        {
            Console.Error.WriteLine($"Telemetry write failed: {telemetryException.Message}");
        }
    }

    Console.Error.WriteLine("Playback cancelled.");
    Environment.ExitCode = 130;
    return;
}
catch (Exception exception)
{
    if (options.TelemetryPath is not null)
    {
        try
        {
            var telemetryPath = await TelemetryFileWriter.WriteNewAsync(
                options.TelemetryPath,
                FanoutTelemetryWriter.ToFailureNdjson(
                    selection.Endpoints.Count,
                    runId,
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    FanoutFailureReason.PlaybackFailed),
                CancellationToken.None);
            Console.WriteLine($"Telemetry: {telemetryPath}");
        }
        catch (Exception telemetryException)
        {
            Console.Error.WriteLine($"Telemetry write failed: {telemetryException.Message}");
        }
    }

    Console.Error.WriteLine($"Playback failed: {exception.Message}");
    Environment.ExitCode = 1;
    return;
}

Console.WriteLine($"Start target: {result.TargetUnixMilliseconds}");
Console.WriteLine($"Start command: {result.CommandUnixMilliseconds}");
Console.WriteLine($"Late media position: {result.InitialPosition.TotalMilliseconds:F1} ms");
foreach (var warning in result.CleanupWarnings)
{
    Console.Error.WriteLine($"Cleanup warning: {warning}.");
}

if (options.TelemetryPath is not null)
{
    try
    {
        var telemetryPath = await TelemetryFileWriter.WriteNewAsync(
            options.TelemetryPath,
            FanoutTelemetryWriter.ToNdjson(result, selection.Endpoints.Count, runId),
            cancellation.Token);
        Console.WriteLine($"Telemetry: {telemetryPath}");
    }
    catch (Exception exception)
    {
        Console.Error.WriteLine($"Telemetry write failed: {exception.Message}");
        Environment.ExitCode = 1;
    }
}
