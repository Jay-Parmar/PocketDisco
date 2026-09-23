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

Console.Error.WriteLine("Unknown command. Use --help for usage.");
Environment.ExitCode = 2;
