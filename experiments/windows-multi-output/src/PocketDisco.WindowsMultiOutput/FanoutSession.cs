using System.Diagnostics;
using Windows.Devices.Enumeration;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;

namespace PocketDisco.WindowsMultiOutput;

public sealed record FanoutRunResult(
    long TargetUnixMilliseconds,
    long ReadyUnixMilliseconds,
    long CommandUnixMilliseconds,
    long CommandErrorMilliseconds,
    long CompletedUnixMilliseconds,
    TimeSpan InitialPosition,
    bool WasLate)
{
    public IReadOnlyList<string> CleanupWarnings { get; init; } = [];

    public CoordinatorRunContext? CoordinatorContext { get; init; }

    public long? PlaybackObservedTimestamp { get; init; }
}

public static class FanoutSession
{
    private const double SafeVolume = 0.12;
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan CompletionGrace = TimeSpan.FromSeconds(10);

    public static Task<FanoutRunResult> RunAsync(
        IReadOnlyList<RenderEndpoint> endpoints,
        TimeSpan duration,
        TimeSpan startDelay,
        long? targetUnixMilliseconds,
        CancellationToken cancellationToken) =>
        RunCoreAsync(
            endpoints,
            duration,
            startDelay,
            targetUnixMilliseconds,
            coordinatorStartResolver: null,
            onCoordinatorResolved: null,
            cancellationToken);

    public static Task<FanoutRunResult> RunCoordinatorAsync(
        IReadOnlyList<RenderEndpoint> endpoints,
        TimeSpan duration,
        CoordinatorStartResolver coordinatorStartResolver,
        Action<CoordinatorRunContext>? onCoordinatorResolved,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(coordinatorStartResolver);
        return RunCoreAsync(
            endpoints,
            duration,
            startDelay: TimeSpan.Zero,
            targetUnixMilliseconds: null,
            coordinatorStartResolver,
            onCoordinatorResolved,
            cancellationToken);
    }

    private static async Task<FanoutRunResult> RunCoreAsync(
        IReadOnlyList<RenderEndpoint> endpoints,
        TimeSpan duration,
        TimeSpan startDelay,
        long? targetUnixMilliseconds,
        CoordinatorStartResolver? coordinatorStartResolver,
        Action<CoordinatorRunContext>? onCoordinatorResolved,
        CancellationToken cancellationToken)
    {
        if (endpoints.Count != 2)
        {
            throw new ArgumentException("Two endpoints are required.", nameof(endpoints));
        }

        var wavPath = Path.Combine(
            Path.GetTempPath(),
            $"pocketdisco-{Guid.NewGuid():N}.wav");
        var players = new List<MediaPlayer>(endpoints.Count);
        var sources = new List<MediaSource>(endpoints.Count);
        var readiness = new PlaybackReadiness(endpoints.Count);
        var completion = new PlaybackCompletion(endpoints.Count);
        var playing = new PlaybackPlayingAggregator(endpoints.Count, Stopwatch.GetTimestamp);
        var startGate = new PlaybackStartGate();
        var controller = new MediaTimelineController();
        controller.Failed += (_, eventArgs) =>
            startGate.MarkControllerFailed(eventArgs.ExtendedError.Message);
        FanoutRunResult? result = null;
        IReadOnlyList<string> cleanupWarnings = [];

        try
        {
            await File.WriteAllBytesAsync(
                wavPath,
                ToneGenerator.CreateClickTrack(duration),
                cancellationToken);
            var file = await StorageFile.GetFileFromPathAsync(wavPath);
            for (var index = 0; index < endpoints.Count; index++)
            {
                var playerIndex = index;
                var endpoint = endpoints[index];
                var device = await DeviceInformation.CreateFromIdAsync(endpoint.InternalId);
                var source = MediaSource.CreateFromStorageFile(file);
                sources.Add(source);
                var player = new MediaPlayer();
                players.Add(player);
                player.AutoPlay = false;
                player.AudioDevice = device;
                player.RealTimePlayback = true;
                player.Volume = SafeVolume;

                player.CommandManager.IsEnabled = false;
                player.TimelineController = controller;
                player.MediaOpened += (_, _) => readiness.MarkOpened(playerIndex);
                player.MediaFailed += (_, eventArgs) =>
                {
                    startGate.MarkFailed(playerIndex, eventArgs.ErrorMessage);
                    readiness.MarkFailed(playerIndex, eventArgs.ErrorMessage);
                };
                player.MediaEnded += (_, _) => completion.MarkEnded(playerIndex);
                player.PlaybackSession.PlaybackStateChanged += (session, _) =>
                    playing.Update(
                        playerIndex,
                        session.PlaybackState == MediaPlaybackState.Playing);
                player.Source = source;
            }

            await PlaybackRunWaiter.WaitForReadinessAsync(
                readiness.Completion,
                startGate.Failure,
                OpenTimeout,
                cancellationToken);
            var readyUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            long target;
            ScheduledStart plan;
            CoordinatorRunContext? coordinatorContext = null;
            if (coordinatorStartResolver is not null)
            {
                var resolution = await CoordinatorStartWaiter.ResolveAsync(
                    coordinatorStartResolver.ResolveAsync,
                    startGate.Failure,
                    cancellationToken);
                plan = resolution.Plan;
                coordinatorContext = resolution.Context;
                target = coordinatorContext.EffectiveAtUnixMilliseconds;
                plan.ValidateMediaPosition(duration);
                onCoordinatorResolved?.Invoke(coordinatorContext);
            }
            else
            {
                var sampledTimestamp = Stopwatch.GetTimestamp();
                var sampledUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                if (targetUnixMilliseconds.HasValue)
                {
                    ScheduledStart.ValidateTargetLead(
                        targetUnixMilliseconds.Value,
                        sampledUnixMilliseconds,
                        startDelay);
                }

                target = targetUnixMilliseconds
                    ?? checked(sampledUnixMilliseconds + (long)startDelay.TotalMilliseconds);
                plan = ScheduledStart.Create(
                    target,
                    sampledUnixMilliseconds,
                    sampledTimestamp,
                    Stopwatch.GetTimestamp(),
                    Stopwatch.Frequency);
                plan.ValidateMediaPosition(duration);
            }

            controller.Position = plan.InitialPosition;
            var deadline = WaitUntilAsync(plan.DeadlineTimestamp, cancellationToken);
            var beforeStart = await Task.WhenAny(deadline, startGate.Failure);
            await beforeStart;
            var commandTimestamp = Stopwatch.GetTimestamp();
            var commandUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var commandErrorMilliseconds = plan.GetCommandErrorMilliseconds(
                commandTimestamp,
                Stopwatch.Frequency);
            startGate.IssueStart(controller.Resume);

            var remaining = duration - plan.InitialPosition;
            var completionTimeout = remaining + CompletionGrace;
            await PlaybackRunWaiter.WaitForCompletionAsync(
                completion.Completion,
                startGate.Failure,
                completionTimeout,
                cancellationToken);

            var completedUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            result = new FanoutRunResult(
                target,
                readyUnixMilliseconds,
                commandUnixMilliseconds,
                commandErrorMilliseconds,
                completedUnixMilliseconds,
                plan.InitialPosition,
                plan.WasLate)
            {
                CoordinatorContext = coordinatorContext,
                PlaybackObservedTimestamp = playing.FirstBothPlayingTimestamp,
            };
        }
        finally
        {
            var cleanup = new List<CleanupOperation>
            {
                new("pause timeline", controller.Pause),
            };
            for (var index = 0; index < players.Count; index++)
            {
                var player = players[index];
                cleanup.Add(new CleanupOperation(
                    $"dispose player {index + 1}",
                    player.Dispose));
            }

            for (var index = 0; index < sources.Count; index++)
            {
                var source = sources[index];
                cleanup.Add(new CleanupOperation(
                    $"dispose source {index + 1}",
                    source.Dispose));
            }

            cleanup.Add(new CleanupOperation(
                "delete temporary signal",
                () => File.Delete(wavPath)));
            cleanupWarnings = ResourceCleanup.AttemptAll(cleanup);
        }

        return result! with { CleanupWarnings = cleanupWarnings };
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
