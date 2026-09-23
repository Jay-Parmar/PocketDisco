using System.Globalization;

namespace PocketDisco.WindowsMultiOutput;

public sealed record PlaybackOptions(
    IReadOnlyList<int> DeviceIndexes,
    TimeSpan Duration,
    TimeSpan StartDelay,
    long? TargetUnixMilliseconds,
    CoordinatorPlaybackOptions? Coordinator,
    string? TelemetryPath);

public sealed record CoordinatorPlaybackOptions(string BaseUrl, Guid? TrialId)
{
    public bool CreateTrial => !TrialId.HasValue;
}

public sealed record PlaybackOptionsParseResult(PlaybackOptions? Options, string? Error)
{
    public bool IsValid => Options is not null;
}

public static class PlaybackOptionsParser
{
    private static readonly TimeSpan DefaultDuration = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan DefaultStartDelay = TimeSpan.FromSeconds(5);

    public static PlaybackOptionsParseResult Parse(IReadOnlyList<string> arguments)
    {
        int[]? deviceIndexes = null;
        var duration = DefaultDuration;
        var startDelay = DefaultStartDelay;
        long? targetUnixMilliseconds = null;
        string? coordinatorUrl = null;
        Guid? coordinatorTrialId = null;
        string? telemetryPath = null;
        var hasStartDelay = false;
        var hasCoordinatorUrl = false;
        var hasCoordinatorTrial = false;

        for (var index = 0; index < arguments.Count; index += 2)
        {
            var option = arguments[index];
            if (option is not "--devices"
                and not "--duration-seconds"
                and not "--start-delay-ms"
                and not "--start-at-unix-ms"
                and not "--coordinator-url"
                and not "--coordinator-trial"
                and not "--telemetry-file")
            {
                return Invalid($"Unknown option: {option}.");
            }

            if (index + 1 >= arguments.Count)
            {
                return Invalid($"Missing value for {option}.");
            }

            var value = arguments[index + 1];
            switch (option)
            {
                case "--devices":
                    if (!TryParseDeviceIndexes(value, out deviceIndexes))
                    {
                        return Invalid("--devices must contain two non-negative indexes separated by a comma.");
                    }

                    break;
                case "--duration-seconds":
                    if (!TryParseInRange(value, 1, 600, out var durationSeconds))
                    {
                        return Invalid("--duration-seconds must be between 1 and 600.");
                    }

                    duration = TimeSpan.FromSeconds(durationSeconds);
                    break;
                case "--start-delay-ms":
                    if (!TryParseInRange(value, 5_000, 60_000, out var delayMilliseconds))
                    {
                        return Invalid("--start-delay-ms must be between 5000 and 60000.");
                    }

                    startDelay = TimeSpan.FromMilliseconds(delayMilliseconds);
                    hasStartDelay = true;
                    break;
                case "--start-at-unix-ms":
                    if (!long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var target)
                        || target <= 0)
                    {
                        return Invalid("--start-at-unix-ms must be a positive integer.");
                    }

                    targetUnixMilliseconds = target;
                    break;
                case "--coordinator-url":
                    try
                    {
                        coordinatorUrl = CoordinatorUrl.Normalize(value);
                        hasCoordinatorUrl = true;
                    }
                    catch (ArgumentException error)
                    {
                        return Invalid(error.Message);
                    }

                    break;
                case "--coordinator-trial":
                    hasCoordinatorTrial = true;
                    if (value == "create")
                    {
                        coordinatorTrialId = null;
                    }
                    else if (!Guid.TryParseExact(value, "D", out var parsedTrialId))
                    {
                        return Invalid("--coordinator-trial must be create or a UUID.");
                    }
                    else
                    {
                        coordinatorTrialId = parsedTrialId;
                    }

                    break;
                case "--telemetry-file":
                    if (string.IsNullOrWhiteSpace(value))
                    {
                        return Invalid("--telemetry-file must not be empty.");
                    }

                    telemetryPath = value;
                    break;
            }
        }

        if (deviceIndexes is null)
        {
            return Invalid("--devices is required.");
        }

        if (targetUnixMilliseconds.HasValue && hasStartDelay)
        {
            return Invalid("Choose either --start-at-unix-ms or --start-delay-ms.");
        }

        if (hasCoordinatorUrl != hasCoordinatorTrial)
        {
            return Invalid("--coordinator-url and --coordinator-trial must be used together.");
        }

        CoordinatorPlaybackOptions? coordinator = null;
        if (hasCoordinatorUrl)
        {
            if (targetUnixMilliseconds.HasValue || hasStartDelay)
            {
                return Invalid("Coordinator mode cannot use local start timing options.");
            }

            coordinator = new CoordinatorPlaybackOptions(
                coordinatorUrl!,
                coordinatorTrialId);
        }

        return new PlaybackOptionsParseResult(
            new PlaybackOptions(
                deviceIndexes,
                duration,
                startDelay,
                targetUnixMilliseconds,
                coordinator,
                telemetryPath),
            null);
    }

    private static bool TryParseDeviceIndexes(string value, out int[]? indexes)
    {
        var parts = value.Split(',', StringSplitOptions.TrimEntries);
        if (parts.Length != 2)
        {
            indexes = null;
            return false;
        }

        indexes = new int[2];
        for (var index = 0; index < parts.Length; index++)
        {
            if (!int.TryParse(parts[index], NumberStyles.None, CultureInfo.InvariantCulture, out indexes[index])
                || indexes[index] < 0)
            {
                indexes = null;
                return false;
            }
        }

        return true;
    }

    private static bool TryParseInRange(string value, int minimum, int maximum, out int number) =>
        int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out number)
        && number >= minimum
        && number <= maximum;

    private static PlaybackOptionsParseResult Invalid(string error) => new(null, error);
}
