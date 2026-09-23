using System.Diagnostics;
using Windows.Devices.Enumeration;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;

namespace PocketDisco.WindowsMultiOutput;

public sealed record FanoutRunResult(
    long TargetUnixMilliseconds,
    long CommandUnixMilliseconds,
    long CommandTimestamp,
    TimeSpan InitialPosition,
    bool WasLate);

public static class FanoutSession
{
    private const double SafeVolume = 0.12;
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(20);

    public static async Task<FanoutRunResult> RunAsync(
        IReadOnlyList<RenderEndpoint> endpoints,
        TimeSpan duration,
        TimeSpan startDelay,
        long? targetUnixMilliseconds,
        CancellationToken cancellationToken)
    {
        if (endpoints.Count != 2)
        {
            throw new ArgumentException("Two endpoints are required.", nameof(endpoints));
        }

        var wavPath = Path.Combine(
            Path.GetTempPath(),
            $"pocketdisco-{Guid.NewGuid():N}.wav");
        await File.WriteAllBytesAsync(
            wavPath,
            ToneGenerator.CreateClickTrack(duration),
            cancellationToken);

        var players = new List<MediaPlayer>(endpoints.Count);
        var sources = new List<MediaSource>(endpoints.Count);
        var readiness = new PlaybackReadiness(endpoints.Count);
        var playbackFailure = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var controller = new MediaTimelineController();

        try
        {
            var file = await StorageFile.GetFileFromPathAsync(wavPath);
            for (var index = 0; index < endpoints.Count; index++)
            {
                var playerIndex = index;
                var endpoint = endpoints[index];
                var device = await DeviceInformation.CreateFromIdAsync(endpoint.InternalId);
                var source = MediaSource.CreateFromStorageFile(file);
                var player = new MediaPlayer
                {
                    AutoPlay = false,
                    AudioDevice = device,
                    RealTimePlayback = true,
                    Volume = SafeVolume,
                };

                player.CommandManager.IsEnabled = false;
                player.TimelineController = controller;
                player.MediaOpened += (_, _) => readiness.MarkOpened(playerIndex);
                player.MediaFailed += (_, eventArgs) =>
                {
                    readiness.MarkFailed(playerIndex, eventArgs.ErrorMessage);
                    playbackFailure.TrySetException(
                        new InvalidOperationException(
                            $"output-{playerIndex + 1} failed: {eventArgs.ErrorMessage}"));
                };
                player.Source = source;
                sources.Add(source);
                players.Add(player);
            }

            await readiness.Completion.WaitAsync(OpenTimeout, cancellationToken);

            var sampledTimestamp = Stopwatch.GetTimestamp();
            var sampledUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var target = targetUnixMilliseconds
                ?? checked(sampledUnixMilliseconds + (long)startDelay.TotalMilliseconds);
            var plan = ScheduledStart.Create(
                target,
                sampledUnixMilliseconds,
                sampledTimestamp,
                Stopwatch.GetTimestamp(),
                Stopwatch.Frequency);

            controller.Position = plan.InitialPosition;
            await WaitUntilAsync(plan.DeadlineTimestamp, cancellationToken);
            var commandTimestamp = Stopwatch.GetTimestamp();
            var commandUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            controller.Resume();

            var remaining = duration - plan.InitialPosition;
            if (remaining > TimeSpan.Zero)
            {
                var completed = await Task.WhenAny(
                    Task.Delay(remaining, cancellationToken),
                    playbackFailure.Task);
                await completed;
            }

            return new FanoutRunResult(
                target,
                commandUnixMilliseconds,
                commandTimestamp,
                plan.InitialPosition,
                plan.WasLate);
        }
        finally
        {
            controller.Pause();
            foreach (var player in players)
            {
                player.Dispose();
            }

            foreach (var source in sources)
            {
                source.Dispose();
            }

            File.Delete(wavPath);
        }
    }

    private static async Task WaitUntilAsync(
        long deadlineTimestamp,
        CancellationToken cancellationToken)
    {
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var remainingTicks = deadlineTimestamp - Stopwatch.GetTimestamp();
            if (remainingTicks <= 0)
            {
                return;
            }

            var remaining = TimeSpan.FromSeconds((double)remainingTicks / Stopwatch.Frequency);
            if (remaining > TimeSpan.FromMilliseconds(8))
            {
                await Task.Delay(remaining - TimeSpan.FromMilliseconds(4), cancellationToken);
                continue;
            }

            Thread.SpinWait(64);
        }
    }
}
