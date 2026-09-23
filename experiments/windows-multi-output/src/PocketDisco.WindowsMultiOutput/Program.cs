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

Console.WriteLine("Selected outputs:");
foreach (var endpoint in selection.Endpoints)
{
    Console.WriteLine($"  [{endpoint.Index}] {endpoint.DisplayName}");
}

Console.WriteLine("The click track uses 12% application volume.");
try
{
    var result = await FanoutSession.RunAsync(
        selection.Endpoints,
        options.Duration,
        options.StartDelay,
        options.TargetUnixMilliseconds,
        CancellationToken.None);
    Console.WriteLine($"Start target: {result.TargetUnixMilliseconds}");
    Console.WriteLine($"Start command: {result.CommandUnixMilliseconds}");
    Console.WriteLine($"Late media position: {result.InitialPosition.TotalMilliseconds:F1} ms");
}
catch (Exception exception) when (exception is not OperationCanceledException)
{
    Console.Error.WriteLine($"Playback failed: {exception.Message}");
    Environment.ExitCode = 1;
}
