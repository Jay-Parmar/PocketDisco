namespace PocketDisco.WindowsMultiOutput;

public static class ProbeInfo
{
    public const string Name = "PocketDisco Windows multi-output probe";

    public const string Usage = """
        PocketDisco Windows multi-output probe

        Commands:
          --list-devices    List active audio render endpoints
          --devices A,B     Play a generated click track on two endpoint indexes
          --help            Show this help

        Playback options:
          --duration-seconds N    Duration from 1 to 600, default 15
          --start-delay-ms N      Lead time from 5000 to 60000, default 5000
          --start-at-unix-ms N    Absolute UTC start time instead of a delay
          --telemetry-file PATH   Save sanitized NDJSON evidence
        """;
}
