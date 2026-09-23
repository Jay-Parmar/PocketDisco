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
          --coordinator-url URL   Phase 0 coordinator base URL
          --coordinator-trial X   Create a trial or fetch its UUID
          --telemetry-file PATH   Save sanitized NDJSON evidence
          --scenario-id ID        Shared mixed-platform scenario ID
          --client-id ID          Stable Windows client ID
          --output-category X     built_in, wired, bluetooth, usb, virtual, or mixed
          --sync-telemetry-file P Save schema v2 mixed-platform NDJSON

        Coordinator mode:
          Set POCKETDISCO_COORDINATOR_TOKEN in the process environment.
          Use --coordinator-trial create or a canonical trial UUID.
          Local start timing options cannot be combined with coordinator mode.
          Schema v2 telemetry options must be supplied together.
        """;
}
